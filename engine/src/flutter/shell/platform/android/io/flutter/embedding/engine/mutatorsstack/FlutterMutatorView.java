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
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import io.flutter.embedding.android.AndroidTouchProcessor;
import io.flutter.plugin.platform.PlatformViewGestureTracker;
import io.flutter.util.ViewUtils;

/**
 * A view that applies the {@link io.flutter.embedding.engine.mutatorsstack.FlutterMutatorsStack} to
 * its children.
 */
public class FlutterMutatorView extends FrameLayout {
  private FlutterMutatorsStack mutatorsStack;
  private float screenDensity;
  private int left;
  private int top;

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

  /** Observes changes to the content of the platform view in this view. */
  public interface ContentListener {
    /** A descendant (the platform view or any view inside it) was invalidated. */
    void onContentInvalidated();

    /** This view, and so the platform view, was just drawn. */
    void onContentDrawn();
  }

  @Nullable private ContentListener contentListener;
  private boolean drawToWindow = true;

  /**
   * Sets whether this view draws the platform view to the window.
   *
   * <p>When false, the engine draws a capture of the platform view in its place. This view stays
   * laid out and visible, so touch, focus and accessibility work as usual, and {@link
   * ContentListener} is still notified so the capture can be kept up to date.
   */
  public void setDrawToWindow(boolean drawToWindow) {
    if (this.drawToWindow == drawToWindow) {
      return;
    }
    this.drawToWindow = drawToWindow;
    if (!drawToWindow && paint.getAlpha() != 255) {
      paint.setAlpha(255);
      setLayerType(View.LAYER_TYPE_NONE, null);
    }
    invalidate();
  }

  @VisibleForTesting
  public boolean getDrawToWindow() {
    return drawToWindow;
  }

  /**
   * Sets a listener that is notified when the content of the platform view may have changed, and
   * again once it has been drawn.
   *
   * <p>While a listener is set, this view redraws whenever a descendant is invalidated, so that
   * {@link ContentListener#onContentDrawn()} is called in the same frame as the platform view is
   * drawn. Used to keep offscreen captures of the platform view up to date.
   */
  public void setContentListener(@Nullable ContentListener listener) {
    contentListener = listener;
  }

  @Override
  public void onDescendantInvalidated(@NonNull View child, @NonNull View target) {
    super.onDescendantInvalidated(child, target);
    if (contentListener != null) {
      contentListener.onContentInvalidated();
      invalidate();
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
    final ViewGroup.LayoutParams currentParams = getLayoutParams();
    if (!(currentParams instanceof FrameLayout.LayoutParams)
        || currentParams.width != width
        || currentParams.height != height) {
      FrameLayout.LayoutParams layoutParams =
          new FrameLayout.LayoutParams(width, height, Gravity.LEFT | Gravity.TOP);
      layoutParams.leftMargin = left;
      layoutParams.topMargin = top;
      setLayoutParams(layoutParams);
    } else {
      final FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) currentParams;
      layoutParams.leftMargin = left;
      layoutParams.topMargin = top;
      layoutParams.gravity = Gravity.LEFT | Gravity.TOP;
      // If the view hasn't been laid out yet, the initial layout pass will position the view
      // using the layout params.
      if (isLaidOut()) {
        final int dx = left - getLeft();
        final int dy = top - getTop();
        if (dx != 0 || dy != 0) {
          offsetLeftAndRight(dx);
          offsetTopAndBottom(dy);
          // Notify ViewTreeObserver.OnScrollChangedListeners (e.g., PopupWindow, AdMob
          // viewability) that this view moved without a layout pass, matching
          // RecyclerView.dispatchOnScrolled.
          onScrollChanged(getScrollX(), getScrollY(), getScrollX(), getScrollY());
        }
      }
      if (drawToWindow) {
        invalidate();
      }
    }
    setWillNotDraw(false);
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    if (contentListener != null) {
      contentListener.onContentDrawn();
    }
  }

  @Override
  public void draw(Canvas canvas) {
    if (!drawToWindow) {
      if (contentListener != null) {
        contentListener.onContentDrawn();
      }
      return;
    }
    if (mutatorsStack == null) {
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

    int newAlpha = (int) (255 * mutatorsStack.getFinalOpacity());
    boolean shouldApplyOpacity = paint.getAlpha() != newAlpha;
    if (shouldApplyOpacity) {
      paint.setAlpha(newAlpha);
      if (newAlpha < 255) {
        this.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
      } else {
        this.setLayerType(View.LAYER_TYPE_NONE, null);
      }
    }

    super.draw(canvas);
    canvas.restore();

    if (contentListener != null) {
      contentListener.onContentDrawn();
    }
  }

  @Override
  public void dispatchDraw(Canvas canvas) {
    if (!drawToWindow || mutatorsStack == null) {
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
