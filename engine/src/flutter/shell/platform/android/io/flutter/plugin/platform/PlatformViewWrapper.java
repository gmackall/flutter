// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.platform;

import static io.flutter.Build.API_LEVELS;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.HardwareRenderer;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import io.flutter.Log;
import io.flutter.embedding.android.AndroidTouchProcessor;
import io.flutter.embedding.engine.renderer.FlutterRenderer;
import io.flutter.util.ViewUtils;

/**
 * Wraps a platform view to intercept gestures and project this view onto a {@link
 * PlatformViewRenderTarget}.
 *
 * <p>An Android platform view is composed by the engine using a {@code TextureLayer}. The view is
 * embeded to the Android view hierarchy like a normal view, but it's projected onto a {@link
 * PlatformViewRenderTarget}, so it can be efficiently composed by the engine.
 *
 * <p>Since the view is in the Android view hierarchy, keyboard and accessibility interactions
 * behave normally.
 */
public class PlatformViewWrapper extends FrameLayout {
  private static final String TAG = "PlatformViewWrapper";

  private AndroidTouchProcessor touchProcessor;
  private PlatformViewRenderTarget renderTarget;

  private final PlatformViewGestureTracker gestureTracker = new PlatformViewGestureTracker();

  private ViewTreeObserver.OnGlobalFocusChangeListener activeFocusListener;

  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  // The render target surface and canvas of a frame that has been recorded in draw(), but not yet
  // posted to the render target. See draw().
  @Nullable private Surface pendingFrameSurface;
  @Nullable private Canvas pendingFrameCanvas;
  private final Runnable postPendingFrameRunnable = () -> postPendingFrame(true);

  /**
   * Whether this view's subtree is rendered into the render target with a dedicated {@link
   * HardwareRenderer} rather than with {@link Surface#lockHardwareCanvas()}.
   *
   * <p>{@link Surface#lockHardwareCanvas()} renders through {@code Surface.HwuiContext}, which
   * submits every frame with {@code System.nanoTime()} as its vsync time. HWUI derives the work
   * duration that it reports to the Android Dynamic Performance Framework (ADPF) hint session of
   * the UI thread and RenderThread from that frame start time, so none of the UI thread work of the
   * frame (input, animation, recording the platform view) is reported, and the session gets starved
   * of its CPU boost (UI thread and RenderThread end up on little cores). A dedicated renderer lets
   * us submit frames with the Choreographer frame time instead.
   */
  @VisibleForTesting
  static boolean useDedicatedHardwareRenderer = Build.VERSION.SDK_INT >= API_LEVELS.API_29;

  // Present when useDedicatedHardwareRenderer is true. See drawWithHardwareRenderer().
  @Nullable private HardwareRenderer hardwareRenderer;
  @Nullable private RenderNode hardwareRendererRoot;
  @Nullable private Surface hardwareRendererSurface;
  // Whether hardwareRendererRoot holds a recorded frame that hasn't been submitted yet.
  private boolean hasPendingHardwareRendererFrame;
  private long pendingFrameVsyncNanos;

