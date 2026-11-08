// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.platform;

import static io.flutter.Build.API_LEVELS;

import android.content.Context;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.SparseArray;
import android.view.AttachedSurfaceControl;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import io.flutter.Log;
import io.flutter.embedding.android.AndroidTouchProcessor;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.android.MotionEventTracker;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.embedding.engine.FlutterOverlaySurface;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.mutatorsstack.*;
import io.flutter.embedding.engine.renderer.FlutterRenderer;
import io.flutter.embedding.engine.systemchannels.PlatformViewCreationRequest;
import io.flutter.embedding.engine.systemchannels.PlatformViewTouch;
import io.flutter.embedding.engine.systemchannels.PlatformViewsChannel2;
import io.flutter.plugin.editing.TextInputPlugin;
import io.flutter.view.AccessibilityBridge;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages platform views.
 *
 * <p>Each {@link io.flutter.embedding.engine.FlutterEngine} has a single platform views controller.
 * A platform views controller can be attached to at most one Flutter view.
 * [PlatformViewsController2] is intentionally a different class from [PlatformViewsController] in
 * order to separate out the complexity of the first 3 platform view implementations (Virtual
 * Display, Texture Layer, and Hybrid Composition). From the 4th platform view implementation,
 * Hybrid Composition++.
 */
public class PlatformViewsController2 implements PlatformViewsAccessibilityDelegate {
  private static final String TAG = "PlatformViewsController2";

  private PlatformViewRegistryImpl registry;
  private AndroidTouchProcessor androidTouchProcessor;
  private Context context;
  private FlutterView flutterView;
  private FlutterJNI flutterJNI = null;

  @Nullable private TextInputPlugin textInputPlugin;

  private PlatformViewsChannel2 platformViewsChannel;
  private final AccessibilityEventsDelegate accessibilityEventsDelegate;

  private final SparseArray<PlatformView> platformViews;
  private final SparseArray<FlutterMutatorView> platformViewParent;
  private final MotionEventTracker motionEventTracker;

  // Guarded by transactionLock: the raster thread appends to pendingRasterTransactions during a
  // frame and seals the frame's batch into pendingRasterFrames; the platform thread pops one
  // batch per swapTransactions() call.
  private final ArrayList<SurfaceControl.Transaction> pendingRasterTransactions;
  private final ArrayDeque<ArrayList<SurfaceControl.Transaction>> pendingRasterFrames =
      new ArrayDeque<>();
  private final Object transactionLock = new Object();

  // Platform-thread only: populated by swapTransactions(), drained by onEndFrame().
  private final ArrayList<SurfaceControl.Transaction> activeRasterTransactions;

  // Platform-thread only. Clips and overlay visibility share one transaction per frame.
  private SurfaceControl.Transaction pendingPlatformTransaction;
  private SurfaceControl.Transaction activePlatformTransaction;

  private static final class QueuedFrame {
    final ArrayList<Runnable> viewOps = new ArrayList<>();
    @Nullable SurfaceControl.Transaction platformTransaction;
    final ArrayList<SurfaceControl.Transaction> rasterTransactions = new ArrayList<>();
    final ArrayList<PlatformViewWrapper> renderTargetReleases = new ArrayList<>();
  }

  // Platform-thread only: defers a second frame that arrives before the current Choreographer
  // traversal has drawn the already-staged frame, so at most one frame is consumed per VSync.
  private final ArrayList<Runnable> pendingViewOps = new ArrayList<>();
  @Nullable private QueuedFrame queuedFrame;
  private boolean frameStagedForTraversal = false;
  private boolean choreographerCallbackFired = false;
  @Nullable private Handler mainHandler;
  private final Runnable traversalCompleteRunnable =
      () -> {
        if (Build.VERSION.SDK_INT >= API_LEVELS.API_34) {
          onTraversalComplete();
        }
      };
  private final Choreographer.FrameCallback traversalFrameCallback =
      frameTimeNanos -> {
        choreographerCallbackFired = true;
        if (mainHandler == null) {
          mainHandler = new Handler(Looper.getMainLooper());
        }
        Message msg = Message.obtain(mainHandler, traversalCompleteRunnable);
        msg.setAsynchronous(true);
        mainHandler.sendMessageAtFrontOfQueue(msg);
      };

  private Surface overlayerSurface = null;
  private SurfaceControl overlaySurfaceControl = null;

  private boolean upgradeAllViewsToHcpp = false;
  private long uiThreadEndFrameCount = 0;

  // Tracks sealed raster frames waiting for swapTransactions(), platform-thread frames waiting for
  // onEndFrame() or Choreographer traversal completion, and transactions passed to
  // applyTransactionOnDraw() waiting for SurfaceFlinger commit (addTransactionCommittedListener) so
  // a 0-PV frame on the raster thread never overtakes an in-flight UI-synced transaction.
  private final AtomicInteger unswappedSealedFrames = new AtomicInteger(0);
  private final AtomicInteger pendingCommittedTransactions = new AtomicInteger(0);
  private final AtomicInteger commitGeneration = new AtomicInteger(0);
  private boolean hasActiveSwappedFrame = false;
  private volatile boolean platformHasInFlightSyncedFrame = false;
  private final Object pendingTransactionsSignal = new Object();

  private final WeakHashMap<SurfaceView, SurfaceHolder.Callback> pendingSurfaceCallbacks =
      new WeakHashMap<>();
  private final WeakHashMap<SurfaceView, View.OnLayoutChangeListener> pendingLayoutListeners =
      new WeakHashMap<>();
  private final HashSet<PlatformViewWrapper> pendingRenderTargetReleases = new HashSet<>();
  private final HashSet<PlatformViewWrapper> stagedRenderTargetReleases = new HashSet<>();

  public PlatformViewsController2() {
    accessibilityEventsDelegate = new AccessibilityEventsDelegate();
    platformViews = new SparseArray<>();
    platformViewParent = new SparseArray<>();
    pendingRasterTransactions = new ArrayList<>();
    activeRasterTransactions = new ArrayList<>();
    motionEventTracker = MotionEventTracker.getInstance();
  }

  public void setRegistry(@NonNull PlatformViewRegistry registry) {
    this.registry = (PlatformViewRegistryImpl) registry;
  }

  /** Whether the SurfaceControl swapchain mode is enabled. */
  public void setFlutterJNI(FlutterJNI flutterJNI) {
    this.flutterJNI = flutterJNI;
  }

  /**
   * Configures the {@code HcppUpgradeAllViews} opt-in escape hatch. Defaults to {@code false}
   * (TLHC-first for non-SurfaceView platform views).
   */
  public void setUpgradeAllViewsToHcpp(boolean enabled) {
    this.upgradeAllViewsToHcpp = enabled;
  }

  /** Returns true if {@code HcppUpgradeAllViews} is enabled on this controller. */
  public boolean shouldUpgradeAllViewsToHcpp() {
    return upgradeAllViewsToHcpp;
  }

  /**
   * Returns the number of frames that committed a SurfaceControl transaction on the UI thread via
   * {@code applyTransactionOnDraw}. When 0 HCPP platform views are active, frames present directly
   * on the raster thread and this counter remains unchanged.
   */
  public long getUiThreadEndFrameCount() {
    return uiThreadEndFrameCount;
  }

  private void notifyIfNoPendingTransactions() {
    if (!hasPendingTransactions()) {
      synchronized (pendingTransactionsSignal) {
        pendingTransactionsSignal.notifyAll();
      }
    }
  }

  private void updateInFlightSyncedFrameFlag() {
    platformHasInFlightSyncedFrame =
        hasActiveSwappedFrame || queuedFrame != null || frameStagedForTraversal;
    notifyIfNoPendingTransactions();
  }

