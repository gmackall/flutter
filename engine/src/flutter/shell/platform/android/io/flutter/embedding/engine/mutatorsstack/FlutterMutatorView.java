// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine.mutatorsstack;

import static android.view.View.OnFocusChangeListener;
import static io.flutter.Build.API_LEVELS;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import io.flutter.Log;
import io.flutter.embedding.android.AndroidTouchProcessor;
import io.flutter.plugin.platform.PlatformViewGestureTracker;
import io.flutter.util.ViewUtils;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A view that applies the {@link io.flutter.embedding.engine.mutatorsstack.FlutterMutatorsStack} to
 * its children.
 */
public class FlutterMutatorView extends FrameLayout {
  private static final String TAG = "FlutterMutatorView";

  private FlutterMutatorsStack mutatorsStack;
  private float screenDensity;

  // The position of this view in its parent. This is the position of the platform view's bounding
  // rect unless a runtime shader filter is applied, in which case the view is laid out with the
  // filter's frame as its bounds.
  private int viewOriginLeft;
  private int viewOriginTop;

  // SurfaceView content is composited by SurfaceFlinger in a separate layer, so it can't be
  // filtered by a RenderEffect.
  @SuppressWarnings("unchecked")
  private static final Class<? extends View>[] VIEW_TYPES_THAT_PREVENT_RENDER_EFFECTS =
      new Class[] {SurfaceView.class};
  @Nullable private Boolean cachedHasChildSurfaceView = null;

  // The ids of programs whose AGSL failed to compile or whose uniforms couldn't be set.
  private static final Set<Long> failedRuntimeShaderPrograms = ConcurrentHashMap.newKeySet();

  // Each view owns its own RuntimeShader so that the uniforms of views that are filtered in the
  // same frame don't interfere with each other.
  @Nullable private Object runtimeShader; // RuntimeShader, which requires API 33.
  private long runtimeShaderProgramId = -1;
  @Nullable private FlutterMutatorsStack.RuntimeShaderFilter appliedRuntimeShaderFilter;

  private final AndroidTouchProcessor androidTouchProcessor;
  private final PlatformViewGestureTracker gestureTracker = new PlatformViewGestureTracker();
  private Paint paint;

  /**
   * Initialize the FlutterMutatorView. Use this to set the screenDensity, which will be used to
   * correct the final transform matrix.
   */
  public FlutterMutatorView(
      @NonNull Context context,
      float screenDensity,
      @Nullable AndroidTouchProcessor androidTouchProcessor) {
    super(context, null);
    this.screenDensity = screenDensity;
    this.androidTouchProcessor = androidTouchProcessor;
    this.paint = new Paint();
  }

  /** Initialize the FlutterMutatorView. */
  public FlutterMutatorView(@NonNull Context context) {
    this(context, 1, /* androidTouchProcessor=*/ null);
  }

  @Nullable @VisibleForTesting ViewTreeObserver.OnGlobalFocusChangeListener activeFocusListener;

