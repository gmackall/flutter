// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.platform;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import io.flutter.Log;
import io.flutter.embedding.engine.mutatorsstack.FlutterMutatorView;
import io.flutter.embedding.engine.renderer.FlutterRenderer;
import io.flutter.util.ViewUtils;

/**
 * Unified platform view container that starts in texture-capture mode (TLHC, projecting its child
 * onto a {@link PlatformViewRenderTarget}) and can switch in-place without view detachment to
 * native view composition ({@link FlutterMutatorView} for HCPP or Hybrid Composition) if a
 * descendant {@link SurfaceView} is dynamically added.
 *
 * <p>Since the embedded view remains attached to this single wrapper in the Android view hierarchy
 * across mode transitions, window attachment, {@link android.view.SurfaceHolder} state, keyboard
 * focus, active IME {@code InputConnection}s, and accessibility interactions are preserved without
 * interruption.
 */
public class PlatformViewWrapper extends FlutterMutatorView {
  private static final String TAG = "PlatformViewWrapper";

  private static boolean hasSurfaceView(@Nullable View root) {
    return ViewUtils.traverseHierarchy(root, view -> view instanceof SurfaceView);
  }

  private static void invalidateSubtree(@NonNull View view, boolean requestSurfaceViewLayout) {
    view.invalidate();
    if (requestSurfaceViewLayout && view instanceof SurfaceView) {
      view.requestLayout();
    }
    if (view instanceof ViewGroup) {
      final ViewGroup group = (ViewGroup) view;
      final int count = group.getChildCount();
      for (int i = 0; i < count; i++) {
        invalidateSubtree(group.getChildAt(i), requestSurfaceViewLayout);
      }
    }
  }

  /** Listener invoked on the UI thread when a descendant {@link SurfaceView} is detected. */
  public interface OnSurfaceViewDetectedListener {
    void onSurfaceViewDetected();
  }

  @Nullable private PlatformViewRenderTarget renderTarget;
  private boolean captureToTextureMode = true;
  private boolean surfaceViewDetected = false;
  private boolean layoutScanPending = false;
  @Nullable private OnSurfaceViewDetectedListener onSurfaceViewDetectedListener;

  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  // The render target surface and canvas of a frame that has been recorded in draw(), but not yet
  // posted to the render target. See draw().
  @Nullable private Surface pendingFrameSurface;
  @Nullable private Canvas pendingFrameCanvas;
  private final Runnable postPendingFrameRunnable = () -> postPendingFrame(true);
  private final Runnable surfaceViewUpgradeRunnable =
      () -> {
        final OnSurfaceViewDetectedListener listener = onSurfaceViewDetectedListener;
        onSurfaceViewDetectedListener = null;
        if (captureToTextureMode && listener != null) {
          listener.onSurfaceViewDetected();
        }
      };
  private final Runnable layoutScanRunnable =
      () -> {
        layoutScanPending = false;
        if (captureToTextureMode
            && !surfaceViewDetected
            && onSurfaceViewDetectedListener != null
            && hasSurfaceView(this)) {
          markDescendantSurfaceViewPresent(null);
          scheduleSurfaceViewUpgrade();
        }
      };

  public PlatformViewWrapper(@NonNull Context context) {
    super(context);
    setWillNotDraw(false);
  }

  public PlatformViewWrapper(
      @NonNull Context context, @NonNull PlatformViewRenderTarget renderTarget) {
    this(context);
    this.renderTarget = renderTarget;

    Surface surface = renderTarget.getSurface();
    if (surface != null && !FlutterRenderer.debugDisableSurfaceClear) {
      final Canvas canvas = surface.lockHardwareCanvas();
      try {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
      } finally {
        surface.unlockCanvasAndPost(canvas);
      }
    }
  }

  @Override
  protected boolean shouldApplyMutators() {
    return !captureToTextureMode && super.shouldApplyMutators();
  }

  /** Returns true while this wrapper is capturing its child hierarchy into a texture (TLHC). */
  public boolean isCaptureToTextureMode() {
    return captureToTextureMode;
  }