  /**
   * Returns true if any UI-synced SurfaceControl transaction is still sealed, swapped, queued,
   * staged for Choreographer traversal, or waiting for SurfaceFlinger commit after {@code
   * applyTransactionOnDraw}. Safe to query from the raster thread.
   *
   * <p>Note: if {@code applyTransactionOnDraw} accepts a transaction just before the window pauses
   * without completing a draw, {@code pendingCommittedTransactions} remains positive so any
   * subsequent frame stays safely on the UI-synced path until the window draws or {@link
   * #dropTransactions()} resets state on detach.
   */
  public boolean hasPendingTransactions() {
    return unswappedSealedFrames.get() > 0
        || platformHasInFlightSyncedFrame
        || pendingCommittedTransactions.get() > 0;
  }

  /**
   * Registers a callback invoked once SurfaceFlinger commits {@code tx}. Extracted for unit testing
   * in environments where {@link AttachedSurfaceControl#applyTransactionOnDraw} is faked/mocked
   * without a backing SurfaceFlinger instance.
   */
  @VisibleForTesting
  @RequiresApi(API_LEVELS.API_34)
  boolean registerTransactionCommittedCallback(
      @NonNull SurfaceControl.Transaction tx, @NonNull Runnable onCommitted) {
    try {
      SurfaceControl.Transaction unused =
          tx.addTransactionCommittedListener(Runnable::run, onCommitted::run);
      return true;
    } catch (RuntimeException e) {
      Log.w(TAG, "Failed to register SurfaceControl.TransactionCommittedListener", e);
      return false;
    }
  }