  // Frame times older than this are considered stale (e.g. draw() outside of a Choreographer
  // traversal), and the current time is used instead.
  private static final long MAX_FRAME_TIME_AGE_NANOS = 100_000_000L;

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
      if (useDedicatedHardwareRenderer && Build.VERSION.SDK_INT >= API_LEVELS.API_29) {
        if (!surface.isValid()) {
          Log.e(TAG, "Platform view cannot be cleared without a valid RenderTarget surface.");
          return;
        }
        // A surface can only have one producer connected, so the dedicated renderer must also be
        // used for the initial clear. Passing System.nanoTime() advances HWUI's process-wide
        // TimeLord; if the first traversal draw runs within ~2ms of construction, HWUI may report
        // SYNC_FRAME_DROPPED ("AlreadyDrawn") once and self-heal on the next vsync via its own
        // RenderThread retry callback.
        final RecordingCanvas canvas = beginHardwareRendererFrame(surface);
        try {
          canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        } finally {
          hardwareRendererRoot.endRecording();
        }
        syncAndDrawHardwareRendererFrame(hardwareRenderer, System.nanoTime());
        return;
      }
      final Canvas canvas = surface.lockHardwareCanvas();
      try {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
      } finally {
        surface.unlockCanvasAndPost(canvas);
      }
    }
  }

  /**
   * Sets the touch processor that allows to intercept gestures.
   *
   * @param newTouchProcessor The touch processor.
   */
  public void setTouchProcessor(@Nullable AndroidTouchProcessor newTouchProcessor) {
    touchProcessor = newTouchProcessor;
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
      // Flush any pending frame before detaching the surface.
      postPendingFrame(true);
      // When backgrounded on API 29-34, ImageReaderSurfaceProducer.cleanup() closes its
      // ImageReaders on TRIM_MEMORY_BACKGROUND. Detach the surface from the dedicated
      // HardwareRenderer so it releases its native ANativeWindow reference and GraphicBuffer
      // slot cache; the next draw() will re-bind via setSurface().
      if (hardwareRenderer != null && Build.VERSION.SDK_INT >= API_LEVELS.API_29) {
        setHardwareRendererSurface(hardwareRenderer, null);
        hardwareRendererSurface = null;
      }
    }
  }

  /** Releases resources. */
  public void release() {
    // Unlock any pending canvas without scheduling a redundant engine frame for a texture that is
    // about to be released.
    postPendingFrame(false);
    if (hardwareRenderer != null && Build.VERSION.SDK_INT >= API_LEVELS.API_29) {
      hardwareRenderer.destroy();
      hardwareRenderer = null;
      hardwareRendererRoot = null;
      hardwareRendererSurface = null;
    }
    if (renderTarget != null) {
      renderTarget.release();
      renderTarget = null;
    }
  }

  @Override
  public boolean onInterceptTouchEvent(@NonNull MotionEvent event) {
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

  /** Used on Android O+, {@link invalidateChildInParent} used for previous versions. */
  @SuppressLint("NewApi")
  @Override
  public void onDescendantInvalidated(@NonNull View child, @NonNull View target) {
    super.onDescendantInvalidated(child, target);
    invalidate();
  }

  @Override
  public ViewParent invalidateChildInParent(int[] location, Rect dirty) {
    invalidate();
    return super.invalidateChildInParent(location, dirty);
  }

  @Override
  @SuppressLint("NewApi")
  public void draw(Canvas canvas) {
    if (renderTarget == null) {
      super.draw(canvas);
      Log.e(TAG, "Platform view cannot be composed without a RenderTarget.");
      return;
    }

    // A frame recorded by a previous draw() in this same traversal must be posted before
    // querying or locking the render target's surface again.
    postPendingFrame(true);

    final Surface targetSurface = renderTarget.getSurface();
    if (!targetSurface.isValid()) {
      Log.e(TAG, "Platform view cannot be composed without a valid RenderTarget surface.");
      return;
    }

    if (useDedicatedHardwareRenderer && Build.VERSION.SDK_INT >= API_LEVELS.API_29) {
      drawWithHardwareRenderer(targetSurface);
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
      // window syncs its own frame with the RenderThread in this same traversal.
      // unlockCanvasAndPost() hands the render target's frame to the process-wide RenderThread,
      // which then spends several milliseconds rendering the platform view (e.g. a WebView's draw
      // functor). The window's sync, which the UI thread blocks on, is queued behind that work, so
      // the UI thread (also Flutter's UI thread) stalls for the platform view's entire GPU
      // submission every frame. Scrolling inside a WebView then misses every other vsync.
      //
      // Posting at the front of the queue runs the unlock immediately after the current
      // Choreographer frame, once the window's frame has been synced. The platform view still
      // renders in the same vsync, but the RenderThread work no longer blocks the UI thread.
      pendingFrameSurface = targetSurface;
      pendingFrameCanvas = targetCanvas;
      mainHandler.postAtFrontOfQueue(postPendingFrameRunnable);
    }
  }

  /**
   * Records this view's subtree into {@link #hardwareRendererRoot} and schedules its submission to
   * the render target, like the {@link Surface#lockHardwareCanvas()} path in {@link #draw(Canvas)}.
   */
  @RequiresApi(API_LEVELS.API_29)
  private void drawWithHardwareRenderer(@NonNull Surface targetSurface) {
    final RecordingCanvas canvas = beginHardwareRendererFrame(targetSurface);
    try {
      // Fill the render target with transparent pixels. This is needed for platform views that
      // expect a transparent background.
      canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
      super.draw(canvas);
    } finally {
      hardwareRendererRoot.endRecording();
      // See draw() for why the frame is submitted after the current Choreographer frame.
      pendingFrameVsyncNanos = getFrameTimeNanos();
      hasPendingHardwareRendererFrame = true;
      mainHandler.postAtFrontOfQueue(postPendingFrameRunnable);
    }
  }

  @RequiresApi(API_LEVELS.API_29)
  @NonNull
  private RecordingCanvas beginHardwareRendererFrame(@NonNull Surface targetSurface) {
    if (hardwareRenderer == null) {
      hardwareRendererRoot = new RenderNode("PlatformViewWrapper");
      hardwareRendererRoot.setClipToBounds(false);
      hardwareRendererRoot.setForceDarkAllowed(false);
      hardwareRenderer = new HardwareRenderer();
      hardwareRenderer.setContentRoot(hardwareRendererRoot);
      hardwareRenderer.setOpaque(false);
      // Match Surface.HwuiContext, which disables elevation shadows.
      hardwareRenderer.setLightSourceAlpha(0.0f, 0.0f);
      hardwareRenderer.setLightSourceGeometry(0.0f, 0.0f, 0.0f, 0.0f);
    }
    if (hardwareRendererSurface != targetSurface) {
      setHardwareRendererSurface(hardwareRenderer, targetSurface);
      hardwareRendererSurface = targetSurface;
    }
    final int width = renderTarget.getWidth();
    final int height = renderTarget.getHeight();
    // Keep the root RenderNode's bounds equal to the buffer dimensions; re-recording the root's
    // display list below then damages the full bounds every frame (important because public
    // HardwareRenderer.setSurface() uses buffer-preserving swap behavior with partial damage).
    hardwareRendererRoot.setPosition(0, 0, width, height);
    return hardwareRendererRoot.beginRecording(width, height);
  }

  /**
   * Returns the start time of the current frame in the {@link System#nanoTime()} time base.
   *
   * <p>During a traversal, {@link #getDrawingTime()} is the Choreographer frame time (in ms).
   * Reporting it as the vsync time makes HWUI account for the UI thread work of this frame in the
   * ADPF work duration of the platform view's frames.
   */
  @VisibleForTesting
  long getFrameTimeNanos() {
    return getFrameTimeNanos(System.nanoTime());
  }

  @VisibleForTesting
  long getFrameTimeNanos(long nowNanos) {
    final long frameTimeNanos = getDrawingTime() * 1_000_000L;
    if (frameTimeNanos <= 0
        || frameTimeNanos > nowNanos
        || nowNanos - frameTimeNanos > MAX_FRAME_TIME_AGE_NANOS) {
      return nowNanos;
    }
    return frameTimeNanos;
  }

  @RequiresApi(API_LEVELS.API_29)
  @VisibleForTesting
  void setHardwareRendererSurface(
      @NonNull HardwareRenderer renderer, @Nullable Surface surface) {
    renderer.setSurface(surface);
  }

  @RequiresApi(API_LEVELS.API_29)
  @VisibleForTesting
  int syncAndDrawHardwareRendererFrame(@NonNull HardwareRenderer renderer, long vsyncNanos) {
    return renderer.createRenderRequest().setVsyncTime(vsyncNanos).syncAndDraw();
  }

  /** Posts the frame recorded by the last {@link #draw(Canvas)} call to the render target. */
  private void postPendingFrame(boolean scheduleFrame) {
    if (hasPendingHardwareRendererFrame) {
      hasPendingHardwareRendererFrame = false;
      mainHandler.removeCallbacks(postPendingFrameRunnable);
      // Unlike lockHardwareCanvas(), the display list recording is already closed in
      // drawWithHardwareRenderer(), so release() (scheduleFrame == false) only needs to cancel the
      // pending callback before destroying the renderer.
      if (hardwareRenderer == null
          || !scheduleFrame
          || Build.VERSION.SDK_INT < API_LEVELS.API_29) {
        return;
      }
      final int syncResult =
          syncAndDrawHardwareRendererFrame(hardwareRenderer, pendingFrameVsyncNanos);
      if ((syncResult & HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND) != 0) {
        // If HWUI lost or dropped the surface (e.g. after a dequeue timeout), clear the cached
        // reference and request a redraw so the next draw() re-attaches via setSurface().
        hardwareRendererSurface = null;
        invalidate();
      }
      // When SYNC_FRAME_DROPPED is set (e.g. "AlreadyDrawn" when vsyncNanos is within ~2ms of
      // HWUI TimeLord's latest vsync), HWUI posts its own RenderThread frame callback and draws
      // the synced RenderNode tree at the next vsync. ImageReaderSurfaceProducer's onImage
      // listener then schedules an engine frame when that buffer arrives, so skipping
      // scheduleFrame() here is safe and recovers the content on the next vsync.
      if ((syncResult & HardwareRenderer.SYNC_FRAME_DROPPED) == 0 && renderTarget != null) {
        renderTarget.scheduleFrame();
      }
      return;
    }
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
  boolean getFlutterWonGesture() {
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
  public boolean onTouchEvent(@NonNull MotionEvent event) {
    if (touchProcessor == null) {
      return super.onTouchEvent(event);
    }
    gestureTracker.onTouchEvent(event, this::requestUnbuffered);
    // The parent transformed this event into this view's coordinate space using the view's current
    // position, so the same position maps it back into the parent's (the FlutterView's) space.
    final Matrix screenMatrix = new Matrix();
    screenMatrix.postTranslate(getLeft(), getTop());
    return touchProcessor.onTouchEvent(event, screenMatrix);
  }

  @VisibleForTesting
  public ViewTreeObserver.OnGlobalFocusChangeListener getActiveFocusListener() {
    return this.activeFocusListener;
  }

  public void setOnDescendantFocusChangeListener(@NonNull OnFocusChangeListener userFocusListener) {
    unsetOnDescendantFocusChangeListener();
    final ViewTreeObserver observer = getViewTreeObserver();
    if (observer.isAlive() && activeFocusListener == null) {
      activeFocusListener =
          new ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override
            public void onGlobalFocusChanged(View oldFocus, View newFocus) {
              userFocusListener.onFocusChange(
                  PlatformViewWrapper.this, ViewUtils.childHasFocus(PlatformViewWrapper.this));
            }
          };
      observer.addOnGlobalFocusChangeListener(activeFocusListener);
    }
  }

  public void unsetOnDescendantFocusChangeListener() {
    final ViewTreeObserver observer = getViewTreeObserver();
    if (observer.isAlive() && activeFocusListener != null) {
      final ViewTreeObserver.OnGlobalFocusChangeListener currFocusListener = activeFocusListener;
      activeFocusListener = null;
      observer.removeOnGlobalFocusChangeListener(currFocusListener);
    }
  }
}