  /**
   * Sets a focus change listener that notifies when the current view or any of its descendant views
   * have received focus.
   *
   * <p>If there's an active focus listener, it will first remove the current listener, and then add
   * the new one.
   *
   * @param userFocusListener A user provided focus listener.
   */
  public void setOnDescendantFocusChangeListener(@NonNull OnFocusChangeListener userFocusListener) {
    unsetOnDescendantFocusChangeListener();

    final View mutatorView = this;
    final ViewTreeObserver observer = getViewTreeObserver();
    if (observer.isAlive() && activeFocusListener == null) {
      activeFocusListener =
          new ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override
            public void onGlobalFocusChanged(View oldFocus, View newFocus) {
              userFocusListener.onFocusChange(mutatorView, ViewUtils.childHasFocus(mutatorView));
            }
          };
      observer.addOnGlobalFocusChangeListener(activeFocusListener);
    }
  }

  /** Unsets any active focus listener. */
  public void unsetOnDescendantFocusChangeListener() {
    final ViewTreeObserver observer = getViewTreeObserver();
    if (observer.isAlive() && activeFocusListener != null) {
      final ViewTreeObserver.OnGlobalFocusChangeListener currFocusListener = activeFocusListener;
      activeFocusListener = null;
      observer.removeOnGlobalFocusChangeListener(currFocusListener);
    }
  }

  /**
   * Pass the necessary parameters to the view so it can apply correct mutations to its children.
   */
  public void readyToDisplay(
      @NonNull FlutterMutatorsStack mutatorsStack, int left, int top, int width, int height) {
    this.mutatorsStack = mutatorsStack;

    int layoutLeft = left;
    int layoutTop = top;
    int layoutWidth = width;
    int layoutHeight = height;

    FlutterMutatorsStack.RuntimeShaderFilter filter = mutatorsStack.getFinalRuntimeShaderFilter();
    boolean appliedFilter = false;
    if (filter != null
        && !filter.isEmpty()
        && Build.VERSION.SDK_INT >= API_LEVELS.API_33
        && !hasChildSurfaceView()) {
      appliedFilter = applyRuntimeShaderFilter(filter);
    }
    if (appliedFilter) {
      // The shader's coordinates are relative to the filter's frame, so lay the view out with the
      // frame as its bounds. This also gives the filter room to move content outside of the
      // platform view's own bounds (e.g. when stretching).
      layoutLeft = filter.left;
      layoutTop = filter.top;
      layoutWidth = filter.getWidth();
      layoutHeight = filter.getHeight();
    } else {
      if (appliedRuntimeShaderFilter != null && Build.VERSION.SDK_INT >= API_LEVELS.API_33) {
        clearRuntimeShaderFilter();
      }
      if (filter == null) {
        // Re-check for SurfaceViews the next time a filter is applied.
        cachedHasChildSurfaceView = null;
      }
    }

    this.viewOriginLeft = layoutLeft;
    this.viewOriginTop = layoutTop;
    FrameLayout.LayoutParams layoutParams =
        new FrameLayout.LayoutParams(layoutWidth, layoutHeight, Gravity.LEFT | Gravity.TOP);
    layoutParams.leftMargin = layoutLeft;
    layoutParams.topMargin = layoutTop;
    setLayoutParams(layoutParams);
    setWillNotDraw(false);
  }

  private boolean hasChildSurfaceView() {
    if (cachedHasChildSurfaceView == null) {
      cachedHasChildSurfaceView =
          ViewUtils.hasChildViewOfType(this, VIEW_TYPES_THAT_PREVENT_RENDER_EFFECTS);
    }
    return cachedHasChildSurfaceView;
  }

  @Override
  public void onViewAdded(View child) {
    super.onViewAdded(child);
    cachedHasChildSurfaceView = null;
  }

  @Override
  public void onViewRemoved(View child) {
    super.onViewRemoved(child);
    cachedHasChildSurfaceView = null;
  }

  /**
   * Applies {@code filter} as a RenderEffect.
   *
   * @return whether the filter is applied. If it isn't, the platform view is drawn without it.
   */
  @RequiresApi(API_LEVELS.API_33)
  private boolean applyRuntimeShaderFilter(
      @NonNull FlutterMutatorsStack.RuntimeShaderFilter filter) {
    if (filter.equals(appliedRuntimeShaderFilter)) {
      return true;
    }
    final long programId = filter.programId;
    if (failedRuntimeShaderPrograms.contains(programId)) {
      return false;
    }
    final FlutterMutatorsStack.RuntimeShaderProgram program =
        FlutterMutatorsStack.getRuntimeShaderProgram(programId);
    if (program == null) {
      return false;
    }
    try {
      if (runtimeShader == null || runtimeShaderProgramId != programId) {
        runtimeShader = new RuntimeShader(program.agslSource);
        runtimeShaderProgramId = programId;
      }
      final RuntimeShader shader = (RuntimeShader) runtimeShader;
      int offset = 0;
      for (int i = 0; i < program.uniformNames.length; i++) {
        final int size = program.uniformSizes[i];
        if (offset + size > filter.uniformValues.length) {
          throw new IllegalArgumentException("Not enough uniform values");
        }
        shader.setFloatUniform(
            program.uniformNames[i],
            Arrays.copyOfRange(filter.uniformValues, offset, offset + size));
        offset += size;
      }
      setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, program.inputShaderName));
    } catch (IllegalArgumentException e) {
      // Thrown if the AGSL doesn't compile on this device (e.g. because of differences between the
      // device's and the engine's version of Skia) or if a uniform can't be set.
      if (failedRuntimeShaderPrograms.add(programId)) {
        Log.e(
            TAG,
            "ImageFilter.shader can't be applied to platform views; drawing them without it.",
            e);
      }
      runtimeShader = null;
      runtimeShaderProgramId = -1;
      if (appliedRuntimeShaderFilter != null) {
        clearRuntimeShaderFilter();
      }
      return false;
    }
    appliedRuntimeShaderFilter = filter;
    return true;
  }

  @RequiresApi(API_LEVELS.API_33)
  private void clearRuntimeShaderFilter() {
    setRenderEffect(null);
    appliedRuntimeShaderFilter = null;
  }

  @VisibleForTesting
  boolean hasRuntimeShaderFilter() {
    return appliedRuntimeShaderFilter != null;
  }

  @Override
  public void draw(Canvas canvas) {
    // Apply all clippings on the parent canvas.
    canvas.save();
    for (Path path : mutatorsStack.getFinalClippingPaths()) {
      // Reverse the current offset.
      //
      // The frame of this view includes the final offset of the bounding rect.
      // We need to apply all the mutators to the view, which includes the mutation that leads to
      // the final offset. We should reverse this final offset, both as a translate mutation and to
      // all the clipping paths
      //
      // Note that the RenderEffect of a runtime shader filter (if any) is applied to the output of
      // this view, i.e. after these clips. That matches Flutter's rendering only for clips that
      // are applied inside of the filter (or that are left unchanged by it).
      Path pathCopy = new Path(path);
      pathCopy.offset(-viewOriginLeft, -viewOriginTop);
      canvas.clipPath(pathCopy);
    }

    int newAlpha = (int) (255 * mutatorsStack.getFinalOpacity());
    boolean shouldApplyOpacity = paint.getAlpha() != newAlpha;
    if (shouldApplyOpacity) {
      paint.setAlpha((int) (255 * mutatorsStack.getFinalOpacity()));
      this.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
    }

    super.draw(canvas);
    canvas.restore();
  }

  @Override
  public void dispatchDraw(Canvas canvas) {
    // Apply all the transforms on the child canvas.
    canvas.save();

    canvas.concat(getPlatformViewMatrix());
    super.dispatchDraw(canvas);
    canvas.restore();
  }

  @VisibleForTesting
  Matrix getPlatformViewMatrix() {
    Matrix finalMatrix = new Matrix(mutatorsStack.getFinalMatrix());

    // Reverse scale based on screen scale.
    //
    // The Android frame is set based on the logical resolution instead of physical.
    // (https://developer.android.com/training/multiscreen/screendensities).
    // However, flow is based on the physical resolution. For example, 1000 pixels in flow equals
    // 500 points in Android. And until this point, we did all the calculation based on the flow
    // resolution. So we need to scale down to match Android's logical resolution.
    finalMatrix.preScale(1 / screenDensity, 1 / screenDensity);

    // Reverse the current offset.
    //
    // The frame of this view includes the final offset of the bounding rect.
    // We need to apply all the mutators to the view, which includes the mutation that leads to
    // the final offset. We should reverse this final offset, both as a translate mutation and to
    // all the clipping paths
    finalMatrix.postTranslate(-viewOriginLeft, -viewOriginTop);

    return finalMatrix;
  }

  /** Intercept the events here and do not propagate them to the child platform views. */
  @Override
  public boolean onInterceptTouchEvent(MotionEvent event) {
    return true;
  }

  @Override
  public boolean requestSendAccessibilityEvent(View child, AccessibilityEvent event) {
    final View embeddedView = getChildAt(0);
    if (embeddedView != null
        && embeddedView.getImportantForAccessibility()
            == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
      return false;
    }
    // Forward the request only if the embedded view is in the Flutter accessibility tree.
    // The embedded view may be ignored when the framework doesn't populate a SemanticNode
    // for the current platform view.
    // See AccessibilityBridge for more.
    return super.requestSendAccessibilityEvent(child, event);
  }

  /**
   * Informs this view that Flutter has won the gesture arena for the active touch sequence.
   *
   * <p>Subsequent {@link MotionEvent#ACTION_MOVE} events will request unbuffered dispatch to
   * minimize latency and prevent stutter for Flutter-driven gestures (e.g., scrolling).
   *
   * @param gestureId The identifier (downTime) of the gesture that Flutter won. Unbuffered dispatch
   *     is only enabled if this matches the currently active gesture's downTime.
   */
  public void onFlutterWonGesture(long gestureId) {
    gestureTracker.onFlutterWonGesture(gestureId);
  }

  @VisibleForTesting
  public boolean getFlutterWonGesture() {
    return gestureTracker.getFlutterWonGesture();
  }

  @VisibleForTesting
  boolean isGestureActive() {
    return gestureTracker.isGestureActive();
  }

  @VisibleForTesting
  long getCurrentDownTime() {
    return gestureTracker.getCurrentDownTime();
  }

  @VisibleForTesting
  void requestUnbuffered(MotionEvent event) {
    requestUnbufferedDispatch(event);
  }

  @Override
  @SuppressLint("ClickableViewAccessibility")
  public boolean onTouchEvent(MotionEvent event) {
    if (androidTouchProcessor == null) {
      return super.onTouchEvent(event);
    }
    gestureTracker.onTouchEvent(event, this::requestUnbuffered);
    final Matrix screenMatrix = new Matrix();
    screenMatrix.postTranslate(getLeft(), getTop());
    return androidTouchProcessor.onTouchEvent(event, screenMatrix);
  }
}