  /**
   * Blocks the calling (raster) thread until all previously sealed/staged UI-synced SurfaceControl
   * transactions have drained, or until {@code timeoutMs} elapses (e.g. if the UI thread is paused
   * or tearing down).
   *
   * <p>Used on the 1-PV to 0-PV transition so the first post-cleanup 0-PV frame neither overtakes
   * the cleanup frame's {@code applyTransactionOnDraw} transaction nor schedules a new UI-synced
   * frame that would perpetuate a Choreographer staging chain during 60Hz/120Hz animations.
   */
  public boolean waitForPendingTransactions(long timeoutMs) {
    if (!hasPendingTransactions()) {
      return true;
    }
    final long deadlineNanos =
        System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    synchronized (pendingTransactionsSignal) {
      while (hasPendingTransactions()) {
        final long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
          return false;
        }
        final long millis = remainingNanos / 1_000_000L;
        final int nanos = (int) (remainingNanos % 1_000_000L);
        try {
          pendingTransactionsSignal.wait(millis, nanos);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return !hasPendingTransactions();
        }
      }
    }
    return true;
  }

  /* package */ int getActiveRenderTargetWidth(int viewId) {
    final FlutterMutatorView parent = platformViewParent.get(viewId);
    if (parent instanceof PlatformViewWrapper) {
      final PlatformViewWrapper wrapper = (PlatformViewWrapper) parent;
      if (wrapper.hasActiveRenderTarget()) {
        return wrapper.getRenderTargetWidth();
      }
    }
    return -1;
  }

  /* package */ int getActiveRenderTargetHeight(int viewId) {
    final FlutterMutatorView parent = platformViewParent.get(viewId);
    if (parent instanceof PlatformViewWrapper) {
      final PlatformViewWrapper wrapper = (PlatformViewWrapper) parent;
      if (wrapper.hasActiveRenderTarget()) {
        return wrapper.getRenderTargetHeight();
      }
    }
    return -1;
  }

  /**
   * Adopts a newly created {@link PlatformView} routed to HCPP during {@code createForTextureLayer}
   * (when a {@link SurfaceView} or {@link PlatformView#requiresNativeComposition()} is detected at
   * creation time).
   */
  /* package */ void adoptCreatedPlatformView(int viewId, @NonNull PlatformView platformView) {
    platformViews.put(viewId, platformView);
  }

  /**
   * Adopts a live {@link PlatformView} and its unified {@link PlatformViewWrapper} without
   * detaching either from the Android {@link FlutterView} window hierarchy during a dynamic {@code
   * TLHC -> HCPP} upgrade.
   */
  /* package */ void adoptUpgradingPlatformView(
      int viewId, @NonNull PlatformView platformView, @NonNull PlatformViewWrapper wrapper) {
    platformViews.put(viewId, platformView);
    platformViewParent.put(viewId, wrapper);
    wrapper.setTouchProcessor(androidTouchProcessor);
    wrapper.setOnDescendantFocusChangeListener(
        (view, hasFocus) -> {
          if (hasFocus) {
            if (platformViewsChannel != null) {
              platformViewsChannel.invokeViewFocused(viewId);
            }
          } else if (textInputPlugin != null) {
            textInputPlugin.clearPlatformViewClient(viewId);
          }
        });
  }

  @Override
  public boolean usesVirtualDisplay(int id) {
    return false;
  }

  public PlatformView createFlutterPlatformView(@NonNull PlatformViewCreationRequest request) {
    final PlatformViewFactory viewFactory = registry.getFactory(request.viewType);
    if (viewFactory == null) {
      throw new IllegalStateException(
          "Trying to create a platform view of unregistered type: " + request.viewType);
    }

    Object createParams = null;
    if (request.params != null) {
      createParams = viewFactory.getCreateArgsCodec().decodeMessage(request.params);
    }
    final PlatformView platformView = viewFactory.create(context, request.viewId, createParams);

    // Configure the view to match the requested layout direction.
    final View embeddedView = platformView.getView();
    if (embeddedView == null) {
      throw new IllegalStateException(
          "PlatformView#getView() returned null, but an Android view reference was expected.");
    }
    embeddedView.setLayoutDirection(request.direction);
    platformViews.put(request.viewId, platformView);
    maybeInvokeOnFlutterViewAttached(platformView);
    return platformView;
  }

  /**
   * Translates an original touch event to have the same locations as the ones that Flutter
   * calculates (because original + flutter's - original = flutter's).
   *
   * @param originalEvent The saved original input event.
   * @param pointerCoords The coordinates that Flutter thinks the touch is happening at.
   */
  private static void translateMotionEvent(
      MotionEvent originalEvent, PointerCoords[] pointerCoords) {
    if (pointerCoords.length < 1) {
      return;
    }

    float xOffset = pointerCoords[0].x - originalEvent.getX();
    float yOffset = pointerCoords[0].y - originalEvent.getY();

    originalEvent.offsetLocation(xOffset, yOffset);
  }

  @VisibleForTesting
  public MotionEvent toMotionEvent(float density, PlatformViewTouch touch) {
    MotionEventTracker.MotionEventId motionEventId =
        MotionEventTracker.MotionEventId.from(touch.motionEventId);
    MotionEvent trackedEvent = motionEventTracker.pop(motionEventId);

    // Pointer coordinates in the tracked events are global to FlutterView
    // The framework converts them to be local to a widget, given that
    // motion events operate on local coords, we need to replace these in the tracked
    // event with their local counterparts.
    // Compute this early so it can be used as input to translateNonVirtualDisplayMotionEvent.
    PointerCoords[] pointerCoords =
        parsePointerCoordsList(touch.rawPointerCoords, density)
            .toArray(new PointerCoords[touch.pointerCount]);

    if (trackedEvent != null) {
      // We have the original event, deliver it after offsetting as it will pass the verifiable
      // input check.
      translateMotionEvent(trackedEvent, pointerCoords);
      return trackedEvent;
    }
    // We don't have a reference to the original MotionEvent.
    // In this case we manually recreate a MotionEvent to be delivered. This MotionEvent
    // will fail the verifiable input check.
    PointerProperties[] pointerProperties =
        parsePointerPropertiesList(touch.rawPointerPropertiesList)
            .toArray(new PointerProperties[touch.pointerCount]);

    return MotionEvent.obtain(
        touch.downTime.longValue(),
        touch.eventTime.longValue(),
        touch.action,
        touch.pointerCount,
        pointerProperties,
        pointerCoords,
        touch.metaState,
        touch.buttonState,
        touch.xPrecision,
        touch.yPrecision,
        touch.deviceId,
        touch.edgeFlags,
        touch.source,
        touch.flags);
  }

  /**
   * Attaches this platform views controller to its input and output channels.
   *
   * @param context The base context that will be passed to embedded views created by this
   *     controller. This should be the context of the Activity hosting the Flutter application.
   * @param dartExecutor The dart execution context, which is used to set up a system channel.
   */
  public void attach(@Nullable Context context, @NonNull DartExecutor dartExecutor) {
    if (this.context != null) {
      throw new AssertionError(
          "A PlatformViewsController can only be attached to a single output target.\n"
              + "attach was called while the PlatformViewsController was already attached.");
    }
    this.context = context;
    // TODO(gmackall): We should remove this channel once hcpp has been enabled on existing
    //  platform view widgets.
    platformViewsChannel = new PlatformViewsChannel2(dartExecutor);
    platformViewsChannel.setPlatformViewsHandler(channelHandler);
  }

  /**
   * Detaches this platform views controller.
   *
   * <p>This is typically called when a Flutter applications moves to run in the background, or is
   * destroyed. After calling this the platform views controller will no longer listen to it's
   * previous messenger, and will not maintain references to the texture registry, context, and
   * messenger passed to the previous attach call.
   */
  @UiThread
  public void detach() {
    if (platformViewsChannel != null) {
      platformViewsChannel.setPlatformViewsHandler(null);
    }
    destroyOverlaySurface();
    platformViewsChannel = null;
    context = null;
  }

  /**
   * Attaches the controller to a {@link FlutterView}.
   *
   * <p>When {@link io.flutter.embedding.android.FlutterFragment} is used, this method is called
   * after the device rotates since the FlutterView is recreated after a rotation.
   */
  public void attachToView(@NonNull FlutterView newFlutterView) {
    flutterView = newFlutterView;
    // Add wrapper for platform views that are composed at the view hierarchy level.
    for (int index = 0; index < platformViewParent.size(); index++) {
      final FlutterMutatorView view = platformViewParent.valueAt(index);
      flutterView.addView(view);
    }
    // Notify platform views that they are now attached to a FlutterView.
    for (int index = 0; index < platformViews.size(); index++) {
      final PlatformView view = platformViews.valueAt(index);
      view.onFlutterViewAttached(flutterView);
    }
  }

  /**
   * Detaches the controller from {@link FlutterView}.
   *
   * <p>When {@link io.flutter.embedding.android.FlutterFragment} is used, this method is called
   * when the device rotates since the FlutterView is detached from the fragment. The next time the
   * fragment needs to be displayed, a new Flutter view is created, so attachToView is called again.
   */
  private void cleanupSurfaceViewListeners(@Nullable View current) {
    if (current == null) {
      return;
    }
    if (current instanceof SurfaceView) {
      final SurfaceView sv = (SurfaceView) current;
      final View.OnLayoutChangeListener layoutListener = pendingLayoutListeners.remove(sv);
      if (layoutListener != null) {
        sv.removeOnLayoutChangeListener(layoutListener);
      }
      final SurfaceHolder.Callback surfaceCallback = pendingSurfaceCallbacks.remove(sv);
      if (surfaceCallback != null && sv.getHolder() != null) {
        sv.getHolder().removeCallback(surfaceCallback);
      }
      return;
    }
    if (current instanceof ViewGroup) {
      final ViewGroup group = (ViewGroup) current;
      final int childCount = group.getChildCount();
      for (int i = 0; i < childCount; i++) {
        cleanupSurfaceViewListeners(group.getChildAt(i));
      }
    }
  }

  public void detachFromView() {
    // Remove wrapper for platform views that are composed at the view hierarchy level.
    for (int index = 0; index < platformViewParent.size(); index++) {
      final FlutterMutatorView view = platformViewParent.valueAt(index);
      cleanupSurfaceViewListeners(view);
      flutterView.removeView(view);
    }
    pendingLayoutListeners.clear();
    pendingSurfaceCallbacks.clear();

    destroyOverlaySurface();
    flutterView = null;

    if (Build.VERSION.SDK_INT >= API_LEVELS.API_34) {
      dropTransactions();
    }

    // Notify that the platform view have been detached from FlutterView.
    for (int index = 0; index < platformViews.size(); index++) {
      final PlatformView view = platformViews.valueAt(index);
      view.onFlutterViewDetached();
    }
  }

  private void maybeInvokeOnFlutterViewAttached(PlatformView view) {
    if (flutterView == null) {
      Log.i(TAG, "null flutterView");
      // There is currently no FlutterView that we are attached to.
      return;
    }
    view.onFlutterViewAttached(flutterView);
  }

  @Override
  public void attachAccessibilityBridge(@NonNull AccessibilityBridge accessibilityBridge) {
    accessibilityEventsDelegate.setAccessibilityBridge(accessibilityBridge);
  }

  @Override
  public void detachAccessibilityBridge() {
    accessibilityEventsDelegate.setAccessibilityBridge(null);
  }

  /**
   * Attaches this controller to a text input plugin.
   *
   * <p>While a text input plugin is available, the platform views controller interacts with it to
   * facilitate delegation of text input connections to platform views.
   *
   * <p>A platform views controller should be attached to a text input plugin whenever it is
   * possible for the Flutter framework to receive text input.
   */
  public void attachTextInputPlugin(@NonNull TextInputPlugin textInputPlugin) {
    this.textInputPlugin = textInputPlugin;
  }

  /** Detaches this controller from the currently attached text input plugin. */
  public void detachTextInputPlugin() {
    textInputPlugin = null;
  }

  public PlatformViewRegistry getRegistry() {
    return registry;
  }

  /**
   * Invoked when the {@link io.flutter.embedding.engine.FlutterEngine} that owns this {@link
   * PlatformViewsController} detaches from JNI.
   */
  public void onDetachedFromJNI() {
    disposeAllViews();
  }

  public void onPreEngineRestart() {
    disposeAllViews();
  }

  @Override
  @Nullable
  public View getPlatformViewById(int viewId) {
    final PlatformView platformView = platformViews.get(viewId);
    if (platformView == null) {
      return null;
    }
    return platformView.getView();
  }

  private void lockInputConnection(@NonNull VirtualDisplayController controller) {
    if (textInputPlugin == null) {
      return;
    }
    textInputPlugin.lockPlatformViewInputConnection();
    controller.onInputConnectionLocked();
  }

  private void unlockInputConnection(@NonNull VirtualDisplayController controller) {
    if (textInputPlugin == null) {
      return;
    }
    textInputPlugin.unlockPlatformViewInputConnection();
    controller.onInputConnectionUnlocked();
  }

  private static boolean validateDirection(int direction) {
    return direction == View.LAYOUT_DIRECTION_LTR || direction == View.LAYOUT_DIRECTION_RTL;
  }

  @SuppressWarnings("unchecked")
  private static List<PointerProperties> parsePointerPropertiesList(Object rawPropertiesList) {
    List<Object> rawProperties = (List<Object>) rawPropertiesList;
    List<PointerProperties> pointerProperties = new ArrayList<>();
    for (Object o : rawProperties) {
      pointerProperties.add(parsePointerProperties(o));
    }
    return pointerProperties;
  }

  @SuppressWarnings("unchecked")
  private static PointerProperties parsePointerProperties(Object rawProperties) {
    List<Object> propertiesList = (List<Object>) rawProperties;
    PointerProperties properties = new MotionEvent.PointerProperties();
    properties.id = (int) propertiesList.get(0);
    properties.toolType = (int) propertiesList.get(1);
    return properties;
  }

  @SuppressWarnings("unchecked")
  private static List<PointerCoords> parsePointerCoordsList(Object rawCoordsList, float density) {
    List<Object> rawCoords = (List<Object>) rawCoordsList;
    List<PointerCoords> pointerCoords = new ArrayList<>();
    for (Object o : rawCoords) {
      pointerCoords.add(parsePointerCoords(o, density));
    }
    return pointerCoords;
  }

  @SuppressWarnings("unchecked")
  private static PointerCoords parsePointerCoords(Object rawCoords, float density) {
    List<Object> coordsList = (List<Object>) rawCoords;
    PointerCoords coords = new MotionEvent.PointerCoords();
    coords.orientation = (float) (double) coordsList.get(0);
    coords.pressure = (float) (double) coordsList.get(1);
    coords.size = (float) (double) coordsList.get(2);
    coords.toolMajor = (float) ((double) coordsList.get(3) * density);
    coords.toolMinor = (float) ((double) coordsList.get(4) * density);
    coords.touchMajor = (float) ((double) coordsList.get(5) * density);
    coords.touchMinor = (float) ((double) coordsList.get(6) * density);
    coords.x = (float) ((double) coordsList.get(7) * density);
    coords.y = (float) ((double) coordsList.get(8) * density);
    return coords;
  }

  private float getDisplayDensity() {
    return context.getResources().getDisplayMetrics().density;
  }

  private int toPhysicalPixels(double logicalPixels) {
    return (int) Math.round(logicalPixels * getDisplayDensity());
  }

  private int toLogicalPixels(double physicalPixels, float displayDensity) {
    return (int) Math.round(physicalPixels / displayDensity);
  }

  private int toLogicalPixels(double physicalPixels) {
    return toLogicalPixels(physicalPixels, getDisplayDensity());
  }

  private void disposeAllViews() {
    while (platformViews.size() > 0) {
      final int viewId = platformViews.keyAt(0);
      // Dispose deletes the entry from platformViews and clears associated resources.
      channelHandler.dispose(viewId);
    }
  }

  /**
   * Disposes a single
   *
   * @param viewId the PlatformView ID.
   */
  @VisibleForTesting
  public void disposePlatformView(int viewId) {
    channelHandler.dispose(viewId);
  }

  @VisibleForTesting
  FlutterMutatorView getPlatformViewParent(int viewId) {
    return platformViewParent.get(viewId);
  }

  /**
   * Initializes a platform view and adds it to the view hierarchy.
   *
   * @param viewId The view ID. This member is not intended for public use, and is only visible for
   *     testing.
   */
  @VisibleForTesting
  boolean initializePlatformViewIfNeeded(int viewId) {
    final PlatformView platformView = platformViews.get(viewId);
    if (platformView == null) {
      return false;
    }
    if (platformViewParent.get(viewId) != null) {
      return true;
    }
    final View embeddedView = platformView.getView();
    if (embeddedView == null) {
      throw new IllegalStateException(
          "PlatformView#getView() returned null, but an Android view reference was expected.");
    }
    if (embeddedView.getParent() != null) {
      throw new IllegalStateException(
          "The Android view returned from PlatformView#getView() was already added to a parent"
              + " view.");
    }
    final FlutterMutatorView parentView =
        new FlutterMutatorView(
            context, context.getResources().getDisplayMetrics().density, androidTouchProcessor);

    parentView.setOnDescendantFocusChangeListener(
        (view, hasFocus) -> {
          if (hasFocus) {
            if (platformViewsChannel != null) {
              platformViewsChannel.invokeViewFocused(viewId);
            }
          } else if (textInputPlugin != null) {
            textInputPlugin.clearPlatformViewClient(viewId);
          }
        });

    platformViewParent.put(viewId, parentView);

    // Accessibility in the embedded view is initially disabled because if a Flutter app disabled
    // accessibility in the first frame, the embedding won't receive an update to disable
    // accessibility since the embedding never received an update to enable it.
    // The AccessibilityBridge keeps track of the accessibility nodes, and handles the deltas when
    // the framework sends a new a11y tree to the embedding.
    // To prevent races, the framework populate the SemanticsNode after the platform view has been
    // created.
    embeddedView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

    parentView.addView(embeddedView);
    flutterView.addView(parentView);
    return true;
  }

  public void attachToFlutterRenderer(@NonNull FlutterRenderer flutterRenderer) {
    androidTouchProcessor = new AndroidTouchProcessor(flutterRenderer, /*trackMotionEvents=*/ true);
  }

  /**
   * Called when a platform view id displayed in the current frame.
   *
   * @param viewId The ID of the platform view.
   * @param x The left position relative to {@code FlutterView}.
   * @param y The top position relative to {@code FlutterView}.
   * @param width The width of the platform view.
   * @param height The height of the platform view.
   * @param viewWidth The original width of the platform view before applying the mutator stack.
   * @param viewHeight The original height of the platform view before applying the mutator stack.
   * @param mutatorsStack The mutator stack. This member is not intended for public use, and is only
   *     visible for testing.
   */
  @RequiresApi(API_LEVELS.API_34)
  public void onDisplayPlatformView(
      int viewId,
      int x,
      int y,
      int width,
      int height,
      int viewWidth,
      int viewHeight,
      @NonNull FlutterMutatorsStack mutatorsStack) {
    flushCompletedTraversalIfNeeded();
    if (frameStagedForTraversal) {
      pendingViewOps.add(
          () ->
              applyDisplayPlatformView(
                  viewId, x, y, width, height, viewWidth, viewHeight, mutatorsStack));
      return;
    }
    applyDisplayPlatformView(viewId, x, y, width, height, viewWidth, viewHeight, mutatorsStack);
  }

  @RequiresApi(API_LEVELS.API_34)
  private void applyDisplayPlatformView(
      int viewId,
      int x,
      int y,
      int width,
      int height,
      int viewWidth,
      int viewHeight,
      @NonNull FlutterMutatorsStack mutatorsStack) {
    if (!initializePlatformViewIfNeeded(viewId)) {
      return;
    }

    final FlutterMutatorView parentView = platformViewParent.get(viewId);
    if (parentView instanceof PlatformViewWrapper) {
      final PlatformViewWrapper wrapper = (PlatformViewWrapper) parentView;
      if (wrapper.isCaptureToTextureMode()) {
        wrapper.switchToNativeViewComposition(getDisplayDensity());
      }
      if (wrapper.hasActiveRenderTarget()) {
        pendingRenderTargetReleases.add(wrapper);
      }
    }
    parentView.readyToDisplay(mutatorsStack, x, y, width, height);
    parentView.setVisibility(View.VISIBLE);
    parentView.bringToFront();

    final FrameLayout.LayoutParams layoutParams =
        new FrameLayout.LayoutParams(viewWidth, viewHeight, Gravity.LEFT | Gravity.TOP);
    final View view = platformViews.get(viewId).getView();
    if (view != null) {
      view.setLayoutParams(layoutParams);
      view.bringToFront();
      if (parentView.hasDescendantSurfaceView()) {
        applyClipsToSurfaceViewsInHierarchy(view, view, x, y, width, height, mutatorsStack);
      }
    }
  }

  @RequiresApi(API_LEVELS.API_34)
  private void applyClipsToSurfaceViewsInHierarchy(
      @Nullable View current,
      @NonNull View rootEmbeddedView,
      int pvX,
      int pvY,
      int pvWidth,
      int pvHeight,
      @NonNull FlutterMutatorsStack mutatorsStack) {
    if (current == null) {
      return;
    }
    if (current instanceof SurfaceView) {
      maybeApplyClipToSurfaceView(
          (SurfaceView) current, rootEmbeddedView, pvX, pvY, pvWidth, pvHeight, mutatorsStack);
      return;
    }
    if (current instanceof ViewGroup) {
      final ViewGroup group = (ViewGroup) current;
      final int childCount = group.getChildCount();
      for (int i = 0; i < childCount; i++) {
        applyClipsToSurfaceViewsInHierarchy(
            group.getChildAt(i), rootEmbeddedView, pvX, pvY, pvWidth, pvHeight, mutatorsStack);
      }
    }
  }

  static Rect computeLocalSurfaceViewCrop(
      @NonNull SurfaceView surfaceView,
      @NonNull View rootEmbeddedView,
      int pvX,
      int pvY,
      int pvWidth,
      int pvHeight,
      @NonNull FlutterMutatorsStack mutatorsStack) {
    final int svScreenLeft;
    final int svScreenTop;
    final int svWidth;
    final int svHeight;

    if (surfaceView == rootEmbeddedView) {
      svScreenLeft = pvX;
      svScreenTop = pvY;
      svWidth = surfaceView.getWidth() > 0 ? surfaceView.getWidth() : pvWidth;
      svHeight = surfaceView.getHeight() > 0 ? surfaceView.getHeight() : pvHeight;
    } else {
      // Accumulate offset and ancestor scroll positions up to rootEmbeddedView.
      // TODO(gmackall): Account for non-identity child scale/rotation transforms on intermediate
      // ViewGroups if plugins apply custom View matrices above a nested SurfaceView.
      int relX = 0;
      int relY = 0;
      View ancestor = surfaceView;
      while (ancestor != null && ancestor != rootEmbeddedView) {
        relX += ancestor.getLeft() + Math.round(ancestor.getTranslationX());
        relY += ancestor.getTop() + Math.round(ancestor.getTranslationY());
        final ViewParent parent = ancestor.getParent();
        if (parent instanceof View) {
          final View parentView = (View) parent;
          relX -= parentView.getScrollX();
          relY -= parentView.getScrollY();
          ancestor = parentView;
        } else {
          ancestor = null;
        }
      }
      svScreenLeft = pvX + relX;
      svScreenTop = pvY + relY;
      if (surfaceView.getWidth() > 0 && surfaceView.getHeight() > 0) {
        svWidth = surfaceView.getWidth();
        svHeight = surfaceView.getHeight();
      } else if (surfaceView.getLayoutParams() != null
          && surfaceView.getLayoutParams().width > 0
          && surfaceView.getLayoutParams().height > 0) {
        svWidth = surfaceView.getLayoutParams().width;
        svHeight = surfaceView.getLayoutParams().height;
      } else {
        svWidth = Math.max(0, pvWidth - relX);
        svHeight = Math.max(0, pvHeight - relY);
      }
    }

    final Rect screenRect =
        new Rect(svScreenLeft, svScreenTop, svScreenLeft + svWidth, svScreenTop + svHeight);
    final Rect pvScreenRect = new Rect(pvX, pvY, pvX + pvWidth, pvY + pvHeight);
    if (!screenRect.intersect(pvScreenRect)) {
      screenRect.setEmpty();
    }

    final List<Path> clippingPaths = mutatorsStack.getFinalClippingPaths();
    if (!screenRect.isEmpty() && clippingPaths != null && !clippingPaths.isEmpty()) {
      final RectF pathBounds = new RectF();
      for (Path path : clippingPaths) {
        path.computeBounds(pathBounds, true);
        final Rect pathRectInt = new Rect();
        pathBounds.roundOut(pathRectInt);
        if (!screenRect.intersect(pathRectInt)) {
          screenRect.setEmpty();
          break;
        }
      }
    }

    // SurfaceControl.setCrop expects coordinates relative to the SurfaceView's own buffer (0, 0).
    screenRect.offset(-svScreenLeft, -svScreenTop);
    final Rect viewClipBounds = surfaceView.getClipBounds();
    if (!screenRect.isEmpty() && viewClipBounds != null) {
      if (!screenRect.intersect(viewClipBounds)) {
        screenRect.setEmpty();
      }
    }
    if (screenRect.width() <= 0 || screenRect.height() <= 0) {
      screenRect.setEmpty();
    }
    return screenRect;
  }

  static float computeEffectiveSurfaceViewAlpha(
      @NonNull SurfaceView surfaceView,
      @NonNull View rootEmbeddedView,
      @NonNull Rect localCrop,
      @NonNull FlutterMutatorsStack mutatorsStack) {
    // SurfaceFlinger treats an empty crop Rect(0, 0, 0, 0) as uncropped. Force alpha to 0f when
    // the visible intersection is empty so fully clipped SurfaceViews do not remain visible.
    if (localCrop.isEmpty()) {
      return 0f;
    }
    float alpha = mutatorsStack.getFinalOpacity() * surfaceView.getAlpha();
    if (surfaceView != rootEmbeddedView) {
      ViewParent parent = surfaceView.getParent();
      while (parent instanceof View) {
        final View parentView = (View) parent;
        alpha *= parentView.getAlpha();
        if (parentView == rootEmbeddedView) {
          break;
        }
        parent = parentView.getParent();
      }
    }
    return Math.max(0f, Math.min(1f, alpha));
  }

  @RequiresApi(API_LEVELS.API_34)
  private void maybeApplyClipToSurfaceView(
      @NonNull SurfaceView surfaceView,
      @NonNull View rootEmbeddedView,
      int pvX,
      int pvY,
      int pvWidth,
      int pvHeight,
      @NonNull FlutterMutatorsStack mutatorsStack) {
    final Rect localCrop =
        computeLocalSurfaceViewCrop(
            surfaceView, rootEmbeddedView, pvX, pvY, pvWidth, pvHeight, mutatorsStack);
    final float effectiveAlpha =
        computeEffectiveSurfaceViewAlpha(surfaceView, rootEmbeddedView, localCrop, mutatorsStack);

    if (surfaceView != rootEmbeddedView
        && (!surfaceView.isLaidOut() || surfaceView.isLayoutRequested())
        && !pendingLayoutListeners.containsKey(surfaceView)) {
      final View.OnLayoutChangeListener layoutListener =
          new PendingSurfaceLayoutListener(
              this, surfaceView, rootEmbeddedView, pvX, pvY, pvWidth, pvHeight, mutatorsStack);
      pendingLayoutListeners.put(surfaceView, layoutListener);
      surfaceView.addOnLayoutChangeListener(layoutListener);
    }

    final SurfaceControl sc = surfaceView.getSurfaceControl();
    if (sc == null) {
      final SurfaceHolder holder = surfaceView.getHolder();
      if (holder == null || pendingSurfaceCallbacks.containsKey(surfaceView)) {
        return;
      }
      final SurfaceHolder.Callback cb =
          new PendingSurfaceClipCallback(
              this, surfaceView, rootEmbeddedView, pvX, pvY, pvWidth, pvHeight, mutatorsStack);
      pendingSurfaceCallbacks.put(surfaceView, cb);
      holder.addCallback(cb);
      return;
    }
    if (!sc.isValid()) {
      Log.i(
          TAG,
          "Skipping applying clip to SurfaceView: "
              + surfaceView.getId()
              + " because it has an invalid SurfaceControl.");
      return;
    }
    configureSurfaceViewTransaction(sc, effectiveAlpha, localCrop);
  }

  @RequiresApi(API_LEVELS.API_34)
  @VisibleForTesting
  void configureSurfaceViewTransaction(
      @NonNull SurfaceControl sc, float effectiveAlpha, @NonNull Rect localCrop) {
    platformTransaction().setAlpha(sc, effectiveAlpha).setCrop(sc, localCrop);
  }

  // Static listener holding only WeakReferences to avoid strong value->key cycles in WeakHashMap.
  @RequiresApi(API_LEVELS.API_34)
  private static final class PendingSurfaceLayoutListener implements View.OnLayoutChangeListener {
    private final WeakReference<PlatformViewsController2> controllerRef;
    private final WeakReference<SurfaceView> surfaceViewRef;
    private final WeakReference<View> rootEmbeddedViewRef;
    private final int pvX;
    private final int pvY;
    private final int pvWidth;
    private final int pvHeight;
    private final FlutterMutatorsStack mutatorsStack;

    PendingSurfaceLayoutListener(
        @NonNull PlatformViewsController2 controller,
        @NonNull SurfaceView surfaceView,
        @NonNull View rootEmbeddedView,
        int pvX,
        int pvY,
        int pvWidth,
        int pvHeight,
        @NonNull FlutterMutatorsStack mutatorsStack) {
      this.controllerRef = new WeakReference<>(controller);
      this.surfaceViewRef = new WeakReference<>(surfaceView);
      this.rootEmbeddedViewRef = new WeakReference<>(rootEmbeddedView);
      this.pvX = pvX;
      this.pvY = pvY;
      this.pvWidth = pvWidth;
      this.pvHeight = pvHeight;
      this.mutatorsStack = mutatorsStack;
    }

    @Override
    public void onLayoutChange(
        View v,
        int left,
        int top,
        int right,
        int bottom,
        int oldLeft,
        int oldTop,
        int oldRight,
        int oldBottom) {
      final SurfaceView surfaceView = surfaceViewRef.get();
      final PlatformViewsController2 controller = controllerRef.get();
      if (surfaceView != null) {
        surfaceView.removeOnLayoutChangeListener(this);
        if (controller != null) {
          controller.pendingLayoutListeners.remove(surfaceView);
        }
      }
      final View rootEmbeddedView = rootEmbeddedViewRef.get();
      if (controller == null || surfaceView == null || rootEmbeddedView == null) {
        return;
      }
      final SurfaceControl laidOutSc = surfaceView.getSurfaceControl();
      if (laidOutSc == null || !laidOutSc.isValid()) {
        return;
      }
      final Rect updatedCrop =
          computeLocalSurfaceViewCrop(
              surfaceView, rootEmbeddedView, pvX, pvY, pvWidth, pvHeight, mutatorsStack);
      final float updatedAlpha =
          computeEffectiveSurfaceViewAlpha(
              surfaceView, rootEmbeddedView, updatedCrop, mutatorsStack);
      final AttachedSurfaceControl rsc =
          controller.flutterView == null ? null : controller.flutterView.getRootSurfaceControl();
      if (rsc != null) {
        final SurfaceControl.Transaction layoutTx = new SurfaceControl.Transaction();
        layoutTx.setAlpha(laidOutSc, updatedAlpha).setCrop(laidOutSc, updatedCrop);
        // Do NOT close `layoutTx` when `applyTransactionOnDraw` accepts it; ViewRootImpl's
        // FrameDrawingCallback may merge `layoutTx` asynchronously on RenderThread.
        if (!rsc.applyTransactionOnDraw(layoutTx)) {
          try {
            layoutTx.apply();
          } finally {
            layoutTx.close();
          }
        }
      } else {
        controller
            .platformTransaction()
            .setAlpha(laidOutSc, updatedAlpha)
            .setCrop(laidOutSc, updatedCrop);
        if (controller.flutterJNI != null) {
          controller.flutterJNI.scheduleFrame();
        }
      }
    }
  }

  // Static callback holding only WeakReferences to avoid strong value->key cycles in WeakHashMap.
  @RequiresApi(API_LEVELS.API_34)
  private static final class PendingSurfaceClipCallback implements SurfaceHolder.Callback {
    private final WeakReference<PlatformViewsController2> controllerRef;
    private final WeakReference<SurfaceView> surfaceViewRef;
    private final WeakReference<View> rootEmbeddedViewRef;
    private final int pvX;
    private final int pvY;
    private final int pvWidth;
    private final int pvHeight;
    private final FlutterMutatorsStack mutatorsStack;

    PendingSurfaceClipCallback(
        @NonNull PlatformViewsController2 controller,
        @NonNull SurfaceView surfaceView,
        @NonNull View rootEmbeddedView,
        int pvX,
        int pvY,
        int pvWidth,
        int pvHeight,
        @NonNull FlutterMutatorsStack mutatorsStack) {
      this.controllerRef = new WeakReference<>(controller);
      this.surfaceViewRef = new WeakReference<>(surfaceView);
      this.rootEmbeddedViewRef = new WeakReference<>(rootEmbeddedView);
      this.pvX = pvX;
      this.pvY = pvY;
      this.pvWidth = pvWidth;
      this.pvHeight = pvHeight;
      this.mutatorsStack = mutatorsStack;
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
      final SurfaceView surfaceView = surfaceViewRef.get();
      final PlatformViewsController2 controller = controllerRef.get();
      final View rootEmbeddedView = rootEmbeddedViewRef.get();
      if (controller != null && surfaceView != null && rootEmbeddedView != null) {
        final SurfaceControl surfaceControl = surfaceView.getSurfaceControl();
        if (surfaceControl != null && surfaceControl.isValid()) {
          final Rect localCrop =
              computeLocalSurfaceViewCrop(
                  surfaceView, rootEmbeddedView, pvX, pvY, pvWidth, pvHeight, mutatorsStack);
          final float effectiveAlpha =
              computeEffectiveSurfaceViewAlpha(
                  surfaceView, rootEmbeddedView, localCrop, mutatorsStack);
          controller.configureSurfaceViewTransaction(surfaceControl, effectiveAlpha, localCrop);
        } else {
          Log.i(
              TAG,
              "Failed to apply clipping to SurfaceView: "
                  + surfaceView.getId()
                  + " - the SurfaceControl was null or invalid during surfaceCreated callback.");
        }
        // Because this transaction is created outside of the frame timing, we can't
        // guarantee there is another frame coming (if, say, the app has a static
        // layout). So we schedule one to ensure the crop is rendered properly.
        // See https://github.com/flutter/flutter/issues/175546.
        if (controller.flutterJNI != null) {
          controller.flutterJNI.scheduleFrame();
        }
      }
      if (surfaceView != null && controller != null) {
        controller.pendingSurfaceCallbacks.remove(surfaceView);
      }
      holder.removeCallback(this);
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
      final SurfaceView surfaceView = surfaceViewRef.get();
      final PlatformViewsController2 controller = controllerRef.get();
      if (surfaceView != null && controller != null) {
        controller.pendingSurfaceCallbacks.remove(surfaceView);
      }
      holder.removeCallback(this);
    }
  }

  public void hidePlatformView(int viewId) {
    if (Build.VERSION.SDK_INT >= API_LEVELS.API_34) {
      flushCompletedTraversalIfNeeded();
    }
    if (frameStagedForTraversal) {
      pendingViewOps.add(() -> applyHidePlatformView(viewId));
      return;
    }
    applyHidePlatformView(viewId);
  }

  private void applyHidePlatformView(int viewId) {
    if (!initializePlatformViewIfNeeded(viewId)) {
      return;
    }

    final FlutterMutatorView parentView = platformViewParent.get(viewId);
    parentView.setVisibility(View.GONE);
  }

  @UiThread
  @RequiresApi(API_LEVELS.API_34)
  public void onEndFrame() {
    flushCompletedTraversalIfNeeded();
    if (frameStagedForTraversal) {
      if (queuedFrame == null) {
        queuedFrame = new QueuedFrame();
      }
      queuedFrame.viewOps.addAll(pendingViewOps);
      pendingViewOps.clear();
      if (activePlatformTransaction != null) {
        if (queuedFrame.platformTransaction == null) {
          queuedFrame.platformTransaction = activePlatformTransaction;
        } else {
          queuedFrame.platformTransaction =
              queuedFrame.platformTransaction.merge(activePlatformTransaction);
          activePlatformTransaction.close();
        }
        activePlatformTransaction = null;
      }
      queuedFrame.rasterTransactions.addAll(activeRasterTransactions);
      activeRasterTransactions.clear();
      queuedFrame.renderTargetReleases.addAll(pendingRenderTargetReleases);
      pendingRenderTargetReleases.clear();
      hasActiveSwappedFrame = false;
      updateInFlightSyncedFrameFlag();
      return;
    }

    commitActiveFrame();
  }

  private void releaseStagedRenderTargets() {
    if (stagedRenderTargetReleases.isEmpty()) {
      return;
    }
    for (PlatformViewWrapper wrapper : stagedRenderTargetReleases) {
      wrapper.release();
    }
    stagedRenderTargetReleases.clear();
  }

  @UiThread
  @RequiresApi(API_LEVELS.API_34)
  private void commitActiveFrame() {
    hasActiveSwappedFrame = false;
    final SurfaceControl.Transaction platformTx = activePlatformTransaction;
    activePlatformTransaction = null;

    // Use a fresh destination so cleanup cannot close a raster input still in native use.
    // Bypass newTransaction(), which tests may override to return an existing input.
    SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
    if (platformTx != null) {
      tx = tx.merge(platformTx);
      platformTx.close();
    }
    for (SurfaceControl.Transaction rasterTx : activeRasterTransactions) {
      tx = tx.merge(rasterTx);
    }
    activeRasterTransactions.clear();

    // The view or its window may detach before this posted frame runs.
    final AttachedSurfaceControl rootSurfaceControl =
        flutterView == null ? null : flutterView.getRootSurfaceControl();
    if (rootSurfaceControl == null) {
      // Release the unapplied transaction and its owned fence FDs.
      tx.close();
      updateInFlightSyncedFrameFlag();
      return;
    }

    final int expectedGeneration = commitGeneration.get();
    final AtomicBoolean commitCompleted = new AtomicBoolean(false);
    final Runnable completeCommit =
        () -> {
          if (commitCompleted.compareAndSet(false, true)
              && commitGeneration.get() == expectedGeneration) {
            if (pendingCommittedTransactions.decrementAndGet() <= 0) {
              notifyIfNoPendingTransactions();
            }
          }
        };
    pendingCommittedTransactions.incrementAndGet();
    final boolean registeredCommitCallback =
        registerTransactionCommittedCallback(tx, completeCommit);
    if (!registeredCommitCallback) {
      completeCommit.run();
    }

    flutterView.invalidate();
    if (flutterView.willNotDraw() && flutterView.getChildCount() > 0) {
      final View firstChild = flutterView.getChildAt(0);
      if (firstChild != null) {
        firstChild.invalidate();
      }
    }
    if (!rootSurfaceControl.applyTransactionOnDraw(tx)) {
      if (registeredCommitCallback) {
        completeCommit.run();
      }
      tx.close();
    }
    uiThreadEndFrameCount++;
    stagedRenderTargetReleases.addAll(pendingRenderTargetReleases);
    pendingRenderTargetReleases.clear();
    // Defer the next frame until this Choreographer traversal finishes so its View mutations do
    // not overwrite this frame's layout before draw. If the traversal skips drawing (e.g. a
    // pre-draw listener cancels draw), the next commit merges into ViewRootImpl's still-pending
    // transaction (falling back to mailbox replacement).
    if (shouldDeferUntilNextTraversal()) {
      frameStagedForTraversal = true;
      choreographerCallbackFired = false;
      Choreographer.getInstance().postFrameCallback(traversalFrameCallback);
    } else {
      releaseStagedRenderTargets();
    }
    updateInFlightSyncedFrameFlag();
  }

  @VisibleForTesting
  boolean shouldDeferUntilNextTraversal() {
    return flutterView != null && flutterView.isAttachedToWindow();
  }

  /**
   * Flushes a completed Choreographer traversal if the native ALooper callback for the next frame
   * runs inside {@code MessageQueue.nativePollOnce()} before the Java handler message posted by
   * {@link #traversalFrameCallback} is dispatched.
   */
  @UiThread
  @RequiresApi(API_LEVELS.API_34)
  private void flushCompletedTraversalIfNeeded() {
    if (frameStagedForTraversal && choreographerCallbackFired) {
      onTraversalComplete();
    }
  }

  @UiThread
  @RequiresApi(API_LEVELS.API_34)
  private void onTraversalComplete() {
    frameStagedForTraversal = false;
    choreographerCallbackFired = false;
    if (mainHandler != null) {
      mainHandler.removeCallbacks(traversalCompleteRunnable);
    }
    releaseStagedRenderTargets();
    if (queuedFrame == null) {
      updateInFlightSyncedFrameFlag();
      return;
    }
    final QueuedFrame next = queuedFrame;
    queuedFrame = null;

    final SurfaceControl.Transaction savedPendingPlatformTx = pendingPlatformTransaction;
    pendingPlatformTransaction = next.platformTransaction;

    for (Runnable op : next.viewOps) {
      op.run();
    }
    pendingRenderTargetReleases.addAll(next.renderTargetReleases);

    activePlatformTransaction = pendingPlatformTransaction;
    pendingPlatformTransaction = savedPendingPlatformTx;
    activeRasterTransactions.addAll(next.rasterTransactions);

    commitActiveFrame();
  }

  @UiThread
  @RequiresApi(API_LEVELS.API_34)
  public void swapTransactions() {
    flushCompletedTraversalIfNeeded();
    // Do not close discarded raster inputs; native producers may still use them.
    activeRasterTransactions.clear();
    synchronized (transactionLock) {
      final ArrayList<SurfaceControl.Transaction> frameTransactions =
          pendingRasterFrames.pollFirst();
      if (frameTransactions != null) {
        activeRasterTransactions.addAll(frameTransactions);
        hasActiveSwappedFrame = true;
        platformHasInFlightSyncedFrame = true;
        unswappedSealedFrames.set(pendingRasterFrames.size());
      } else {
        hasActiveSwappedFrame = false;
        unswappedSealedFrames.set(pendingRasterFrames.size());
        updateInFlightSyncedFrameFlag();
      }
    }

    if (activePlatformTransaction != null) {
      // Discard a platform transaction whose onEndFrame() never ran.
      activePlatformTransaction.close();
    }
    activePlatformTransaction = pendingPlatformTransaction;
    pendingPlatformTransaction = null;
  }

  /**
   * Discards the transaction state of the view that just detached.
   *
   * <p>Platform transactions are closed here: they only touch SurfaceControls that this detach
   * invalidates, including the overlay that {@link #destroyOverlaySurface()} just released, and
   * nothing outside the platform thread references them. Raster transactions are dropped without
   * closing them, because their native producers may still be writing into them.
   */
  @UiThread
  @RequiresApi(API_LEVELS.API_34)
  private void dropTransactions() {
    frameStagedForTraversal = false;
    choreographerCallbackFired = false;
    hasActiveSwappedFrame = false;
    pendingViewOps.clear();
    Choreographer.getInstance().removeFrameCallback(traversalFrameCallback);
    if (mainHandler != null) {
      mainHandler.removeCallbacks(traversalCompleteRunnable);
    }
    if (queuedFrame != null) {
      if (queuedFrame.platformTransaction != null) {
        queuedFrame.platformTransaction.close();
      }
      for (PlatformViewWrapper wrapper : queuedFrame.renderTargetReleases) {
        wrapper.release();
      }
      queuedFrame = null;
    }
    for (PlatformViewWrapper wrapper : pendingRenderTargetReleases) {
      wrapper.release();
    }
    pendingRenderTargetReleases.clear();
    releaseStagedRenderTargets();
    activeRasterTransactions.clear();
    synchronized (transactionLock) {
      // If platform tasks posted before detach run swapTransactions() after an immediate
      // re-attach seals new frames, batches can shift by one until those stale tasks drain.
      pendingRasterFrames.clear();
      pendingRasterTransactions.clear();
      unswappedSealedFrames.set(0);
    }
    commitGeneration.incrementAndGet();
    pendingCommittedTransactions.set(0);
    updateInFlightSyncedFrameFlag();

    if (activePlatformTransaction != null) {
      activePlatformTransaction.close();
      activePlatformTransaction = null;
    }
    if (pendingPlatformTransaction != null) {
      pendingPlatformTransaction.close();
      pendingPlatformTransaction = null;
    }
  }

  @UiThread
  @RequiresApi(API_LEVELS.API_34)
  private SurfaceControl.Transaction platformTransaction() {
    if (pendingPlatformTransaction == null) {
      pendingPlatformTransaction = newTransaction();
    }
    return pendingPlatformTransaction;
  }

  /**
   * Allocates a transaction for the raster thread without making it visible to the platform thread.
   *
   * <p>The native caller continues to write into the transaction after this returns (setting the
   * buffer and the completion callback). Publishing it here would let {@link #swapTransactions()}
   * and {@link #onEndFrame()} merge and close it while those writes are in flight. The caller must
   * call {@link #publishTransaction} once it is done writing.
   *
   * <p>This is only called when HCPP is active, which is gated on API 34+ in the engine (both
   * {@code kMinAPILevelHCPP} and the AHB swapchain's {@code CreateTransactionCB} require API 34+,
   * where {@code ASurfaceTransaction_fromJava} was introduced).
   */
  // Called from the raster thread through FlutterJNI.
  @RequiresApi(API_LEVELS.API_34)
  public SurfaceControl.Transaction createUnpublishedTransaction() {
    return newTransaction();
  }

  /**
   * Publishes a transaction returned by {@link #createUnpublishedTransaction()} now that the caller
   * has finished writing into it.
   *
   * <p>Like {@link #createUnpublishedTransaction()}, this is only reachable on API 34+ because HCPP
   * and the native transaction callback are gated on API 34+.
   */
  // Called from the raster thread through FlutterJNI.
  @RequiresApi(API_LEVELS.API_34)
  public void publishTransaction(SurfaceControl.Transaction tx) {
    if (tx == null) {
      return;
    }
    synchronized (transactionLock) {
      pendingRasterTransactions.add(tx);
    }
  }

  /**
   * Seals the transactions published through {@link #publishTransaction} during the current raster
   * frame into a single batch for the next {@link #swapTransactions()} call.
   *
   * <p>The raster thread may begin or complete the next frame before the platform thread runs the
   * task posted by {@code SubmitFlutterView}. Queuing one batch per raster frame keeps {@link
   * #swapTransactions()} from draining transactions that belong to a later frame.
   */
  // Called from the raster thread through FlutterJNI.
  @RequiresApi(API_LEVELS.API_34)
  public void sealFrameTransactions() {
    synchronized (transactionLock) {
      pendingRasterFrames.addLast(new ArrayList<>(pendingRasterTransactions));
      pendingRasterTransactions.clear();
      unswappedSealedFrames.set(pendingRasterFrames.size());
    }
  }

  /** Allocates a transaction so tests can spy on the instance retained by the controller. */
  @VisibleForTesting
  @RequiresApi(API_LEVELS.API_34)
  SurfaceControl.Transaction newTransaction() {
    return new SurfaceControl.Transaction();
  }

  @RequiresApi(API_LEVELS.API_34)
  public FlutterOverlaySurface createOverlaySurface() {
    if (overlayerSurface == null) {
      final SurfaceControl.Builder surfaceControlBuilder = new SurfaceControl.Builder();
      surfaceControlBuilder.setBufferSize(flutterView.getWidth(), flutterView.getHeight());
      surfaceControlBuilder.setFormat(PixelFormat.RGBA_8888);
      surfaceControlBuilder.setName("Flutter Overlay Surface");
      surfaceControlBuilder.setOpaque(false);
      surfaceControlBuilder.setHidden(false);
      final SurfaceControl surfaceControl = surfaceControlBuilder.build();
      final SurfaceControl.Transaction tx =
          flutterView.getRootSurfaceControl().buildReparentTransaction(surfaceControl);
      tx.setLayer(surfaceControl, 1000);
      tx.apply();
      overlayerSurface = new Surface(surfaceControl);
      overlaySurfaceControl = surfaceControl;
    }

    return new FlutterOverlaySurface(0, overlayerSurface);
  }

  public void destroyOverlaySurface() {
    if (overlayerSurface != null) {
      overlayerSurface.release();
      overlayerSurface = null;
      overlaySurfaceControl = null;
    }
  }

  @RequiresApi(API_LEVELS.API_34)
  public void showOverlaySurface() {
    flushCompletedTraversalIfNeeded();
    if (frameStagedForTraversal) {
      pendingViewOps.add(this::applyShowOverlaySurface);
      return;
    }
    applyShowOverlaySurface();
  }

  @RequiresApi(API_LEVELS.API_34)
  private void applyShowOverlaySurface() {
    if (overlaySurfaceControl == null) {
      return;
    }
    SurfaceControl.Transaction tx = platformTransaction();
    tx.setVisibility(overlaySurfaceControl, /*visible=*/ true);
  }

  @RequiresApi(API_LEVELS.API_34)
  public void hideOverlaySurface() {
    flushCompletedTraversalIfNeeded();
    if (frameStagedForTraversal) {
      pendingViewOps.add(this::applyHideOverlaySurface);
      return;
    }
    applyHideOverlaySurface();
  }

  @RequiresApi(API_LEVELS.API_34)
  private void applyHideOverlaySurface() {
    if (overlaySurfaceControl == null) {
      return;
    }
    SurfaceControl.Transaction tx = platformTransaction();
    tx.setVisibility(overlaySurfaceControl, /*visible=*/ false);
  }

  public boolean isHcppEnabled() {
    if (flutterJNI == null) {
      return false;
    }
    return flutterJNI.IsSurfaceControlEnabled();
  }

  //// Message Handler ///////

  final PlatformViewsChannel2.PlatformViewsHandler channelHandler =
      new PlatformViewsChannel2.PlatformViewsHandler() {

        @Override
        public void createPlatformView(@NonNull PlatformViewCreationRequest request) {
          createFlutterPlatformView(request);
        }

        @Override
        public void dispose(int viewId) {
          final PlatformView platformView = platformViews.get(viewId);
          if (platformView == null) {
            Log.e(TAG, "Disposing unknown platform view with id: " + viewId);
            return;
          }
          if (platformView.getView() != null) {
            final View embeddedView = platformView.getView();
            cleanupSurfaceViewListeners(embeddedView);
            final ViewGroup pvParent = (ViewGroup) embeddedView.getParent();
            if (pvParent != null) {
              // Eagerly remove the embedded view from the PlatformViewWrapper.
              // Without this call, we see some crashes because removing the view
              // is used as a signal to stop processing.
              pvParent.removeView(embeddedView);
            }
          }
          platformViews.remove(viewId);
          try {
            platformView.dispose();
          } catch (RuntimeException exception) {
            Log.e(TAG, "Disposing platform view threw an exception", exception);
          }

          // The platform view is displayed using a PlatformViewLayer.
          final FlutterMutatorView parentView = platformViewParent.get(viewId);
          if (parentView != null) {
            if (parentView instanceof PlatformViewWrapper) {
              final PlatformViewWrapper wrapper = (PlatformViewWrapper) parentView;
              pendingRenderTargetReleases.remove(wrapper);
              stagedRenderTargetReleases.remove(wrapper);
              wrapper.release();
            }
            parentView.removeAllViews();
            parentView.unsetOnDescendantFocusChangeListener();

            final ViewGroup mutatorViewParent = (ViewGroup) parentView.getParent();
            if (mutatorViewParent != null) {
              mutatorViewParent.removeView(parentView);
            }
            platformViewParent.remove(viewId);
          }
        }

        @Override
        public void onTouch(@NonNull PlatformViewTouch touch) {
          final int viewId = touch.viewId;
          final float density = context.getResources().getDisplayMetrics().density;

          final PlatformView platformView = platformViews.get(viewId);
          if (platformView == null) {
            Log.e(TAG, "Sending touch to an unknown view with id: " + viewId);
            return;
          }
          final View view = platformView.getView();
          if (view == null) {
            Log.e(TAG, "Sending touch to a null view with id: " + viewId);
            return;
          }
          final MotionEvent event = toMotionEvent(density, touch);
          view.dispatchTouchEvent(event);
        }

        @Override
        public void setDirection(int viewId, int direction) {
          final PlatformView platformView = platformViews.get(viewId);
          if (platformView == null) {
            Log.e(TAG, "Setting direction to an unknown view with id: " + viewId);
            return;
          }
          View embeddedView = platformView.getView();
          if (embeddedView == null) {
            Log.e(TAG, "Setting direction to a null view with id: " + viewId);
            return;
          }
          embeddedView.setLayoutDirection(direction);
        }

        @Override
        public void clearFocus(int viewId) {
          final PlatformView platformView = platformViews.get(viewId);
          if (platformView == null) {
            Log.e(TAG, "Clearing focus on an unknown view with id: " + viewId);
            return;
          }
          View embeddedView = platformView.getView();
          if (embeddedView == null) {
            Log.e(TAG, "Clearing focus on a null view with id: " + viewId);
            return;
          }
          embeddedView.clearFocus();
        }

        @Override
        public void onRejectGesture(int viewId, long gestureId) {
          final MotionEvent event =
              motionEventTracker.peek(MotionEventTracker.MotionEventId.from(gestureId));
          if (event == null) {
            return;
          }
          final FlutterMutatorView parentView = platformViewParent.get(viewId);
          if (parentView != null) {
            parentView.onFlutterWonGesture(event.getDownTime());
          }
        }

        @Override
        public boolean isSurfaceControlEnabled() {
          return isHcppEnabled();
        }
      };
}
