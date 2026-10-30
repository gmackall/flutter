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
import android.os.PerformanceHintManager;
import android.os.Process;
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
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.embedding.engine.renderer.FlutterRenderer;
import io.flutter.util.ViewUtils;
import java.util.concurrent.Executor;

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
  private final Runnable postPendingFrameRunnable = this::postPendingFrame;

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
  @VisibleForTesting static boolean useDedicatedHardwareRenderer = Build.VERSION.SDK_INT >= 29;

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
  private static final Executor DIRECT_EXECUTOR = Runnable::run;

  @VisibleForTesting
  interface AdpfSessionHandle {
    void updateTargetWorkDuration(long targetDurationNanos);

    boolean setThreads(@NonNull int[] tids);

    void reportActualWorkDuration(long actualDurationNanos);

    void close();
  }

  @VisibleForTesting
  interface AdpfHintBridge {
    @Nullable
    AdpfSessionHandle createSession(
        @NonNull Context context, @NonNull int[] tids, long initialTargetWorkDurationNanos);

    float getRefreshRateFPS();

    int getSharedImageReaderTid();

    int getLastRasterThreadTid();

    boolean requestNotEfficiencyCpuAffinity();
  }

  private static final AdpfHintBridge DEFAULT_ADPF_BRIDGE =
      new AdpfHintBridge() {
        @Override
        @Nullable
        @RequiresApi(API_LEVELS.API_31)
        public AdpfSessionHandle createSession(
            @NonNull Context context, @NonNull int[] tids, long initialTargetWorkDurationNanos) {
          if (Build.VERSION.SDK_INT < API_LEVELS.API_31) {
            return null;
          }
          try {
            final PerformanceHintManager manager =
                context.getSystemService(PerformanceHintManager.class);
            if (manager == null) {
              return null;
            }
            final PerformanceHintManager.Session rawSession =
                manager.createHintSession(tids, initialTargetWorkDurationNanos);
            if (rawSession == null) {
              return null;
            }
            return new AdpfSessionHandle() {
              @Override
              public void updateTargetWorkDuration(long targetDurationNanos) {
                try {
                  rawSession.updateTargetWorkDuration(targetDurationNanos);
                } catch (Throwable ignored) {
                }
              }

              @Override
              public boolean setThreads(@NonNull int[] updatedTids) {
                if (Build.VERSION.SDK_INT < API_LEVELS.API_34) {
                  return false;
                }
                try {
                  rawSession.setThreads(updatedTids);
                  return true;
                } catch (Throwable ignored) {
                  return false;
                }
              }

              @Override
              public void reportActualWorkDuration(long actualDurationNanos) {
                try {
                  rawSession.reportActualWorkDuration(actualDurationNanos);
                } catch (Throwable ignored) {
                }
              }

              @Override
              public void close() {
                try {
                  rawSession.close();
                } catch (Throwable ignored) {
                }
              }
            };
          } catch (Throwable ignored) {
            return null;
          }
        }

        @Override
        public float getRefreshRateFPS() {
          return FlutterJNI.getRefreshRateFPS();
        }

        @Override
        public int getSharedImageReaderTid() {
          return FlutterRenderer.getSharedImageReaderTid();
        }

        @Override
        public int getLastRasterThreadTid() {
          return FlutterJNI.getLastRasterThreadTid();
        }

        @Override
        public boolean requestNotEfficiencyCpuAffinity() {
          return FlutterJNI.requestNotEfficiencyCpuAffinity();
        }
      };

  /**
   * Coordinates display-refresh-aware ADPF hint sessions and non-efficiency CPU affinity across the
   * 4 threads in the TLHC rendering relay ({@code main}, {@code RenderThread}, {@code
   * FlutterImageReader}, and {@code 1.raster}).
   *
   * <p>Standalone {@link HardwareRenderer} instances have no {@code ViewRootImpl} display wiring,
   * so HWUI's internal ADPF session defaults its frame interval to 60 Hz (~16.67 ms, target ~11.6
   * ms) even on 90/120 Hz displays and only covers {@code main} + {@code RenderThread}. On 120 Hz
   * displays (8.33 ms period), reporting 5-7.5 ms Stage-1 ({@code main + RenderThread}) durations
   * against an 11.6 ms target causes PowerHAL to throttle {@code UCLAMP_MIN} down while leaving
   * {@code FlutterImageReader} and {@code 1.raster} completely unboosted.
   */
  static final class PipelineAdpfController {
    // Stage 1 (UI traversal + RenderThread commit into ImageReader) targets 60% of the display
    // vsync period (5.0 ms at 120 Hz), leaving 40% (3.33 ms at 120 Hz) for Stage 2
    // (FlutterImageReader acquire + Impeller rasterization) within the same vsync deadline.
    private static final double STAGE1_TARGET_BUDGET_FRACTION = 0.60;
    private static final long MAX_REPORTED_DURATION_NANOS = 50_000_000L;
    private static final long SAME_VSYNC_UPDATE_DELTA_NANOS = 1_000_000L;

    private final Object lock = new Object();
    @NonNull private AdpfHintBridge bridge = DEFAULT_ADPF_BRIDGE;
    @Nullable private AdpfSessionHandle session;
    private int activeRendererCount = 0;

    private int mainTid = 0;
    private volatile int renderThreadTid = 0;
    private volatile boolean renderThreadAffinityApplied = false;

    private int sessionMainTid = 0;
    private int sessionRenderThreadTid = 0;
    private int sessionReaderTid = 0;
    private int sessionRasterTid = 0;
    private long targetWorkDurationNanos = 0L;

    private long lastReportedVsyncNanos = 0L;
    private long lastReportedDurationNanos = 0L;

    static long computeTargetWorkDurationNanos(float refreshRateFPS) {
      final float clampedFps =
          (Float.isNaN(refreshRateFPS) || refreshRateFPS <= 0.0f)
              ? 60.0f
              : Math.max(30.0f, Math.min(240.0f, refreshRateFPS));
      return (long) ((1_000_000_000.0 / clampedFps) * STAGE1_TARGET_BUDGET_FRACTION);
    }

    @NonNull
    static int[] buildDeduplicatedTids(int t0, int t1, int t2, int t3) {
      int count = 0;
      final int[] temp = new int[4];
      final int[] candidates = {t0, t1, t2, t3};
      for (int c : candidates) {
        if (c <= 0) {
          continue;
        }
        boolean dup = false;
        for (int i = 0; i < count; i++) {
          if (temp[i] == c) {
            dup = true;
            break;
          }
        }
        if (!dup) {
          temp[count++] = c;
        }
      }
      final int[] out = new int[count];
      System.arraycopy(temp, 0, out, 0, count);
      return out;
    }

    void setBridgeForTest(@NonNull AdpfHintBridge testBridge) {
      synchronized (lock) {
        if (session != null) {
          session.close();
          session = null;
        }
        bridge = testBridge;
        mainTid = 0;
        renderThreadTid = 0;
        renderThreadAffinityApplied = false;
        sessionMainTid = 0;
        sessionRenderThreadTid = 0;
        sessionReaderTid = 0;
        sessionRasterTid = 0;
        targetWorkDurationNanos = 0L;
        lastReportedVsyncNanos = 0L;
        lastReportedDurationNanos = 0L;
      }
    }

    void onRendererCreated() {
      synchronized (lock) {
        activeRendererCount++;
      }
    }

    void onRendererDestroyed() {
      AdpfSessionHandle toClose = null;
      synchronized (lock) {
        if (activeRendererCount > 0) {
          activeRendererCount--;
        }
        if (activeRendererCount == 0 && session != null) {
          toClose = session;
          session = null;
          sessionMainTid = 0;
          sessionRenderThreadTid = 0;
          sessionReaderTid = 0;
          sessionRasterTid = 0;
          targetWorkDurationNanos = 0L;
        }
      }
      if (toClose != null) {
        toClose.close();
      }
    }

    boolean needsFrameCommitCallback(int sdkInt) {
      return sdkInt >= API_LEVELS.API_31 || !renderThreadAffinityApplied;
    }

    void prepareForFrameOnMainThread(@NonNull Context context, int sdkInt) {
      if (sdkInt < API_LEVELS.API_31) {
        return;
      }
      synchronized (lock) {
        if (mainTid <= 0 && Looper.myLooper() == Looper.getMainLooper()) {
          mainTid = Process.myTid();
        }
        final int curMain = mainTid;
        final int curRender = renderThreadTid;
        final int curReader = bridge.getSharedImageReaderTid();
        final int curRaster = bridge.getLastRasterThreadTid();
        final long desiredTarget = computeTargetWorkDurationNanos(bridge.getRefreshRateFPS());

        final boolean threadsChanged =
            curMain != sessionMainTid
                || curRender != sessionRenderThreadTid
                || curReader != sessionReaderTid
                || curRaster != sessionRasterTid;

        if (session == null) {
          final int[] tids = buildDeduplicatedTids(curMain, curRender, curReader, curRaster);
          if (tids.length == 0) {
            return;
          }
          session = bridge.createSession(context, tids, desiredTarget);
          if (session != null) {
            sessionMainTid = curMain;
            sessionRenderThreadTid = curRender;
            sessionReaderTid = curReader;
            sessionRasterTid = curRaster;
            targetWorkDurationNanos = desiredTarget;
          }
          return;
        }

        if (threadsChanged) {
          final int[] tids = buildDeduplicatedTids(curMain, curRender, curReader, curRaster);
          if (tids.length > 0) {
            if (!session.setThreads(tids)) {
              session.close();
              session = bridge.createSession(context, tids, desiredTarget);
              targetWorkDurationNanos = desiredTarget;
            }
            if (session != null) {
              sessionMainTid = curMain;
              sessionRenderThreadTid = curRender;
              sessionReaderTid = curReader;
              sessionRasterTid = curRaster;
            }
          }
        }

        if (session != null && desiredTarget != targetWorkDurationNanos) {
          session.updateTargetWorkDuration(desiredTarget);
          targetWorkDurationNanos = desiredTarget;
        }
      }
    }

    void onFrameCommittedOnRenderThread(long vsyncNanos, long commitNanos, int currentTid) {
      if (renderThreadTid != currentTid && currentTid > 0) {
        renderThreadTid = currentTid;
      }
      if (!renderThreadAffinityApplied) {
        if (bridge.requestNotEfficiencyCpuAffinity()) {
          renderThreadAffinityApplied = true;
        }
      }
      final long rawDurationNanos = commitNanos - vsyncNanos;
      if (rawDurationNanos <= 0L || rawDurationNanos > MAX_FRAME_TIME_AGE_NANOS) {
        return;
      }
      final long clampedDurationNanos = Math.min(rawDurationNanos, MAX_REPORTED_DURATION_NANOS);
      AdpfSessionHandle activeSession;
      synchronized (lock) {
        activeSession = session;
        if (activeSession == null) {
          return;
        }
        if (vsyncNanos < lastReportedVsyncNanos) {
          return;
        }
        if (vsyncNanos == lastReportedVsyncNanos) {
          final boolean crossedTarget =
              clampedDurationNanos > targetWorkDurationNanos
                  && lastReportedDurationNanos <= targetWorkDurationNanos;
          final boolean grewMaterially =
              clampedDurationNanos - lastReportedDurationNanos >= SAME_VSYNC_UPDATE_DELTA_NANOS;
          if (!crossedTarget && !grewMaterially) {
            return;
          }
        }
        lastReportedVsyncNanos = vsyncNanos;
        lastReportedDurationNanos = clampedDurationNanos;
      }
      activeSession.reportActualWorkDuration(clampedDurationNanos);
    }
  }

  @VisibleForTesting
  static final PipelineAdpfController adpfController = new PipelineAdpfController();

  public PlatformViewWrapper(@NonNull Context context) {
    super(context);
    setWillNotDraw(false);
  }

  @SuppressLint("NewApi")
  public PlatformViewWrapper(
      @NonNull Context context, @NonNull PlatformViewRenderTarget renderTarget) {
    this(context);
    this.renderTarget = renderTarget;

    Surface surface = renderTarget.getSurface();
    if (surface != null && !FlutterRenderer.debugDisableSurfaceClear) {
      if (useDedicatedHardwareRenderer) {
        // A surface can only have one producer connected, so the dedicated renderer must also be
        // used for the initial clear.
        final RecordingCanvas canvas = beginHardwareRendererFrame(surface);
        try {
          canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        } finally {
          hardwareRendererRoot.endRecording();
        }
        hardwareRenderer.createRenderRequest().setVsyncTime(System.nanoTime()).syncAndDraw();
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
    postPendingFrame();
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

  /** Releases resources. */
  public void release() {
    postPendingFrame();
    if (hardwareRenderer != null) {
      hardwareRenderer.destroy();
      hardwareRenderer = null;
      hardwareRendererRoot = null;
      hardwareRendererSurface = null;
      adpfController.onRendererDestroyed();
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

    final Surface targetSurface = renderTarget.getSurface();
    if (!targetSurface.isValid()) {
      Log.e(TAG, "Platform view cannot be composed without a valid RenderTarget surface.");
      return;
    }

    // A frame recorded by a previous draw() in this same traversal must be posted first.
    postPendingFrame();

    if (useDedicatedHardwareRenderer) {
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
  @RequiresApi(29)
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

  @RequiresApi(29)
  @NonNull
  private RecordingCanvas beginHardwareRendererFrame(@NonNull Surface targetSurface) {
    if (hardwareRenderer == null) {
      hardwareRendererRoot = new RenderNode("PlatformViewWrapper");
      hardwareRendererRoot.setClipToBounds(false);
      hardwareRenderer = new HardwareRenderer();
      hardwareRenderer.setContentRoot(hardwareRendererRoot);
      hardwareRenderer.setOpaque(false);
      // Match Surface.HwuiContext, which disables elevation shadows.
      hardwareRenderer.setLightSourceAlpha(0.0f, 0.0f);
      hardwareRenderer.setLightSourceGeometry(0.0f, 0.0f, 0.0f, 0.0f);
      adpfController.onRendererCreated();
    }
    if (hardwareRendererSurface != targetSurface) {
      hardwareRenderer.setSurface(targetSurface);
      hardwareRendererSurface = targetSurface;
    }
    final int width = renderTarget.getWidth();
    final int height = renderTarget.getHeight();
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
    final long now = System.nanoTime();
    final long frameTimeNanos = getDrawingTime() * 1_000_000L;
    if (frameTimeNanos <= 0
        || frameTimeNanos > now
        || now - frameTimeNanos > MAX_FRAME_TIME_AGE_NANOS) {
      return now;
    }
    return frameTimeNanos;
  }

  /** Posts the frame recorded by the last {@link #draw(Canvas)} call to the render target. */
  private void postPendingFrame() {
    if (hasPendingHardwareRendererFrame) {
      hasPendingHardwareRendererFrame = false;
      mainHandler.removeCallbacks(postPendingFrameRunnable);
      int syncResult = HardwareRenderer.SYNC_OK;
      if (hardwareRenderer != null) {
        final int sdkInt = Build.VERSION.SDK_INT;
        adpfController.prepareForFrameOnMainThread(getContext(), sdkInt);
        final long frameVsyncNanos = pendingFrameVsyncNanos;
        final HardwareRenderer.FrameRenderRequest request =
            hardwareRenderer.createRenderRequest().setVsyncTime(frameVsyncNanos);
        if (adpfController.needsFrameCommitCallback(sdkInt)) {
          request.setFrameCommitCallback(
              DIRECT_EXECUTOR,
              () ->
                  adpfController.onFrameCommittedOnRenderThread(
                      frameVsyncNanos, System.nanoTime(), Process.myTid()));
        }
        syncResult = request.syncAndDraw();
      }
      if (renderTarget != null
          && (syncResult
                  & (HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND
                      | HardwareRenderer.SYNC_CONTEXT_IS_STOPPED
                      | HardwareRenderer.SYNC_FRAME_DROPPED))
              == 0) {
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
    if (renderTarget != null) {
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

  /**
   * Delivers the remainder of a gesture synchronously to the embedded view once the framework has
   * resolved the gesture arena in the platform view's favour. See {@link
   * PlatformViewDirectTouchDispatcher}.
   */
  private final PlatformViewDirectTouchDispatcher directTouchDispatcher =
      new PlatformViewDirectTouchDispatcher();

  /**
   * Informs this view that the Flutter framework started forwarding the given gesture to the
   * platform view (the platform view won the gesture arena).
   *
   * @param forwardedEvent The first event of the gesture forwarded by the framework, with the
   *     framework's PV-local coordinates.
   */
  public void onPlatformViewWonGesture(@NonNull MotionEvent forwardedEvent) {
    directTouchDispatcher.onPlatformViewWonGesture(
        gestureTracker.isGestureActive(), gestureTracker.getCurrentDownTime(), forwardedEvent);
  }

  /** See {@link PlatformViewDirectTouchDispatcher#isDirectlyDispatched(MotionEvent)}. */
  public boolean isDirectlyDispatched(@NonNull MotionEvent echoedEvent) {
    return directTouchDispatcher.isDirectlyDispatched(echoedEvent);
  }

  /** See {@link PlatformViewDirectTouchDispatcher#verifyDirectlyDispatchedEcho(MotionEvent)}. */
  public void verifyDirectlyDispatchedEcho(@NonNull MotionEvent echoedEvent) {
    directTouchDispatcher.verifyDirectlyDispatchedEcho(echoedEvent);
  }

  /** Whether the active gesture is currently delivered directly to the embedded view. */
  public boolean isDirectDispatchActive() {
    return directTouchDispatcher.isActive();
  }

  private Matrix cachedScreenMatrix = null;
  private int cachedScreenMatrixLeft = Integer.MIN_VALUE;
  private int cachedScreenMatrixTop = Integer.MIN_VALUE;

  @Override
  @SuppressLint("ClickableViewAccessibility")
  public boolean onTouchEvent(@NonNull MotionEvent event) {
    if (touchProcessor == null) {
      return super.onTouchEvent(event);
    }
    gestureTracker.onTouchEvent(event, this::requestUnbuffered);
    final boolean deliveredDirectly = directTouchDispatcher.onTouchEvent(event, getChildAt(0));
    // The parent transformed this event into this view's coordinate space using the view's current
    // position, so the same position maps it back into the parent's (the FlutterView's) space.
    if (cachedScreenMatrix == null
        || getLeft() != cachedScreenMatrixLeft
        || getTop() != cachedScreenMatrixTop) {
      cachedScreenMatrix = new Matrix();
      cachedScreenMatrix.postTranslate(getLeft(), getTop());
      cachedScreenMatrixLeft = getLeft();
      cachedScreenMatrixTop = getTop();
    }
    // Events that were already delivered to the embedded view need no tracked copy: the framework
    // is told not to echo them (see PlatformViewsChannel#invokeDirectTouchDispatch), and any echo
    // that still arrives is dropped by PlatformViewsController regardless.
    return touchProcessor.onTouchEvent(
        event, cachedScreenMatrix, /*trackEvent=*/ !deliveredDirectly);
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