  /** Returns true if the underlying {@link PlatformViewRenderTarget} has not yet been released. */
  public boolean hasActiveRenderTarget() {
    return renderTarget != null;
  }

  /**
   * Registers a one-shot listener notified on the UI looper when a descendant {@link SurfaceView}
   * is detected inside this wrapper while in texture-capture mode.
   */
  public void setOnSurfaceViewDetectedListener(@Nullable OnSurfaceViewDetectedListener listener) {
    this.onSurfaceViewDetectedListener = listener;
    if (captureToTextureMode && !surfaceViewDetected && listener != null && hasSurfaceView(this)) {
      markDescendantSurfaceViewPresent(null);
      scheduleSurfaceViewUpgrade();
    }
  }

  private void scheduleSurfaceViewUpgrade() {
    if (surfaceViewDetected || !captureToTextureMode) {
      return;
    }
    surfaceViewDetected = true;
    Log.w(
        TAG,
        "SurfaceView detected dynamically inside TLHC platform view; upgrading to native"
            + " composition. Implement PlatformView.requiresNativeComposition() = true to avoid"
            + " runtime transition.");
    mainHandler.removeCallbacks(layoutScanRunnable);
    layoutScanPending = false;
    mainHandler.post(surfaceViewUpgradeRunnable);
  }

  /**
   * Transitions this wrapper in-place from texture-capture mode (TLHC) to native view composition
   * ({@link FlutterMutatorView}) without detaching the embedded child view.
   *
   * <p>Note: {@link #release()} is intentionally NOT called here so the last rendered texture
   * buffer remains valid until the first native composition frame is committed.
   */
  public void switchToNativeViewComposition(float screenDensity) {
    if (!captureToTextureMode) {
      return;
    }
    postPendingFrame(false);
    captureToTextureMode = false;
    setScreenDensity(screenDensity);
    onSurfaceViewDetectedListener = null;
    mainHandler.removeCallbacks(surfaceViewUpgradeRunnable);
    mainHandler.removeCallbacks(layoutScanRunnable);
    layoutScanPending = false;
    // Recursively invalidate all descendants (and request layout on any SurfaceView) so the
    // Window's ThreadedRenderer re-records every child RenderNode (including SurfaceView's CLEAR
    // hole punch and PositionUpdateListener) even when wrapper bounds do not change.
    invalidateSubtree(this, /* requestSurfaceViewLayout= */ true);
    final ViewParent parent = getParent();
    if (parent instanceof View) {
      ((View) parent).invalidate();
    }
  }

  @Override
  public void requestTransparentRegion(@Nullable View child) {
    super.requestTransparentRegion(child);
    if (captureToTextureMode && !surfaceViewDetected && onSurfaceViewDetectedListener != null) {
      if (child instanceof SurfaceView || hasSurfaceView(this)) {
        markDescendantSurfaceViewPresent(child instanceof SurfaceView ? (SurfaceView) child : null);
        scheduleSurfaceViewUpgrade();
      }
    }
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    if (captureToTextureMode
        && !surfaceViewDetected
        && onSurfaceViewDetectedListener != null
        && !layoutScanPending) {
      layoutScanPending = true;
      mainHandler.post(layoutScanRunnable);
    }
  }

