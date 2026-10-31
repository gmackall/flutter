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
import android.view.Choreographer;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
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
   * submits every frame with {@code System.nanoTime()} at unlock time rather than the traversal's
   * Choreographer frame time. Using a dedicated {@link HardwareRenderer} allows supplying the
   * Choreographer frame timestamp from {@link #getDrawingTime()} via {@link
   * HardwareRenderer.FrameRenderRequest#setVsyncTime(long)}, as recommended by the {@link
   * HardwareRenderer} API so HWUI frame timing and animations use the true vsync start time, and
   * exposes the {@link HardwareRenderer.FrameRenderRequest#syncAndDraw()} result bits ({@link
   * HardwareRenderer#SYNC_REDRAW_REQUESTED}, {@link
   * HardwareRenderer#SYNC_LOST_SURFACE_REWARD_IF_FOUND}, {@link
   * HardwareRenderer#SYNC_FRAME_DROPPED}).
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
  // Whether a next-vsync native redraw has been scheduled after SYNC_REDRAW_REQUESTED.
  private boolean hasPendingRedrawRequest;
  private final Choreographer.FrameCallback redrawFrameCallback =
      frameTimeNanos -> {
        if (!hasPendingRedrawRequest) {
          return;
        }
        hasPendingRedrawRequest = false;
        if (renderTarget != null) {
          invalidate();
        }
      };
  /** Supplies the live Linux TID of an owning engine's raster thread (or 0 if inactive). */
  public interface IntSupplier {
    int getAsInt();
  }

  private boolean registeredWithAdpf;
  @Nullable private IntSupplier rasterThreadTidSupplier;

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

    boolean requestNotEfficiencyCpuAffinity(int tid);

    default boolean restoreCpuAffinity(int tid) {
      return false;
    }
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
        public boolean requestNotEfficiencyCpuAffinity(int tid) {
          if (tid <= 0) {
            return false;
          }
          try {
            return FlutterJNI.requestCpuAffinityForThread(tid);
          } catch (UnsatisfiedLinkError ignored) {
            return false;
          }
        }

        @Override
        public boolean restoreCpuAffinity(int tid) {
          if (tid <= 0) {
            return false;
          }
          try {
            return FlutterJNI.restoreCpuAffinityForThread(tid);
          } catch (UnsatisfiedLinkError ignored) {
            return false;
          }
        }
      };

  /**
   * Coordinates display-refresh-aware ADPF hint sessions across active TLHC rendering pipelines
   * ({@code main}, {@code RenderThread}, {@code FlutterImageReader}, and the exact set of actively
   * participating engines' raster threads) with strictly opt-in, TID-keyed, cross-thread-restorable
   * CPU affinity (disabled by default so ordinary scheduling relies purely on kernel {@code uclamp}
   * hints from {@link PerformanceHintManager.Session}).
   *
   * <p>Target duration and reported actual work duration model:
   *
   * <ul>
   *   <li>Target work duration ({@link #computeTargetWorkDurationNanos(float)}) equals the full
   *       display frame period ({@code Math.round(1_000_000_000.0 / clampedFps)}, e.g. 8,333,333 ns
   *       at 120 Hz and 16,666,667 ns at 60 Hz), matching the per-frame budget shared by the
   *       session thread group.
   *   <li>Reported actual work duration ({@link PerformanceHintManager.Session
   *       #reportActualWorkDuration(long)}) measures elapsed producer frame work {@code commitNanos
   *       - vsyncNanos} in {@link #onFrameCommittedOnRenderThread(long, long, int)} (spanning
   *       main-thread UI traversal plus HWUI {@code RenderThread} sync, recording, and GPU command
   *       submission up to {@code FrameRenderRequest.setFrameCommitCallback}). Downstream {@code
   *       FlutterImageReader} and participating engines' {@code raster} threads share the session's
   *       kernel {@code uclamp} group via thread membership in {@link
   *       PerformanceHintManager#createHintSession(int[], long)} / {@code setThreads(int[])}.
   *   <li>When CPU affinity opt-in is enabled ({@link #setCpuAffinityEnabled(boolean)}), baseline
   *       masks are saved per explicit Linux TID via {@code sched_getaffinity(tid)} and restored
   *       per explicit Linux TID via {@code sched_setaffinity(tid)} when active TLHC rendering
   *       stops, regardless of which thread invokes teardown.
   * </ul>
   */
  static final class PipelineAdpfController {
    private static final long MAX_REPORTED_DURATION_NANOS = 50_000_000L;
    private static final long SAME_VSYNC_UPDATE_DELTA_NANOS = 1_000_000L;

    private final Object lock = new Object();
    @NonNull private AdpfHintBridge bridge = DEFAULT_ADPF_BRIDGE;
    @Nullable private AdpfSessionHandle session;
    private final IdentityHashMap<Object, IntSupplier> activeRenderers = new IdentityHashMap<>();
    private final Set<Integer> appliedAffinityTids = new LinkedHashSet<>();
    private boolean cpuAffinityEnabled = false;

    private int mainTid = 0;
    private volatile int renderThreadTid = 0;
    @Nullable private int[] lastFailedSessionTids = null;
    @NonNull private int[] sessionActiveTids = new int[0];
    private long targetWorkDurationNanos = 0L;

    private long lastReportedVsyncNanos = 0L;
    private long lastReportedDurationNanos = 0L;

    static long computeTargetWorkDurationNanos(float refreshRateFPS) {
      final float clampedFps =
          (Float.isNaN(refreshRateFPS) || refreshRateFPS <= 0.0f)
              ? 60.0f
              : Math.max(30.0f, Math.min(240.0f, refreshRateFPS));
      return Math.round(1_000_000_000.0 / clampedFps);
    }

    @NonNull
    static int[] buildDeduplicatedTids(@NonNull int... candidates) {
      int count = 0;
      final int[] temp = new int[candidates.length];
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

    private void restoreAppliedAffinitiesLocked() {
      if (appliedAffinityTids.isEmpty()) {
        return;
      }
      for (Integer tid : appliedAffinityTids) {
        if (tid != null && tid > 0) {
          bridge.restoreCpuAffinity(tid);
        }
      }
      appliedAffinityTids.clear();
    }

    private void applyAffinityForTidLocked(int tid) {
      if (tid <= 0 || appliedAffinityTids.contains(tid)) {
        return;
      }
      if (bridge.requestNotEfficiencyCpuAffinity(tid)) {
        appliedAffinityTids.add(tid);
      }
    }

    void setBridgeForTest(@NonNull AdpfHintBridge testBridge) {
      synchronized (lock) {
        if (session != null) {
          session.close();
          session = null;
        }
        restoreAppliedAffinitiesLocked();
        bridge = testBridge;
        activeRenderers.clear();
        cpuAffinityEnabled = false;
        mainTid = 0;
        renderThreadTid = 0;
        lastFailedSessionTids = null;
        sessionActiveTids = new int[0];
        targetWorkDurationNanos = 0L;
        lastReportedVsyncNanos = 0L;
        lastReportedDurationNanos = 0L;
      }
    }

    /**
     * Controls whether explicit kernel CPU affinity ({@code CpuAffinity::kNotEfficiency}) is
     * applied during active TLHC rendering and restored when active TLHC rendering stops. Defaults
     * to {@code false} so ordinary apps and default TLHC sessions rely exclusively on ADPF {@link
     * PerformanceHintManager.Session} hints without mutating thread affinity masks.
     */
    void setCpuAffinityEnabled(boolean enabled) {
      synchronized (lock) {
        if (cpuAffinityEnabled == enabled) {
          return;
        }
        cpuAffinityEnabled = enabled;
        if (!enabled) {
          restoreAppliedAffinitiesLocked();
        }
      }
    }

    boolean isCpuAffinityEnabled() {
      synchronized (lock) {
        return cpuAffinityEnabled;
      }
    }

    int getActiveRendererCountForTest() {
      synchronized (lock) {
        return activeRenderers.size();
      }
    }

    @NonNull
    int[] getSessionActiveTidsForTest() {
      synchronized (lock) {
        return sessionActiveTids.clone();
      }
    }

    @NonNull
    private int[] collectDesiredSessionTidsLocked() {
      final int curMain = mainTid;
      final int curRender = renderThreadTid;
      final int curReader = bridge.getSharedImageReaderTid();
      final int[] raw = new int[3 + activeRenderers.size()];
      int idx = 0;
      raw[idx++] = curMain;
      raw[idx++] = curRender;
      raw[idx++] = curReader;
      for (IntSupplier supplier : activeRenderers.values()) {
        if (supplier != null) {
          final int tid = supplier.getAsInt();
          if (tid > 0) {
            raw[idx++] = tid;
          }
        }
      }
      // Sort participating raster TIDs deterministically so session equality checks are
      // order-independent.
      if (idx > 4) {
        Arrays.sort(raw, 3, idx);
      }
      final int[] trimmed = new int[idx];
      System.arraycopy(raw, 0, trimmed, 0, idx);
      return buildDeduplicatedTids(trimmed);
    }

    void onRendererCreated(@NonNull Object owner, @Nullable IntSupplier rasterTidSupplier) {
      synchronized (lock) {
        activeRenderers.put(owner, rasterTidSupplier);
      }
    }

    void onRendererDestroyed(@NonNull Object owner) {
      synchronized (lock) {
        onRendererDestroyedLocked(owner);
      }
    }

    private void onRendererDestroyedLocked(@NonNull Object owner) {
      activeRenderers.remove(owner);
      if (activeRenderers.isEmpty()) {
        lastFailedSessionTids = null;
        if (session != null) {
          session.close();
          session = null;
          sessionActiveTids = new int[0];
          targetWorkDurationNanos = 0L;
        }
        restoreAppliedAffinitiesLocked();
      } else if (session != null) {
        final int[] updatedTids = collectDesiredSessionTidsLocked();
        if (updatedTids.length > 0 && !Arrays.equals(updatedTids, sessionActiveTids)) {
          if (session.setThreads(updatedTids)) {
            sessionActiveTids = updatedTids;
            lastFailedSessionTids = null;
          }
        }
      }
    }

    boolean needsFrameCommitCallback(int sdkInt) {
      synchronized (lock) {
        if (cpuAffinityEnabled && !appliedAffinityTids.contains(renderThreadTid)) {
          return true;
        }
        if (sdkInt < API_LEVELS.API_31) {
          return false;
        }
        return session != null || renderThreadTid == 0;
      }
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
        final long desiredTarget = computeTargetWorkDurationNanos(bridge.getRefreshRateFPS());
        final int[] tids = collectDesiredSessionTidsLocked();
        if (tids.length == 0) {
          return;
        }
        final int[] coreTids = buildDeduplicatedTids(curMain, curRender);
        final boolean hasAuxTids = !Arrays.equals(coreTids, tids);

        if (session == null) {
          if (lastFailedSessionTids != null && Arrays.equals(tids, lastFailedSessionTids)) {
            return;
          }
          session = bridge.createSession(context, tids, desiredTarget);
          if (session != null) {
            lastFailedSessionTids = null;
            sessionActiveTids = tids;
            targetWorkDurationNanos = desiredTarget;
            return;
          }
          if (hasAuxTids && coreTids.length > 0) {
            session = bridge.createSession(context, coreTids, desiredTarget);
            if (session != null) {
              lastFailedSessionTids = tids.clone();
              sessionActiveTids = coreTids;
              targetWorkDurationNanos = desiredTarget;
              return;
            }
          }
          lastFailedSessionTids = tids.clone();
          return;
        }

        if (!Arrays.equals(tids, sessionActiveTids)
            && !(lastFailedSessionTids != null
                && Arrays.equals(tids, lastFailedSessionTids)
                && Arrays.equals(coreTids, sessionActiveTids))) {
          if (session.setThreads(tids)) {
            lastFailedSessionTids = null;
            sessionActiveTids = tids;
          } else if (sdkInt >= API_LEVELS.API_34
              && hasAuxTids
              && coreTids.length > 0
              && session.setThreads(coreTids)) {
            lastFailedSessionTids = tids.clone();
            sessionActiveTids = coreTids;
          } else {
            session.close();
            session = bridge.createSession(context, tids, desiredTarget);
            if (session != null) {
              lastFailedSessionTids = null;
              sessionActiveTids = tids;
              targetWorkDurationNanos = desiredTarget;
            } else if (hasAuxTids && coreTids.length > 0) {
              lastFailedSessionTids = tids.clone();
              session = bridge.createSession(context, coreTids, desiredTarget);
              if (session != null) {
                sessionActiveTids = coreTids;
                targetWorkDurationNanos = desiredTarget;
              } else {
                sessionActiveTids = new int[0];
                targetWorkDurationNanos = 0L;
              }
            } else {
              lastFailedSessionTids = tids.clone();
              sessionActiveTids = new int[0];
              targetWorkDurationNanos = 0L;
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
      final long rawDurationNanos = commitNanos - vsyncNanos;
      synchronized (lock) {
        if (cpuAffinityEnabled && !activeRenderers.isEmpty()) {
          applyAffinityForTidLocked(currentTid);
          applyAffinityForTidLocked(bridge.getSharedImageReaderTid());
        }
        if (rawDurationNanos <= 0L || rawDurationNanos > MAX_FRAME_TIME_AGE_NANOS) {
          return;
        }
        final long clampedDurationNanos = Math.min(rawDurationNanos, MAX_REPORTED_DURATION_NANOS);
        if (session == null || activeRenderers.isEmpty()) {
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
        session.reportActualWorkDuration(clampedDurationNanos);
      }
    }
  }

  /**
   * Configures whether workload-scoped non-efficiency CPU affinity hints are applied during active
   * TLHC rendering. Defaults to {@code false} unless explicitly enabled (for example via {@link
   * PlatformViewsController#META_DATA_ENABLE_TLHC_CPU_AFFINITY}).
   */
  public static void setCpuAffinityEnabled(boolean enabled) {
    adpfController.setCpuAffinityEnabled(enabled);
  }

  /** Returns whether workload-scoped TLHC CPU affinity hints are currently enabled. */
  public static boolean isCpuAffinityEnabled() {
    return adpfController.isCpuAffinityEnabled();
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
   * Sets the supplier for this wrapper's owning Flutter engine raster thread TID. When active, the
   * ADPF hint session tracks the live raster TID of each participating engine instance (including
   * shared {@code ThreadHost} groups across spawned engines) rather than a process-global last
   * engine TID.
   */
  public void setRasterThreadTidSupplier(@Nullable IntSupplier supplier) {
    rasterThreadTidSupplier = supplier;
    if (registeredWithAdpf) {
      adpfController.onRendererCreated(this, rasterThreadTidSupplier);
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

  @VisibleForTesting
  void scheduleNativeRedraw() {
    if (hasPendingRedrawRequest) {
      return;
    }
    hasPendingRedrawRequest = true;
    Choreographer.getInstance().postFrameCallback(redrawFrameCallback);
  }

  private void cancelPendingRedrawRequest() {
    if (!hasPendingRedrawRequest) {
      return;
    }
    hasPendingRedrawRequest = false;
    Choreographer.getInstance().removeFrameCallback(redrawFrameCallback);
  }

  private void registerWithAdpfIfNeeded() {
    if (!registeredWithAdpf) {
      registeredWithAdpf = true;
      adpfController.onRendererCreated(this, rasterThreadTidSupplier);
    }
  }

  private void unregisterFromAdpfIfNeeded() {
    if (registeredWithAdpf) {
      registeredWithAdpf = false;
      adpfController.onRendererDestroyed(this);
    }
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    if (visibility != View.VISIBLE) {
      // Flush any pending frame before detaching the surface.
      postPendingFrame(true);
      cancelPendingRedrawRequest();
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
    cancelPendingRedrawRequest();
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

  /**
   * Records this view's subtree into {@link #hardwareRendererRoot} and schedules its submission to
   * the render target, like the {@link Surface#lockHardwareCanvas()} path in {@link #draw(Canvas)}.
   */
  @RequiresApi(API_LEVELS.API_29)
  private void drawWithHardwareRenderer(@NonNull Surface targetSurface) {
    cancelPendingRedrawRequest();
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
   * <p>During a traversal, {@link #getDrawingTime()} is the Choreographer frame time (in ms), which
   * {@link HardwareRenderer.FrameRenderRequest#setVsyncTime(long)} expects in the {@link
   * System#nanoTime()} time base.
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
      if ((syncResult & HardwareRenderer.SYNC_REDRAW_REQUESTED) != 0) {
        // Android requires another render pass on the next vsync when SYNC_REDRAW_REQUESTED is
        // returned (e.g. for a RenderNode animation that the HardwareRenderer cannot advance on
        // its own). Schedule a cancellable native redraw on the next animation frame so the next
        // traversal records and submits a new frame.
        scheduleNativeRedraw();
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

  /**
   * Delivers the remainder of a gesture synchronously to the embedded view once the framework has
   * resolved the gesture arena in the platform view's favour. See {@link
   * PlatformViewDirectTouchDispatcher}.
   */
  private final PlatformViewDirectTouchDispatcher directTouchDispatcher =
      new PlatformViewDirectTouchDispatcher();

  /**
   * Sets a listener invoked when direct touch dispatch transitions from active to inactive
   * mid-gesture (see {@link
   * PlatformViewDirectTouchDispatcher#setOnDirectTouchDeactivatedListener}).
   */
  public void setOnDirectTouchDeactivatedListener(
      @Nullable PlatformViewDirectTouchDispatcher.OnDirectTouchDeactivatedListener listener) {
    directTouchDispatcher.setOnDirectTouchDeactivatedListener(listener);
  }

  /**
   * Informs this view that the Flutter framework started forwarding the given gesture to the
   * platform view (the platform view won the gesture arena).
   *
   * @param forwardedEvent The first event of the gesture forwarded by the framework, with the
   *     framework's PV-local coordinates.
   * @return true if this call activated direct dispatch for {@code forwardedEvent}'s gesture.
   */
  public boolean onPlatformViewWonGesture(@NonNull MotionEvent forwardedEvent) {
    final View embeddedView = getChildAt(0);
    return directTouchDispatcher.onPlatformViewWonGesture(
        gestureTracker.isGestureActive(),
        gestureTracker.getCurrentDownTime(),
        forwardedEvent,
        embeddedView != null ? embeddedView : this);
  }

  /** See {@link PlatformViewDirectTouchDispatcher#isDirectlyDispatched(MotionEvent)}. */
  public boolean isDirectlyDispatched(@NonNull MotionEvent echoedEvent) {
    return directTouchDispatcher.isDirectlyDispatched(echoedEvent);
  }

  /** See {@link PlatformViewDirectTouchDispatcher#verifyDirectlyDispatchedEcho(MotionEvent)}. */
  public boolean verifyDirectlyDispatchedEcho(@NonNull MotionEvent echoedEvent) {
    return directTouchDispatcher.verifyDirectlyDispatchedEcho(echoedEvent);
  }

  /** Whether the active gesture is currently delivered directly to the embedded view. */
  public boolean isDirectDispatchActive() {
    return directTouchDispatcher.isActive();
  }

  /**
   * Invalidates native direct touch dispatch for the current gesture without injecting a synthetic
   * {@link MotionEvent#ACTION_CANCEL}, incrementing the mapping generation counter so stale
   * in-flight echoes cannot re-activate direct dispatch until a fresh {@link
   * MotionEvent#ACTION_DOWN} begins with a valid translation-only mapping.
   */
  public void invalidateDirectTouch() {
    directTouchDispatcher.invalidateDirectTouch();
  }

  /** See {@link #invalidateDirectTouch()}. */
  public void invalidateDirectTouch(long downTime) {
    directTouchDispatcher.invalidateDirectTouch(downTime);
  }

  /** Returns the current mapping generation counter for direct touch dispatch. */
  @VisibleForTesting
  public long getDirectTouchMappingGeneration() {
    return directTouchDispatcher.getMappingGeneration();
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
    final View embeddedView = getChildAt(0);
    final boolean deliverDirectly = directTouchDispatcher.prepareTouchEvent(event, embeddedView);
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
    // Forward to touchProcessor before embeddedView.dispatchTouchEvent(event) so a child view that
    // mutates its MotionEvent in place cannot corrupt the coordinates sent to Flutter. Events that
    // are delivered directly need no tracked copy: the framework is told not to echo them (see
    // PlatformViewsChannel#invokeDirectTouchDispatch), and any echo that still arrives is dropped
    // by PlatformViewsController regardless.
    final boolean handled =
        touchProcessor.onTouchEvent(
            event, cachedScreenMatrix, /*trackEvent=*/ !deliverDirectly);
    if (deliverDirectly) {
      embeddedView.dispatchTouchEvent(event);
    }
    return handled;
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
