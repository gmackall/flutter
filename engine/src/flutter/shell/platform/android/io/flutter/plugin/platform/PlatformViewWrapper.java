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
import java.util.Arrays;
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
  private boolean registeredWithAdpf;

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

  @VisibleForTesting
  static final AdpfHintBridge DEFAULT_ADPF_BRIDGE =
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
          try {
            return FlutterJNI.getLastRasterThreadTid();
          } catch (UnsatisfiedLinkError ignored) {
            return 0;
          }
        }

        @Override
        public boolean requestNotEfficiencyCpuAffinity() {
          try {
            return FlutterJNI.requestNotEfficiencyCpuAffinity();
          } catch (UnsatisfiedLinkError ignored) {
            return false;
          }
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
    private volatile int lastAffinityAttemptedRenderThreadTid = 0;
    @Nullable private int[] lastFailedSessionTids = null;

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
        activeRendererCount = 0;
        mainTid = 0;
        renderThreadTid = 0;
        renderThreadAffinityApplied = false;
        lastAffinityAttemptedRenderThreadTid = 0;
        lastFailedSessionTids = null;
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
        if (activeRendererCount == 0) {
          lastFailedSessionTids = null;
          if (session != null) {
            toClose = session;
            session = null;
            sessionMainTid = 0;
            sessionRenderThreadTid = 0;
            sessionReaderTid = 0;
            sessionRasterTid = 0;
            targetWorkDurationNanos = 0L;
          }
        }
      }
      if (toClose != null) {
        toClose.close();
      }
    }

    boolean needsFrameCommitCallback(int sdkInt) {
      return sdkInt >= API_LEVELS.API_31
          || (!renderThreadAffinityApplied && lastAffinityAttemptedRenderThreadTid == 0);
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
        final int[] tids = buildDeduplicatedTids(curMain, curRender, curReader, curRaster);
        if (tids.length == 0) {
          return;
        }
        final boolean hasAuxTids = curReader > 0 || curRaster > 0;

        if (session == null) {
          if (lastFailedSessionTids != null && Arrays.equals(tids, lastFailedSessionTids)) {
            return;
          }
          session = bridge.createSession(context, tids, desiredTarget);
          if (session != null) {
            lastFailedSessionTids = null;
            sessionMainTid = curMain;
            sessionRenderThreadTid = curRender;
            sessionReaderTid = curReader;
            sessionRasterTid = curRaster;
            targetWorkDurationNanos = desiredTarget;
            return;
          }
          if (hasAuxTids) {
            final int[] coreTids = buildDeduplicatedTids(curMain, curRender, 0, 0);
            if (coreTids.length > 0 && !Arrays.equals(coreTids, tids)) {
              session = bridge.createSession(context, coreTids, desiredTarget);
              if (session != null) {
                lastFailedSessionTids = tids.clone();
                sessionMainTid = curMain;
                sessionRenderThreadTid = curRender;
                sessionReaderTid = 0;
                sessionRasterTid = 0;
                targetWorkDurationNanos = desiredTarget;
                return;
              }
            }
          }
          lastFailedSessionTids = tids.clone();
          return;
        }

        final boolean threadsChanged =
            curMain != sessionMainTid
                || curRender != sessionRenderThreadTid
                || curReader != sessionReaderTid
                || curRaster != sessionRasterTid;

        if (threadsChanged
            && !(lastFailedSessionTids != null
                && Arrays.equals(tids, lastFailedSessionTids)
                && curMain == sessionMainTid
                && curRender == sessionRenderThreadTid)) {
          if (session.setThreads(tids)) {
            lastFailedSessionTids = null;
            sessionMainTid = curMain;
            sessionRenderThreadTid = curRender;
            sessionReaderTid = curReader;
            sessionRasterTid = curRaster;
          } else {
            final int[] coreTids =
                hasAuxTids ? buildDeduplicatedTids(curMain, curRender, 0, 0) : new int[0];
            if (sdkInt >= API_LEVELS.API_34
                && coreTids.length > 0
                && !Arrays.equals(coreTids, tids)
                && session.setThreads(coreTids)) {
              lastFailedSessionTids = tids.clone();
              sessionMainTid = curMain;
              sessionRenderThreadTid = curRender;
              sessionReaderTid = 0;
              sessionRasterTid = 0;
            } else {
              session.close();
              session = bridge.createSession(context, tids, desiredTarget);
              if (session != null) {
                lastFailedSessionTids = null;
                sessionMainTid = curMain;
                sessionRenderThreadTid = curRender;
                sessionReaderTid = curReader;
                sessionRasterTid = curRaster;
                targetWorkDurationNanos = desiredTarget;
              } else if (coreTids.length > 0 && !Arrays.equals(coreTids, tids)) {
                lastFailedSessionTids = tids.clone();
                session = bridge.createSession(context, coreTids, desiredTarget);
                if (session != null) {
                  sessionMainTid = curMain;
                  sessionRenderThreadTid = curRender;
                  sessionReaderTid = 0;
                  sessionRasterTid = 0;
                  targetWorkDurationNanos = desiredTarget;
                } else {
                  sessionMainTid = 0;
                  sessionRenderThreadTid = 0;
                  sessionReaderTid = 0;
                  sessionRasterTid = 0;
                  targetWorkDurationNanos = 0L;
                }
              } else {
                lastFailedSessionTids = tids.clone();
                sessionMainTid = 0;
                sessionRenderThreadTid = 0;
                sessionReaderTid = 0;
                sessionRasterTid = 0;
                targetWorkDurationNanos = 0L;
              }
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
      if (currentTid > 0 && lastAffinityAttemptedRenderThreadTid != currentTid) {
        lastAffinityAttemptedRenderThreadTid = currentTid;
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

  private void registerWithAdpfIfNeeded() {
    if (!registeredWithAdpf) {
      registeredWithAdpf = true;
      adpfController.onRendererCreated();
    }
  }

  private void unregisterFromAdpfIfNeeded() {
    if (registeredWithAdpf) {
      registeredWithAdpf = false;
      adpfController.onRendererDestroyed();
    }
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
      unregisterFromAdpfIfNeeded();
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    postPendingFrame(true);
    if (hardwareRenderer != null && Build.VERSION.SDK_INT >= API_LEVELS.API_29) {
      setHardwareRendererSurface(hardwareRenderer, null);
      hardwareRendererSurface = null;
    }
    unregisterFromAdpfIfNeeded();
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
    unregisterFromAdpfIfNeeded();
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
    registerWithAdpfIfNeeded();
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
  void setHardwareRendererSurface(@NonNull HardwareRenderer renderer, @Nullable Surface surface) {
    renderer.setSurface(surface);
  }

  @RequiresApi(API_LEVELS.API_29)
  @VisibleForTesting
  int syncAndDrawHardwareRendererFrame(@NonNull HardwareRenderer renderer, long vsyncNanos) {
    final int sdkInt = Build.VERSION.SDK_INT;
    final HardwareRenderer.FrameRenderRequest request =
        renderer.createRenderRequest().setVsyncTime(vsyncNanos);
    if (adpfController.needsFrameCommitCallback(sdkInt)) {
      request.setFrameCommitCallback(
          DIRECT_EXECUTOR,
          () ->
              adpfController.onFrameCommittedOnRenderThread(
                  vsyncNanos, System.nanoTime(), Process.myTid()));
    }
    return request.syncAndDraw();
  }

  /** Posts the frame recorded by the last {@link #draw(Canvas)} call to the render target. */
  private void postPendingFrame(boolean scheduleFrame) {
    if (hasPendingHardwareRendererFrame) {
      hasPendingHardwareRendererFrame = false;
      mainHandler.removeCallbacks(postPendingFrameRunnable);
      // Unlike lockHardwareCanvas(), the display list recording is already closed in
      // drawWithHardwareRenderer(), so release() (scheduleFrame == false) only needs to cancel the
      // pending callback before destroying the renderer.
      if (hardwareRenderer == null || !scheduleFrame || Build.VERSION.SDK_INT < API_LEVELS.API_29) {
        return;
      }
      adpfController.prepareForFrameOnMainThread(getContext(), Build.VERSION.SDK_INT);
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