  /**
   * Moves this view to the given position, in the parent's coordinate space.
   *
   * <p>Only the position of the wrapper changes, which never affects the content rendered into the
   * {@link PlatformViewRenderTarget}. To avoid re-rendering the embedded view every frame while
   * scrolling, this doesn't request a layout pass (which would also force this view's display list
   * to be re-recorded). Instead, the view is translated directly, which only updates its {@code
   * RenderNode} properties.
   *
   * <p>The layout params are kept in sync so that any later layout pass keeps the view at the same
   * position.
   *
   * @param newLeft The new left position, in physical pixels.
   * @param newTop The new top position, in physical pixels.
   */
  public void setOffset(int newLeft, int newTop) {
    final ViewGroup.LayoutParams params = getLayoutParams();
    if (params instanceof FrameLayout.LayoutParams) {
      final FrameLayout.LayoutParams frameParams = (FrameLayout.LayoutParams) params;
      frameParams.leftMargin = newLeft;
      frameParams.topMargin = newTop;
      frameParams.gravity = Gravity.LEFT | Gravity.TOP;
    }
    // If the view hasn't been laid out yet, the initial layout pass will position the view using
    // the layout params.
    if (isLaidOut()) {
      final int dx = newLeft - getLeft();
      final int dy = newTop - getTop();
      if (dx != 0 || dy != 0) {
        offsetLeftAndRight(dx);
        offsetTopAndBottom(dy);
        // Notify ViewTreeObserver.OnScrollChangedListeners (e.g., PopupWindow, AdMob viewability)
        // that this view moved without a layout pass, matching RecyclerView.dispatchOnScrolled.
        onScrollChanged(getScrollX(), getScrollY(), getScrollX(), getScrollY());
      }
    }
  }

  public void resizeRenderTarget(int width, int height) {
    // Post any frame recorded at the old size before the render target switches surfaces.
    postPendingFrame(true);
    if (renderTarget != null) {
      renderTarget.resize(width, height);
    }
  }

  public int getRenderTargetWidth() {
    if (renderTarget != null) {
      return renderTarget.getWidth();
    }
    return 0;
  }

  public int getRenderTargetHeight() {
    if (renderTarget != null) {
      return renderTarget.getHeight();
    }
    return 0;
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    if (visibility != View.VISIBLE) {
      // Flush any pending frame so the render target surface does not remain locked while
      // backgrounded.
      postPendingFrame(true);
    }
  }

  /** Releases resources. */
  public void release() {
    onSurfaceViewDetectedListener = null;
    mainHandler.removeCallbacks(surfaceViewUpgradeRunnable);
    mainHandler.removeCallbacks(layoutScanRunnable);
    layoutScanPending = false;
    // Unlock any pending canvas without scheduling a redundant engine frame for a texture that is
    // about to be released.
    postPendingFrame(false);
    final boolean hadRenderTarget = renderTarget != null;
    if (renderTarget != null) {
      renderTarget.release();
      renderTarget = null;
    }
    if (hadRenderTarget && !captureToTextureMode && isAttachedToWindow()) {
      // Ensure the Window's ThreadedRenderer re-draws the subtree and re-asserts any SurfaceView
      // PositionUpdateListener if tearing down the offscreen render target invalidated HWUI state.
      invalidateSubtree(this, /* requestSurfaceViewLayout= */ false);
      final ViewParent parent = getParent();
      if (parent instanceof View) {
        ((View) parent).invalidate();
      }
    }
  }

  /** Used on Android O+, {@link invalidateChildInParent} used for previous versions. */
  @SuppressLint("NewApi")
  @Override
  public void onDescendantInvalidated(@NonNull View child, @NonNull View target) {
    super.onDescendantInvalidated(child, target);
    if (captureToTextureMode) {
      invalidate();
    }
  }

  @Override
  public ViewParent invalidateChildInParent(int[] location, Rect dirty) {
    if (captureToTextureMode) {
      invalidate();
    }
    return super.invalidateChildInParent(location, dirty);
  }

