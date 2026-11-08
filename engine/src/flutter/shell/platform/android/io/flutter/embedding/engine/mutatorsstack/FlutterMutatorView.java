// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine.mutatorsstack;

import static android.view.View.OnFocusChangeListener;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import io.flutter.embedding.android.AndroidTouchProcessor;
import io.flutter.plugin.platform.PlatformViewGestureTracker;
import io.flutter.util.ViewUtils;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A view that applies the {@link io.flutter.embedding.engine.mutatorsstack.FlutterMutatorsStack} to
 * its children.
 */
public class FlutterMutatorView extends FrameLayout {
  private FlutterMutatorsStack mutatorsStack;
  private float screenDensity;
  private int left;
  private int top;

  @Nullable private AndroidTouchProcessor androidTouchProcessor;
  private final PlatformViewGestureTracker gestureTracker = new PlatformViewGestureTracker();
  private Paint paint;
  private boolean descendantSurfaceViewDirty = true;
  private boolean cachedHasDescendantSurfaceView = false;
  private final Set<SurfaceView> trackedSurfaceViews =
      Collections.newSetFromMap(new WeakHashMap<>());
  private final View.OnAttachStateChangeListener surfaceViewDetachListener =
      new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(@NonNull View v) {}

        @Override
        public void onViewDetachedFromWindow(@NonNull View v) {
          v.removeOnAttachStateChangeListener(this);
          trackedSurfaceViews.remove(v);
          descendantSurfaceViewDirty = true;
        }
      };

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

  /** Sets the touch processor that allows intercepting gestures. */
  public void setTouchProcessor(@Nullable AndroidTouchProcessor newTouchProcessor) {
    this.androidTouchProcessor = newTouchProcessor;
  }

  /** Updates the screen density used to scale the mutator matrix. */
  public void setScreenDensity(float screenDensity) {
    this.screenDensity = screenDensity;
  }

  @Nullable @VisibleForTesting ViewTreeObserver.OnGlobalFocusChangeListener activeFocusListener;

  @Nullable
  @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
  protected ViewTreeObserver.OnGlobalFocusChangeListener getActiveFocusListener() {
    return activeFocusListener;
  }

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
    this.left = left;
    this.top = top;
    FrameLayout.LayoutParams layoutParams =
        new FrameLayout.LayoutParams(width, height, Gravity.LEFT | Gravity.TOP);
    layoutParams.leftMargin = left;
    layoutParams.topMargin = top;
    setLayoutParams(layoutParams);
    setWillNotDraw(false);
  }

  private void trackSurfaceViewDetach(@NonNull SurfaceView sv) {
    if (trackedSurfaceViews.add(sv)) {
      sv.addOnAttachStateChangeListener(surfaceViewDetachListener);
    }
  }

  /** Marks the cached descendant {@link SurfaceView} state as present without re-scanning. */
  protected void markDescendantSurfaceViewPresent(@Nullable SurfaceView sv) {
    cachedHasDescendantSurfaceView = true;
    if (sv != null) {
      trackSurfaceViewDetach(sv);
      descendantSurfaceViewDirty = !(isAttachedToWindow() && isLaidOut() && !isLayoutRequested());
    } else {
      descendantSurfaceViewDirty = true;
    }
  }

  @VisibleForTesting
  public boolean isDescendantSurfaceViewCacheDirty() {
    return descendantSurfaceViewDirty;
  }

  /**
   * Returns true if this container currently holds at least one descendant {@link SurfaceView}.
   *
   * <p>Once attached to a window and laid out with no pending layout request, the result is cached
   * across draws and invalidated only on view hierarchy/layout/transparent-region changes or
   * tracked {@link SurfaceView} detachment—never on ordinary child invalidations (e.g. {@code
   * WebView} scrolling/redraws).
   */
  public boolean hasDescendantSurfaceView() {
    final boolean canUseCache = isAttachedToWindow() && isLaidOut() && !isLayoutRequested();
    if (descendantSurfaceViewDirty || !canUseCache) {
      final boolean[] found = new boolean[] {false};
      ViewUtils.traverseHierarchy(
          this,
          view -> {
            if (view instanceof SurfaceView) {
              found[0] = true;
              trackSurfaceViewDetach((SurfaceView) view);
            }
            return false;
          });
      cachedHasDescendantSurfaceView = found[0];
      descendantSurfaceViewDirty = !canUseCache;
    }
    return cachedHasDescendantSurfaceView;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    descendantSurfaceViewDirty = true;
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    if (!cachedHasDescendantSurfaceView) {
      descendantSurfaceViewDirty = true;
    }
  }

  @Override
  public void onViewAdded(View child) {
    super.onViewAdded(child);
    descendantSurfaceViewDirty = true;
  }

  @Override
  public void onViewRemoved(View child) {
    super.onViewRemoved(child);
    descendantSurfaceViewDirty = true;
  }

  @Override
  public void requestTransparentRegion(@Nullable View child) {
    super.requestTransparentRegion(child);
    if (child instanceof SurfaceView) {
      markDescendantSurfaceViewPresent((SurfaceView) child);
    } else {
      descendantSurfaceViewDirty = true;
    }
  }

  protected boolean shouldApplyMutators() {
    return mutatorsStack != null;
  }

  @Override
  public void draw(Canvas canvas) {
    if (!shouldApplyMutators()) {
      super.draw(canvas);
      return;
    }
    // Apply all clippings on the parent canvas.
    canvas.save();
    for (Path path : mutatorsStack.getFinalClippingPaths()) {
      // Reverse the current offset.
      //
      // The frame of this view includes the final offset of the bounding rect.
      // We need to apply all the mutators to the view, which includes the mutation that leads to
      // the final offset. We should reverse this final offset, both as a translate mutation and to
      // all the clipping paths
      Path pathCopy = new Path(path);
      pathCopy.offset(-left, -top);
      canvas.clipPath(pathCopy);
    }

    // Avoid an offscreen hardware layer when a descendant SurfaceView is present: a
    // LAYER_TYPE_HARDWARE FBO intercepts SurfaceView's PorterDuff.Mode.CLEAR hole punch instead of
    // clearing through the root window buffer (e.g. over FlutterImageView in Legacy HC). Note that
    // SurfaceView opacity is applied via SurfaceControl.Transaction.setAlpha, whereas
    // non-SurfaceView
    // sibling views sharing the same platform view container render at full alpha without an FBO.
    if (hasDescendantSurfaceView()) {
      if (getLayerType() != View.LAYER_TYPE_NONE) {
        paint.setAlpha(255);
        this.setLayerType(View.LAYER_TYPE_NONE, null);
      }
    } else {
      int newAlpha = (int) (255 * mutatorsStack.getFinalOpacity());
      boolean shouldApplyOpacity = paint.getAlpha() != newAlpha;
      if (shouldApplyOpacity) {
        paint.setAlpha(newAlpha);
        this.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
      }
    }

    super.draw(canvas);
    canvas.restore();
  }

  @Override
  public void dispatchDraw(Canvas canvas) {
    if (!shouldApplyMutators()) {
      super.dispatchDraw(canvas);
      return;
    }
    // Apply all the transforms on the child canvas.
    canvas.save();

    canvas.concat(getPlatformViewMatrix());
    super.dispatchDraw(canvas);
    canvas.restore();
  }

  private Matrix getPlatformViewMatrix() {
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
    finalMatrix.postTranslate(-left, -top);

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
  public boolean isGestureActive() {
    return gestureTracker.isGestureActive();
  }

  @VisibleForTesting
  public long getCurrentDownTime() {
    return gestureTracker.getCurrentDownTime();
  }

  @VisibleForTesting
  void requestUnbuffered(MotionEvent event) {
    requestUnbufferedDispatch(event);
  }

  protected void dispatchUnbufferedRequest(MotionEvent event) {
    requestUnbuffered(event);
  }

  @Override
  @SuppressLint("ClickableViewAccessibility")
  public boolean onTouchEvent(MotionEvent event) {
    if (androidTouchProcessor == null) {
      return super.onTouchEvent(event);
    }
    gestureTracker.onTouchEvent(event, this::dispatchUnbufferedRequest);
    final Matrix screenMatrix = new Matrix();
    screenMatrix.postTranslate(getLeft(), getTop());
    return androidTouchProcessor.onTouchEvent(event, screenMatrix);
  }
}