  @Override
  @SuppressLint("NewApi")
  public void draw(Canvas canvas) {
    if (!captureToTextureMode) {
      super.draw(canvas);
      return;
    }
    if (renderTarget == null) {
      super.draw(canvas);
      Log.e(TAG, "Platform view cannot be composed without a RenderTarget.");
      return;
    }

    if (surfaceViewDetected
        || (onSurfaceViewDetectedListener != null && hasDescendantSurfaceView())) {
      if (!surfaceViewDetected && onSurfaceViewDetectedListener != null) {
        scheduleSurfaceViewUpgrade();
      }
      // Once a descendant SurfaceView is present on an upgradable TLHC wrapper, freeze the last
      // pre-SV texture buffer rather than locking the offscreen Surface.HwuiContext canvas:
      // SurfaceView cannot render into an offscreen texture (it would only punch a CLEAR hole), and
      // recording SurfaceView.mRenderNode into Surface.HwuiContext clears child HWUI dirty flags
      // and binds its PositionUpdateListener to the offscreen HardwareRenderer, whose later
      // destruction in release() fires positionLost() -> tx.hide(mSurfaceControl).
      postPendingFrame(true);
      return;
    }

    // A frame recorded by a previous draw() in this same traversal must be posted before
    // querying or locking the render target's surface again.
    postPendingFrame(true);

    final Surface targetSurface = renderTarget.getSurface();
    if (targetSurface == null || !targetSurface.isValid()) {
      Log.e(TAG, "Platform view cannot be composed without a valid RenderTarget surface.");
      return;
    }

    final Canvas targetCanvas = targetSurface.lockHardwareCanvas();
    if (targetCanvas == null) {
      // Cannot render right now.
      invalidate();
      return;
    }

    try {
      // Fill the render target with transparent pixels. This is needed for platform views that
      // expect a transparent background.
      targetCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
      // Override the canvas that this subtree of views will use to draw.
      super.draw(targetCanvas);
    } finally {
      // Don't post the frame to the render target synchronously here.
      //
      // draw() runs while the window's ThreadedRenderer records its display list, i.e. before the
      // window syncs its own frame with the RenderThread in this same traversal. In observed
      // traces (e.g. scrolling a WebView with a heavy draw functor), calling unlockCanvasAndPost()
      // inline hands the render target's frame to the process-wide RenderThread ahead of the
      // window's sync, stalling the UI thread (also Flutter's UI thread) on that RenderThread
      // submission during traversal.
      //
      // Posting at the front of the main thread queue defers unlockCanvasAndPost() until
      // immediately after the current traversal completes (after the window's frame sync),
      // avoiding that inline UI-thread stall during traversal. Note that this orders submission
      // after the current traversal rather than providing a hard guarantee that the resulting
      // buffer reaches the same display presentation deadline.
      pendingFrameSurface = targetSurface;
      pendingFrameCanvas = targetCanvas;
      mainHandler.postAtFrontOfQueue(postPendingFrameRunnable);
    }
  }

  /** Posts the frame recorded by the last {@link #draw(Canvas)} call to the render target. */
  private void postPendingFrame(boolean scheduleFrame) {
    final Surface surface = pendingFrameSurface;
    final Canvas canvas = pendingFrameCanvas;
    if (surface == null || canvas == null) {
      return;
    }
    pendingFrameSurface = null;
    pendingFrameCanvas = null;
    mainHandler.removeCallbacks(postPendingFrameRunnable);
    try {
      surface.unlockCanvasAndPost(canvas);
    } catch (IllegalStateException | IllegalArgumentException e) {
      Log.e(TAG, "Failed to post platform view frame to the render target: " + e);
      return;
    }
    if (scheduleFrame && renderTarget != null) {
      renderTarget.scheduleFrame();
    }
  }

  @Nullable
  @Override
  @VisibleForTesting
  protected ViewTreeObserver.OnGlobalFocusChangeListener getActiveFocusListener() {
    return super.getActiveFocusListener();
  }

  @Override
  @VisibleForTesting
  public boolean getFlutterWonGesture() {
    return super.getFlutterWonGesture();
  }

  @Override
  @VisibleForTesting
  public boolean isGestureActive() {
    return super.isGestureActive();
  }

  @Override
  @VisibleForTesting
  public long getCurrentDownTime() {
    return super.getCurrentDownTime();
  }

  @VisibleForTesting
  void requestUnbuffered(MotionEvent event) {
    requestUnbufferedDispatch(event);
  }

  @Override
  protected void dispatchUnbufferedRequest(MotionEvent event) {
    requestUnbuffered(event);
  }
}
