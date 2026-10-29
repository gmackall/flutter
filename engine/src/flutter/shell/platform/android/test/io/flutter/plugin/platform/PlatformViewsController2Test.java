// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.platform;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.app.Presentation;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.util.SparseArray;
import android.view.AttachedSurfaceControl;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.flutter.embedding.android.FlutterImageView;
import io.flutter.embedding.android.FlutterSurfaceView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.android.MotionEventTracker;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.mutatorsstack.FlutterMutatorView;
import io.flutter.embedding.engine.mutatorsstack.FlutterMutatorsStack;
import io.flutter.embedding.engine.renderer.FlutterRenderer;
import io.flutter.embedding.engine.systemchannels.AccessibilityChannel;
import io.flutter.embedding.engine.systemchannels.MouseCursorChannel;
import io.flutter.embedding.engine.systemchannels.PlatformViewCreationRequest;
import io.flutter.embedding.engine.systemchannels.PlatformViewTouch;
import io.flutter.embedding.engine.systemchannels.ScribeChannel;
import io.flutter.embedding.engine.systemchannels.SettingsChannel;
import io.flutter.embedding.engine.systemchannels.TextInputChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.StandardMessageCodec;
import io.flutter.plugin.common.StandardMethodCodec;
import io.flutter.plugin.localization.LocalizationPlugin;
import io.flutter.view.TextureRegistry;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSurfaceView;

@RunWith(AndroidJUnit4.class)
public class PlatformViewsController2Test {
  // An implementation of PlatformView that counts invocations of its lifecycle callbacks.
  class CountingPlatformView implements PlatformView {
    static final String VIEW_TYPE_ID = "CountingPlatformView";
    private View view;

    public CountingPlatformView(Context context) {
      view = new SurfaceView(context);
    }

    public int disposeCalls = 0;
    public int attachCalls = 0;
    public int detachCalls = 0;

    @Override
    public void dispose() {
      // We have been removed from the view hierarhy before the call to dispose.
      assertNull(view.getParent());
      disposeCalls++;
    }

    @Override
    public View getView() {
      return view;
    }

    @Override
    public void onFlutterViewAttached(View flutterView) {
      attachCalls++;
    }

    @Override
    public void onFlutterViewDetached() {
      detachCalls++;
    }
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void itRemovesPlatformViewBeforeDiposeIsCalled() {
    PlatformViewRegistryImpl registryImpl = new PlatformViewRegistryImpl();
    PlatformViewsController2 PlatformViewsController2 = new PlatformViewsController2();
    PlatformViewsController2.setRegistry(registryImpl);
    FlutterJNI jni = new FlutterJNI();
    attach(jni, PlatformViewsController2);
    // Get the platform view registry.
    PlatformViewRegistry registry = PlatformViewsController2.getRegistry();

    // Register a factory for our platform view.
    registry.registerViewFactory(
        CountingPlatformView.VIEW_TYPE_ID,
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @Override
          public PlatformView create(Context context, int viewId, Object args) {
            return new CountingPlatformView(context);
          }
        });

    // Create the platform view.
    int viewId = 0;

    final PlatformViewCreationRequest request =
        PlatformViewCreationRequest.createHCPPRequest(
            viewId, CountingPlatformView.VIEW_TYPE_ID, View.LAYOUT_DIRECTION_LTR, null);
    PlatformView pView = PlatformViewsController2.createFlutterPlatformView(request);
    assertTrue(pView instanceof CountingPlatformView);
    CountingPlatformView cpv = (CountingPlatformView) pView;

    PlatformViewsController2.disposePlatformView(viewId);
    assertEquals(1, cpv.disposeCalls);
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void itNotifiesPlatformViewsOfEngineAttachmentAndDetachment() {
    PlatformViewRegistryImpl registryImpl = new PlatformViewRegistryImpl();
    PlatformViewsController2 PlatformViewsController2 = new PlatformViewsController2();
    PlatformViewsController2.setRegistry(registryImpl);
    FlutterJNI jni = new FlutterJNI();
    attach(jni, PlatformViewsController2);
    // Get the platform view registry.
    PlatformViewRegistry registry = PlatformViewsController2.getRegistry();

    // Register a factory for our platform view.
    registry.registerViewFactory(
        CountingPlatformView.VIEW_TYPE_ID,
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @Override
          public PlatformView create(Context context, int viewId, Object args) {
            return new CountingPlatformView(context);
          }
        });

    // Create the platform view.
    int viewId = 0;
    final PlatformViewCreationRequest request =
        PlatformViewCreationRequest.createHCPPRequest(
            viewId, CountingPlatformView.VIEW_TYPE_ID, View.LAYOUT_DIRECTION_LTR, null);

    PlatformView pView = PlatformViewsController2.createFlutterPlatformView(request);
    assertTrue(pView instanceof CountingPlatformView);
    CountingPlatformView cpv = (CountingPlatformView) pView;
    assertEquals(1, cpv.attachCalls);
    assertEquals(0, cpv.detachCalls);
    assertEquals(0, cpv.disposeCalls);
    PlatformViewsController2.detachFromView();
    assertEquals(1, cpv.attachCalls);
    assertEquals(1, cpv.detachCalls);
    assertEquals(0, cpv.disposeCalls);
    PlatformViewsController2.disposePlatformView(viewId);
  }

  @Test
  public void itUsesActionEventTypeFromFrameworkEventAsActionChanged() {
    MotionEventTracker motionEventTracker = MotionEventTracker.getInstance();
    PlatformViewRegistryImpl registryImpl = new PlatformViewRegistryImpl();
    PlatformViewsController2 PlatformViewsController2 = new PlatformViewsController2();
    PlatformViewsController2.setRegistry(registryImpl);

    MotionEvent original =
        MotionEvent.obtain(
            10, // downTime
            10, // eventTime
            261, // action
            0, // x
            0, // y
            0 // metaState
            );

    MotionEventTracker.MotionEventId motionEventId = motionEventTracker.track(original);

    PlatformViewTouch frameWorkTouch =
        new PlatformViewTouch(
            0, // viewId
            original.getDownTime(),
            original.getEventTime(),
            0, // action
            1, // pointerCount
            Arrays.asList(Arrays.asList(0, 0)), // pointer properties
            Arrays.asList(Arrays.asList(0., 1., 2., 3., 4., 5., 6., 7., 8.)), // pointer coords
            original.getMetaState(),
            original.getButtonState(),
            original.getXPrecision(),
            original.getYPrecision(),
            original.getDeviceId(),
            original.getEdgeFlags(),
            original.getSource(),
            original.getFlags(),
            motionEventId.getId());
    MotionEvent resolvedEvent =
        PlatformViewsController2.toMotionEvent(
            1, // density
            frameWorkTouch);
    assertEquals(resolvedEvent.getAction(), original.getAction());
    assertNotEquals(resolvedEvent.getAction(), frameWorkTouch.action);
  }

  private MotionEvent makePlatformViewTouchAndInvokeToMotionEvent(
      PlatformViewsController2 PlatformViewsController2,
      MotionEventTracker motionEventTracker,
      MotionEvent original,
      boolean usingVirtualDisplays) {
    MotionEventTracker.MotionEventId motionEventId = motionEventTracker.track(original);

    // Construct a PlatformViewTouch.rawPointerPropertiesList by doing the inverse of
    // PlatformViewsController2.parsePointerPropertiesList.
    List<List<Integer>> pointerProperties =
        Arrays.asList(Arrays.asList(original.getPointerId(0), original.getToolType(0)));
    // Construct a PlatformViewTouch.rawPointerCoords by doing the inverse of
    // PlatformViewsController2.parsePointerCoordsList.
    List<List<Double>> pointerCoordinates =
        Arrays.asList(
            Arrays.asList(
                (double) original.getOrientation(),
                (double) original.getPressure(),
                (double) original.getSize(),
                (double) original.getToolMajor(),
                (double) original.getToolMinor(),
                (double) original.getTouchMajor(),
                (double) original.getTouchMinor(),
                (double) original.getX(),
                (double) original.getY()));
    // Make a platform view touch from the motion event.
    PlatformViewTouch frameWorkTouchNonVd =
        new PlatformViewTouch(
            0, // viewId
            original.getDownTime(),
            original.getEventTime(),
            original.getAction(),
            1, // pointerCount
            pointerProperties, // pointer properties
            pointerCoordinates, // pointer coords
            original.getMetaState(),
            original.getButtonState(),
            original.getXPrecision(),
            original.getYPrecision(),
            original.getDeviceId(),
            original.getEdgeFlags(),
            original.getSource(),
            original.getFlags(),
            motionEventId.getId());

    return PlatformViewsController2.toMotionEvent(
        1, // density
        frameWorkTouchNonVd);
  }

  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void getPlatformViewById() {
    PlatformViewRegistryImpl registryImpl = new PlatformViewRegistryImpl();
    PlatformViewsController2 PlatformViewsController2 = new PlatformViewsController2();
    PlatformViewsController2.setRegistry(registryImpl);

    int platformViewId = 0;
    assertNull(PlatformViewsController2.getPlatformViewById(platformViewId));

    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    PlatformView platformView = mock(PlatformView.class);
    View androidView = mock(View.class);
    when(platformView.getView()).thenReturn(androidView);
    when(viewFactory.create(any(), eq(platformViewId), any())).thenReturn(platformView);
    PlatformViewsController2.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    attach(jni, PlatformViewsController2);

    // Simulate create call from the framework.
    createPlatformView(jni, PlatformViewsController2, platformViewId, "testType");

    assertTrue(PlatformViewsController2.initializePlatformViewIfNeeded(platformViewId));

    View resultAndroidView = PlatformViewsController2.getPlatformViewById(platformViewId);
    assertNotNull(resultAndroidView);
    assertEquals(resultAndroidView, androidView);
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void createPlatformViewMessage_initializesAndroidView() {
    PlatformViewRegistryImpl registryImpl = new PlatformViewRegistryImpl();
    PlatformViewsController2 PlatformViewsController2 = new PlatformViewsController2();
    PlatformViewsController2.setRegistry(registryImpl);

    int platformViewId = 0;
    assertNull(PlatformViewsController2.getPlatformViewById(platformViewId));

    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    PlatformView platformView = mock(PlatformView.class);
    when(platformView.getView()).thenReturn(mock(View.class));
    when(viewFactory.create(any(), eq(platformViewId), any())).thenReturn(platformView);
    PlatformViewsController2.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    attach(jni, PlatformViewsController2);

    // Simulate create call from the framework.
    createPlatformView(jni, PlatformViewsController2, platformViewId, "testType");
    verify(viewFactory, times(1)).create(any(), eq(platformViewId), any());
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void createPlatformViewMessage_setsAndroidViewLayoutDirection() {
    PlatformViewRegistryImpl registryImpl = new PlatformViewRegistryImpl();
    PlatformViewsController2 PlatformViewsController2 = new PlatformViewsController2();
    PlatformViewsController2.setRegistry(registryImpl);

    int platformViewId = 0;
    assertNull(PlatformViewsController2.getPlatformViewById(platformViewId));

    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    PlatformView platformView = mock(PlatformView.class);

    View androidView = mock(View.class);
    when(platformView.getView()).thenReturn(androidView);
    when(viewFactory.create(any(), eq(platformViewId), any())).thenReturn(platformView);
    PlatformViewsController2.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    attach(jni, PlatformViewsController2);

    // Simulate create call from the framework.
    createPlatformView(jni, PlatformViewsController2, platformViewId, "testType");
    verify(androidView, times(1)).setLayoutDirection(0);
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void createPlatformViewMessage_throwsIfViewIsNull() {
    PlatformViewRegistryImpl registryImpl = new PlatformViewRegistryImpl();
    PlatformViewsController2 PlatformViewsController2 = new PlatformViewsController2();
    PlatformViewsController2.setRegistry(registryImpl);

    int platformViewId = 0;
    assertNull(PlatformViewsController2.getPlatformViewById(platformViewId));

    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    PlatformView platformView = mock(PlatformView.class);
    when(platformView.getView()).thenReturn(null);
    when(viewFactory.create(any(), eq(platformViewId), any())).thenReturn(platformView);
    PlatformViewsController2.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    attach(jni, PlatformViewsController2);

    // Simulate create call from the framework.
    createPlatformView(jni, PlatformViewsController2, platformViewId, "testType");
    assertEquals(1, ShadowFlutterJNI.getResponses().size());

    assertFalse(PlatformViewsController2.initializePlatformViewIfNeeded(platformViewId));
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void createHybridPlatformViewMessage_throwsIfViewIsNull() {
    PlatformViewRegistryImpl registryImpl = new PlatformViewRegistryImpl();
    PlatformViewsController2 PlatformViewsController2 = new PlatformViewsController2();
    PlatformViewsController2.setRegistry(registryImpl);

    int platformViewId = 0;
    assertNull(PlatformViewsController2.getPlatformViewById(platformViewId));

    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    PlatformView platformView = mock(PlatformView.class);
    when(platformView.getView()).thenReturn(null);
    when(viewFactory.create(any(), eq(platformViewId), any())).thenReturn(platformView);
    PlatformViewsController2.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    attach(jni, PlatformViewsController2);

    // Simulate create call from the framework.
    createPlatformView(jni, PlatformViewsController2, platformViewId, "testType");
    assertEquals(1, ShadowFlutterJNI.getResponses().size());

    assertFalse(PlatformViewsController2.initializePlatformViewIfNeeded(platformViewId));
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void setPlatformViewDirection_throwIfPlatformViewNotFound() {
    PlatformViewRegistryImpl registryImpl = new PlatformViewRegistryImpl();
    PlatformViewsController2 PlatformViewsController2 = new PlatformViewsController2();
    PlatformViewsController2.setRegistry(registryImpl);

    int platformViewId = 0;
    assertNull(PlatformViewsController2.getPlatformViewById(platformViewId));

    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    PlatformView platformView = mock(PlatformView.class);
    final View androidView = mock(View.class);
    when(platformView.getView()).thenReturn(androidView);
    when(viewFactory.create(any(), eq(platformViewId), any())).thenReturn(platformView);
    PlatformViewsController2.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    attach(jni, PlatformViewsController2);

    verify(androidView, never()).setLayoutDirection(anyInt());

    // Simulate create call from the framework.
    createPlatformView(jni, PlatformViewsController2, platformViewId, "testType");
    assertEquals(1, ShadowFlutterJNI.getResponses().size());

    // Simulate set direction call from the framework.
    setLayoutDirection(jni, PlatformViewsController2, platformViewId, 1);
    verify(androidView, times(1)).setLayoutDirection(1);

    // The limit value of reply message will be equal to 2 if the layout direction is set
    // successfully, otherwise it will be much more than 2 due to the reply message contains
    // an error message wrapped with exception detail information.
    assertEquals(2, ShadowFlutterJNI.getResponses().get(0).limit());
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void disposeAndroidView() {
    PlatformViewRegistryImpl registryImpl = new PlatformViewRegistryImpl();
    PlatformViewsController2 PlatformViewsController2 = new PlatformViewsController2();
    PlatformViewsController2.setRegistry(registryImpl);

    int platformViewId = 0;
    assertNull(PlatformViewsController2.getPlatformViewById(platformViewId));

    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    PlatformView platformView = mock(PlatformView.class);

    Context context = ApplicationProvider.getApplicationContext();
    View androidView = new View(context);

    when(platformView.getView()).thenReturn(androidView);
    when(viewFactory.create(any(), eq(platformViewId), any())).thenReturn(platformView);
    PlatformViewsController2.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    attach(jni, PlatformViewsController2);

    // Simulate create call from the framework.
    createPlatformView(jni, PlatformViewsController2, platformViewId, "testType");
    assertTrue(PlatformViewsController2.initializePlatformViewIfNeeded(platformViewId));

    assertNotNull(androidView.getParent());
    assertTrue(androidView.getParent() instanceof FlutterMutatorView);

    // Simulate dispose call from the framework.
    disposePlatformView(jni, PlatformViewsController2, platformViewId);
    assertNull(androidView.getParent());

    // Simulate create call from the framework.
    createPlatformView(jni, PlatformViewsController2, platformViewId, "testType");
    assertTrue(PlatformViewsController2.initializePlatformViewIfNeeded(platformViewId));

    assertNotNull(androidView.getParent());
    assertTrue(androidView.getParent() instanceof FlutterMutatorView);
    verify(platformView, times(1)).dispose();
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void disposeNullAndroidView() {
    PlatformViewRegistryImpl registryImpl = new PlatformViewRegistryImpl();
    PlatformViewsController2 PlatformViewsController2 = new PlatformViewsController2();
    PlatformViewsController2.setRegistry(registryImpl);

    int platformViewId = 0;
    assertNull(PlatformViewsController2.getPlatformViewById(platformViewId));

    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    PlatformView platformView = mock(PlatformView.class);

    View androidView = mock(View.class);
    when(platformView.getView()).thenReturn(androidView);
    when(viewFactory.create(any(), eq(platformViewId), any())).thenReturn(platformView);
    PlatformViewsController2.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    attach(jni, PlatformViewsController2);

    // Simulate create call from the framework.
    createPlatformView(jni, PlatformViewsController2, platformViewId, "testType");
    assertTrue(PlatformViewsController2.initializePlatformViewIfNeeded(platformViewId));

    when(platformView.getView()).thenReturn(null);

    // Simulate dispose call from the framework.
    disposePlatformView(jni, PlatformViewsController2, platformViewId);
    verify(platformView, times(1)).dispose();
  }

  private static class TransactionTrackingController extends PlatformViewsController2 {
    final List<SurfaceControl.Transaction> transactions = new ArrayList<>();

    @Override
    SurfaceControl.Transaction newTransaction() {
      SurfaceControl.Transaction tx = spy(super.newTransaction());
      transactions.add(tx);
      return tx;
    }
  }

  private AttachedSurfaceControl attachToViewWithOverlay(PlatformViewsController2 controller) {
    controller.setRegistry(new PlatformViewRegistryImpl());
    FlutterView flutterView = mock(FlutterView.class);
    AttachedSurfaceControl rootSurfaceControl = mock(AttachedSurfaceControl.class);
    when(flutterView.getRootSurfaceControl()).thenReturn(rootSurfaceControl);
    when(rootSurfaceControl.buildReparentTransaction(any()))
        .thenReturn(new SurfaceControl.Transaction());
    controller.attachToView(flutterView);
    controller.createOverlaySurface();
    return rootSurfaceControl;
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void showOverlaySurfaceDefersTransactionUntilEndFrame() {
    TransactionTrackingController controller = new TransactionTrackingController();
    AttachedSurfaceControl rootSurfaceControl = attachToViewWithOverlay(controller);

    controller.showOverlaySurface();
    assertEquals(1, controller.transactions.size());
    SurfaceControl.Transaction platformTx = controller.transactions.get(0);
    verify(platformTx, never()).apply();
    verify(platformTx, never()).close();

    controller.swapTransactions();
    controller.onEndFrame();

    verify(rootSurfaceControl, times(1))
        .applyTransactionOnDraw(any(SurfaceControl.Transaction.class));
    verify(platformTx).close();
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void overlayMutationsSharePlatformTransactionUntilSwap() {
    TransactionTrackingController controller = new TransactionTrackingController();
    attachToViewWithOverlay(controller);

    controller.showOverlaySurface();
    controller.hideOverlaySurface();
    assertEquals(1, controller.transactions.size());
    SurfaceControl.Transaction platformTx = controller.transactions.get(0);
    verify(platformTx).setVisibility(any(SurfaceControl.class), eq(true));
    verify(platformTx).setVisibility(any(SurfaceControl.class), eq(false));

    controller.swapTransactions();

    controller.showOverlaySurface();
    assertEquals(2, controller.transactions.size());
    assertNotSame(platformTx, controller.transactions.get(1));
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void createTransactionIsolatesRasterSubmissionsRegardlessOfCallingThread() {
    TransactionTrackingController controller = new TransactionTrackingController();
    AttachedSurfaceControl rootSurfaceControl = attachToViewWithOverlay(controller);
    controller.showOverlaySurface();
    SurfaceControl.Transaction platformTx = controller.transactions.get(0);

    // The JNI entry point always creates raster transactions, even on this test's main thread.
    SurfaceControl.Transaction rasterTx1 = createAndPublishRasterTransaction(controller);
    SurfaceControl.Transaction rasterTx2 = createAndPublishRasterTransaction(controller);
    assertNotSame(platformTx, rasterTx1);
    assertNotSame(platformTx, rasterTx2);
    assertNotSame(rasterTx1, rasterTx2);

    controller.sealFrameTransactions();
    controller.swapTransactions();
    controller.onEndFrame();

    verify(rootSurfaceControl, times(1))
        .applyTransactionOnDraw(any(SurfaceControl.Transaction.class));
    verify(platformTx).close();
    verify(rasterTx1, never()).close();
    verify(rasterTx2, never()).close();
  }

  /** How the controller discards a frame while a producer still holds its raster transaction. */
  private enum FrameDiscard {
    /** Drop an active frame by swapping again, as a skipped onEndFrame() would. */
    SWAP_AGAIN,
    /** Detach the view before the transaction is swapped into the active list. */
    DETACH_BEFORE_SWAP,
    /** Run the frame that was posted from the raster thread just before the detach. */
    DETACH_THEN_END_FRAME
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void swapTransactionsDoesNotCloseRasterTransactionInUse() throws Exception {
    assertRasterTransactionInUseIsNotClosed(FrameDiscard.SWAP_AGAIN);
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void detachFromViewDoesNotCloseRasterTransactionInUse() throws Exception {
    assertRasterTransactionInUseIsNotClosed(FrameDiscard.DETACH_BEFORE_SWAP);
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void onEndFrameDoesNotCloseRasterTransactionInUseAfterDetach() throws Exception {
    assertRasterTransactionInUseIsNotClosed(FrameDiscard.DETACH_THEN_END_FRAME);
  }

  private void assertRasterTransactionInUseIsNotClosed(FrameDiscard discard) throws Exception {
    final SurfaceControl.Transaction rasterTx = spy(new SurfaceControl.Transaction());
    PlatformViewsController2 controller =
        new PlatformViewsController2() {
          @Override
          SurfaceControl.Transaction newTransaction() {
            return rasterTx;
          }
        };
    controller.setRegistry(new PlatformViewRegistryImpl());

    FlutterView flutterView = mock(FlutterView.class);
    controller.attachToView(flutterView);

    final CountDownLatch published = new CountDownLatch(1);
    final CountDownLatch releaseProducer = new CountDownLatch(1);
    final AtomicReference<Throwable> producerFailure = new AtomicReference<>();
    Thread rasterThread =
        new Thread(
            () -> {
              try {
                SurfaceControl.Transaction tx = createAndPublishRasterTransaction(controller);
                controller.sealFrameTransactions();
                published.countDown();
                // Model native code retaining the borrowed transaction after publishing it.
                if (!releaseProducer.await(10, TimeUnit.SECONDS)) {
                  throw new AssertionError("Platform thread did not release the producer");
                }
                verify(tx, never()).close();
              } catch (Throwable t) {
                producerFailure.set(t);
              }
            });
    rasterThread.setDaemon(true);
    rasterThread.start();
    try {
      assertTrue("Producer did not publish a transaction", published.await(10, TimeUnit.SECONDS));
      switch (discard) {
        case SWAP_AGAIN:
          controller.swapTransactions();
          controller.swapTransactions();
          break;
        case DETACH_BEFORE_SWAP:
          controller.detachFromView();
          break;
        case DETACH_THEN_END_FRAME:
          controller.swapTransactions();
          controller.detachFromView();
          controller.onEndFrame();
          break;
      }
      verify(rasterTx, never()).close();
    } finally {
      releaseProducer.countDown();
      rasterThread.join(10000);
    }
    assertFalse("Producer did not terminate", rasterThread.isAlive());
    if (producerFailure.get() != null) {
      throw new AssertionError("Raster producer failed", producerFailure.get());
    }
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void onEndFrameUsesSeparateMergeDestinationForRasterOnlyFrame() {
    PlatformViewsController2 controller = new PlatformViewsController2();
    controller.setRegistry(new PlatformViewRegistryImpl());
    FlutterView flutterView = mock(FlutterView.class);
    AttachedSurfaceControl rootSurfaceControl = mock(AttachedSurfaceControl.class);
    when(flutterView.getRootSurfaceControl()).thenReturn(rootSurfaceControl);
    controller.attachToView(flutterView);

    SurfaceControl.Transaction rasterTx = createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();

    controller.swapTransactions();
    controller.onEndFrame();

    verify(rootSurfaceControl).applyTransactionOnDraw(argThat(tx -> tx != null && tx != rasterTx));
    verify(flutterView).invalidate();
  }

  @Test
  @Config(
      shadows = {
        ShadowFlutterJNI.class,
        ShadowPlatformTaskQueue.class,
        ShadowRecordingTransaction.class
      })
  public void rasterTransactionIsNotMergedUntilPublishedThroughFlutterJNI() {
    ShadowRecordingTransaction.reset();
    PlatformViewsController2 controller = new PlatformViewsController2();
    controller.setRegistry(new PlatformViewRegistryImpl());
    FlutterJNI jni = new FlutterJNI();
    jni.setPlatformViewsController2(controller);
    FlutterView flutterView = mock(FlutterView.class);
    when(flutterView.getRootSurfaceControl()).thenReturn(mock(AttachedSurfaceControl.class));
    controller.attachToView(flutterView);

    // The raster thread creates the transaction, then keeps writing into it natively.
    SurfaceControl.Transaction rasterTx = jni.createUnpublishedTransaction();

    // A frame that ends while those writes are in flight must not merge it.
    jni.sealFrameTransactions();
    controller.swapTransactions();
    controller.onEndFrame();
    assertTrue(ShadowRecordingTransaction.merged.isEmpty());

    // Once the raster thread publishes it, the next frame merges it.
    jni.publishTransaction(rasterTx);
    jni.sealFrameTransactions();
    controller.swapTransactions();
    controller.onEndFrame();
    assertEquals(Arrays.asList(rasterTx), ShadowRecordingTransaction.merged);
  }

  @Test
  @Config(
      shadows = {
        ShadowFlutterJNI.class,
        ShadowPlatformTaskQueue.class,
        ShadowRecordingTransaction.class
      })
  public void swapTransactionsConsumesOneSealedRasterFrameAtATime() {
    ShadowRecordingTransaction.reset();
    PlatformViewsController2 controller = new PlatformViewsController2();
    controller.setRegistry(new PlatformViewRegistryImpl());
    FlutterJNI jni = new FlutterJNI();
    jni.setPlatformViewsController2(controller);
    FlutterView flutterView = mock(FlutterView.class);
    when(flutterView.getRootSurfaceControl()).thenReturn(mock(AttachedSurfaceControl.class));
    controller.attachToView(flutterView);

    // Frame 1 publishes its overlay and main transactions and seals the frame before posting its
    // platform-thread task.
    SurfaceControl.Transaction frame1OverlayTx = createAndPublishRasterTransaction(controller);
    SurfaceControl.Transaction frame1MainTx = createAndPublishRasterTransaction(controller);
    jni.sealFrameTransactions();

    // Before frame 1's platform task runs swapTransactions(), the raster thread begins frame 2 and
    // publishes its overlay transaction without sealing frame 2 yet.
    SurfaceControl.Transaction frame2OverlayTx = createAndPublishRasterTransaction(controller);

    controller.swapTransactions();
    controller.onEndFrame();
    assertEquals(Arrays.asList(frame1OverlayTx, frame1MainTx), ShadowRecordingTransaction.merged);

    // Frame 2 finishes and seals, and frame 3 also finishes and seals before frame 2's platform
    // task runs swapTransactions().
    ShadowRecordingTransaction.reset();
    SurfaceControl.Transaction frame2MainTx = createAndPublishRasterTransaction(controller);
    jni.sealFrameTransactions();
    SurfaceControl.Transaction frame3OverlayTx = createAndPublishRasterTransaction(controller);
    SurfaceControl.Transaction frame3MainTx = createAndPublishRasterTransaction(controller);
    jni.sealFrameTransactions();

    controller.swapTransactions();
    controller.onEndFrame();
    assertEquals(Arrays.asList(frame2OverlayTx, frame2MainTx), ShadowRecordingTransaction.merged);

    ShadowRecordingTransaction.reset();
    controller.swapTransactions();
    controller.onEndFrame();
    assertEquals(Arrays.asList(frame3OverlayTx, frame3MainTx), ShadowRecordingTransaction.merged);
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void swapTransactionsClosesDiscardedPlatformTransaction() {
    TransactionTrackingController controller = new TransactionTrackingController();
    attachToViewWithOverlay(controller);
    controller.showOverlaySurface();
    // Defensive coverage: production calls onEndFrame() between swaps.
    controller.swapTransactions();
    controller.swapTransactions();

    verify(controller.transactions.get(0)).close();
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void detachFromViewClosesPlatformTransactions() {
    TransactionTrackingController controller = new TransactionTrackingController();
    attachToViewWithOverlay(controller);

    controller.showOverlaySurface();
    controller.swapTransactions();
    // The active transaction from the frame above and a pending one from the frame that never
    // reached onEndFrame().
    controller.showOverlaySurface();
    assertEquals(2, controller.transactions.size());

    controller.detachFromView();

    // Both target the overlay SurfaceControl that detachFromView() just released.
    verify(controller.transactions.get(0)).close();
    verify(controller.transactions.get(1)).close();
  }

  /**
   * Detects list corruption that causes merge(null) and a JNI abort.
   *
   * <p>Detection is probabilistic and excludes native writes after publication. Barrier timeouts
   * skip the run, so this does not test liveness.
   */
  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void createTransactionIsSafeWhenRasterAndPlatformThreadsRaceOnFrames() throws Exception {
    final PlatformViewsController2 controller = new PlatformViewsController2();
    attachToViewWithOverlay(controller);

    final int rasterThreadCount = 4;
    // Larger batches increase the opportunity for add() to overlap clear()/addAll().
    final int presentsPerRound = 64;
    final int rounds = 300;
    final long timeoutMs = 30000;

    // Start producers and the frame swap together each round to encourage overlap.
    final CyclicBarrier roundStart = new CyclicBarrier(rasterThreadCount + 1);
    // Product failures take precedence over harness skips.
    final AtomicReference<Throwable> raceFailure = new AtomicReference<>();
    final AtomicReference<Throwable> harnessFailure = new AtomicReference<>();
    final AtomicBoolean running = new AtomicBoolean(true);
    final List<Thread> rasterThreads = new ArrayList<>();

    for (int i = 0; i < rasterThreadCount; i++) {
      final Thread rasterThread =
          new Thread(
              () -> {
                try {
                  for (int round = 0; round < rounds && running.get(); round++) {
                    roundStart.await(timeoutMs, TimeUnit.MILLISECONDS);
                    for (int present = 0; present < presentsPerRound; present++) {
                      createAndPublishRasterTransaction(controller);
                    }
                    controller.sealFrameTransactions();
                  }
                } catch (TimeoutException e) {
                  harnessFailure.compareAndSet(null, e);
                } catch (BrokenBarrierException e) {
                  // The participant that stopped early recorded the cause.
                } catch (Throwable t) {
                  raceFailure.compareAndSet(null, t);
                } finally {
                  running.set(false);
                  roundStart.reset();
                }
              },
              "fake-raster-" + i);
      rasterThread.setDaemon(true);
      rasterThreads.add(rasterThread);
      rasterThread.start();
    }

    try {
      for (int round = 0; round < rounds; round++) {
        roundStart.await(timeoutMs, TimeUnit.MILLISECONDS);

        controller.showOverlaySurface();
        controller.hideOverlaySurface();

        controller.swapTransactions();
        controller.onEndFrame();
      }
    } catch (TimeoutException | BrokenBarrierException e) {
      harnessFailure.compareAndSet(null, e);
    } catch (Throwable t) {
      raceFailure.compareAndSet(null, t);
    } finally {
      running.set(false);
      for (Thread rasterThread : rasterThreads) {
        // Repeat resets in case a producer enters await() after an earlier reset.
        for (int attempt = 0; attempt < 500 && rasterThread.isAlive(); attempt++) {
          roundStart.reset();
          rasterThread.join(10);
        }
        if (rasterThread.isAlive()) {
          raceFailure.compareAndSet(
              null, new AssertionError(rasterThread.getName() + " did not terminate"));
        }
      }
    }

    if (raceFailure.get() != null) {
      throw new AssertionError("Concurrent transaction processing failed.", raceFailure.get());
    }
    Assume.assumeNoException(
        "Stress run interrupted by a barrier failure; transaction failures are reported above.",
        harnessFailure.get());
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void onEndFrameIsANoopAfterDetachFromView() {
    PlatformViewsController2 controller = new PlatformViewsController2();
    controller.setRegistry(new PlatformViewRegistryImpl());

    FlutterView mockFlutterView = mock(FlutterView.class);
    AttachedSurfaceControl mockAttachedSurfaceControl = mock(AttachedSurfaceControl.class);
    when(mockFlutterView.getRootSurfaceControl()).thenReturn(mockAttachedSurfaceControl);

    controller.attachToView(mockFlutterView);
    createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.swapTransactions();
    controller.detachFromView();

    // onEndFrame is posted from the raster thread, so it can run after the view was detached.
    // It must not throw: the JNI caller turns a pending exception into an abort.
    controller.onEndFrame();

    verify(mockAttachedSurfaceControl, never())
        .applyTransactionOnDraw(any(SurfaceControl.Transaction.class));
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void onEndFrameIsANoopWhenFlutterViewHasNoRootSurfaceControl() {
    PlatformViewsController2 controller = new PlatformViewsController2();
    controller.setRegistry(new PlatformViewRegistryImpl());

    // getRootSurfaceControl() returns null while the view is not attached to a window.
    FlutterView mockFlutterView = mock(FlutterView.class);
    when(mockFlutterView.getRootSurfaceControl()).thenReturn(null);

    controller.attachToView(mockFlutterView);
    createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.swapTransactions();

    controller.onEndFrame();

    verify(mockFlutterView, never()).invalidate();
  }

  @Test
  @Config(
      shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class},
      minSdk = 34)
  public void onDisplayPlatformView_onlyUpdatesLayoutParamsWhenTheyChange() {
    PlatformViewRegistryImpl registryImpl = new PlatformViewRegistryImpl();
    PlatformViewsController2 platformViewsController = new PlatformViewsController2();
    platformViewsController.setRegistry(registryImpl);

    int platformViewId = 0;
    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    PlatformView platformView = mock(PlatformView.class);
    View androidView = spy(new View(ApplicationProvider.getApplicationContext()));
    when(platformView.getView()).thenReturn(androidView);
    when(viewFactory.create(any(), eq(platformViewId), any())).thenReturn(platformView);
    platformViewsController.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    jni.attachToNative();
    FlutterView flutterView = attach(jni, platformViewsController);
    createPlatformView(jni, platformViewsController, platformViewId, "testType");

    platformViewsController.onDisplayPlatformView(
        platformViewId, 0, 0, 10, 10, 10, 10, new FlutterMutatorsStack());
    flutterView.measure(
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
    flutterView.layout(0, 0, 100, 100);
    FlutterMutatorView parentView = platformViewsController.getPlatformViewParent(platformViewId);
    assertFalse(parentView.isLayoutRequested());
    assertFalse(flutterView.isLayoutRequested());
    clearInvocations(androidView);

    // Moving the platform view without changing its size updates its position without requesting
    // layout on the wrapper, FlutterView, or child view.
    platformViewsController.onDisplayPlatformView(
        platformViewId, 5, 5, 10, 10, 10, 10, new FlutterMutatorsStack());
    verify(androidView, never()).setLayoutParams(any());
    verify(androidView, never()).bringToFront();
    assertFalse(parentView.isLayoutRequested());
    assertFalse(flutterView.isLayoutRequested());
    assertEquals(5, ((FrameLayout.LayoutParams) parentView.getLayoutParams()).leftMargin);
    assertEquals(5, ((FrameLayout.LayoutParams) parentView.getLayoutParams()).topMargin);

    platformViewsController.onDisplayPlatformView(
        platformViewId, 5, 5, 20, 10, 20, 10, new FlutterMutatorsStack());
    verify(androidView, times(1)).setLayoutParams(any());
    assertEquals(20, androidView.getLayoutParams().width);
    assertTrue(parentView.isLayoutRequested());
  }

  @Test
  @Config(shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class})
  public void itInformsMutatorViewWhenGestureIsRejected() {
    PlatformViewRegistryImpl registryImpl = new PlatformViewRegistryImpl();
    PlatformViewsController2 platformViewsController = new PlatformViewsController2();
    platformViewsController.setRegistry(registryImpl);

    int platformViewId = 0;
    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    PlatformView platformView = mock(PlatformView.class);
    View androidView = mock(View.class);
    when(platformView.getView()).thenReturn(androidView);
    when(viewFactory.create(any(), eq(platformViewId), any())).thenReturn(platformView);
    platformViewsController.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    jni.attachToNative();
    attach(jni, platformViewsController);

    createPlatformView(jni, platformViewsController, platformViewId, "testType");

    assertTrue(platformViewsController.initializePlatformViewIfNeeded(platformViewId));

    FlutterMutatorView parentView = platformViewsController.getPlatformViewParent(platformViewId);
    assertNotNull(parentView);
    assertFalse(parentView.getFlutterWonGesture());

    // Without active gesture, rejectGesture has no effect.
    rejectGesturePlatformView(jni, platformViewsController, platformViewId, 100L);
    assertFalse(parentView.getFlutterWonGesture());

    // Start active gesture with downTime 100.
    final MotionEvent downEvent =
        MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 0.0f, 0.0f, 0);
    final MotionEventTracker.MotionEventId eventId =
        MotionEventTracker.getInstance().track(downEvent);
    parentView.onTouchEvent(downEvent);

    // Mismatched gestureId does not set flutterWonGesture.
    rejectGesturePlatformView(jni, platformViewsController, platformViewId, 99999L);
    assertFalse(parentView.getFlutterWonGesture());

    // Matching gestureId sets flutterWonGesture.
    rejectGesturePlatformView(jni, platformViewsController, platformViewId, eventId.getId());
    assertTrue(parentView.getFlutterWonGesture());
  }

  @Test
  @Config(
      shadows = {
        ShadowFlutterJNI.class,
        ShadowPlatformTaskQueue.class,
        ShadowRecordingTransaction.class
      })
  public void onEndFrame_defersSecondFrameUntilChoreographerTraversalCompletes() {
    ShadowRecordingTransaction.reset();
    PlatformViewsController2 controller =
        new PlatformViewsController2() {
          @Override
          boolean shouldDeferUntilNextTraversal() {
            return true;
          }
        };
    controller.setRegistry(new PlatformViewRegistryImpl());

    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    when(viewFactory.create(any(), anyInt(), any()))
        .thenAnswer(
            invocation -> {
              PlatformView pv = mock(PlatformView.class);
              when(pv.getView()).thenReturn(new View(ApplicationProvider.getApplicationContext()));
              return pv;
            });
    controller.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    jni.attachToNative();
    AttachedSurfaceControl rootSurfaceControl = mock(AttachedSurfaceControl.class);
    Context context = ApplicationProvider.getApplicationContext();
    FlutterView flutterView =
        new FlutterView(context, new FlutterSurfaceView(context)) {
          @Override
          public AttachedSurfaceControl getRootSurfaceControl() {
            return rootSurfaceControl;
          }
        };
    attachToFlutterView(jni, controller, flutterView);

    createPlatformView(jni, controller, 1, "testType");

    // Frame 1 stages y = 10 and rasterTx1 immediately for the upcoming Choreographer traversal.
    FlutterMutatorsStack stack1 = new FlutterMutatorsStack();
    stack1.pushTransform(new float[] {1, 0, 0, 0, 1, 10, 0, 0, 1});
    SurfaceControl.Transaction rasterTx1 = createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.onDisplayPlatformView(1, 0, 10, 50, 50, 50, 50, stack1);
    controller.swapTransactions();
    controller.onEndFrame();

    FlutterMutatorView parent = controller.getPlatformViewParent(1);
    assertEquals(10, ((FrameLayout.LayoutParams) parent.getLayoutParams()).topMargin);
    verify(rootSurfaceControl, times(1))
        .applyTransactionOnDraw(any(SurfaceControl.Transaction.class));
    assertEquals(Arrays.asList(rasterTx1), ShadowRecordingTransaction.merged);

    // Frame 2 arrives on the UI thread before Choreographer.doFrame() draws Frame 1.
    // It must be held in queuedFrame so Frame 1 is not overwritten before being drawn.
    ShadowRecordingTransaction.reset();
    FlutterMutatorsStack stack2 = new FlutterMutatorsStack();
    stack2.pushTransform(new float[] {1, 0, 0, 0, 1, 30, 0, 0, 1});
    SurfaceControl.Transaction rasterTx2 = createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.onDisplayPlatformView(1, 0, 30, 50, 50, 50, 50, stack2);
    controller.swapTransactions();
    controller.onEndFrame();

    assertEquals(10, ((FrameLayout.LayoutParams) parent.getLayoutParams()).topMargin);
    verify(rootSurfaceControl, times(1))
        .applyTransactionOnDraw(any(SurfaceControl.Transaction.class));
    assertTrue(ShadowRecordingTransaction.merged.isEmpty());

    // Once the Choreographer frame and post-traversal callback run, Frame 2 is staged for the
    // next VSync.
    ShadowLooper.idleMainLooper();

    assertEquals(30, ((FrameLayout.LayoutParams) parent.getLayoutParams()).topMargin);
    verify(rootSurfaceControl, times(2))
        .applyTransactionOnDraw(any(SurfaceControl.Transaction.class));
    assertEquals(Arrays.asList(rasterTx2), ShadowRecordingTransaction.merged);
  }

  @Test
  @Config(
      shadows = {
        ShadowFlutterJNI.class,
        ShadowPlatformTaskQueue.class,
        ShadowRecordingTransaction.class
      })
  public void onEndFrame_stagesNextFrameImmediatelyWhenNativePollRunsAfterChoreographerCallback() {
    ShadowRecordingTransaction.reset();
    PlatformViewsController2 controller =
        new PlatformViewsController2() {
          @Override
          boolean shouldDeferUntilNextTraversal() {
            return true;
          }
        };
    controller.setRegistry(new PlatformViewRegistryImpl());

    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    when(viewFactory.create(any(), anyInt(), any()))
        .thenAnswer(
            invocation -> {
              PlatformView pv = mock(PlatformView.class);
              when(pv.getView()).thenReturn(new View(ApplicationProvider.getApplicationContext()));
              return pv;
            });
    controller.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    jni.attachToNative();
    AttachedSurfaceControl rootSurfaceControl = mock(AttachedSurfaceControl.class);
    Context context = ApplicationProvider.getApplicationContext();
    FlutterView flutterView =
        new FlutterView(context, new FlutterSurfaceView(context)) {
          @Override
          public AttachedSurfaceControl getRootSurfaceControl() {
            return rootSurfaceControl;
          }
        };
    attachToFlutterView(jni, controller, flutterView);

    createPlatformView(jni, controller, 1, "testType");

    // Frame 1 stages y = 10 and registers traversalFrameCallback on Choreographer.
    FlutterMutatorsStack stack1 = new FlutterMutatorsStack();
    stack1.pushTransform(new float[] {1, 0, 0, 0, 1, 10, 0, 0, 1});
    SurfaceControl.Transaction rasterTx1 = createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.onDisplayPlatformView(1, 0, 10, 50, 50, 50, 50, stack1);
    controller.swapTransactions();
    controller.onEndFrame();

    // Run only the scheduled Choreographer frame callback (traversalFrameCallback) without
    // draining the newly-posted Handler message (traversalCompleteRunnable) yet, simulating
    // MessageLoopAndroid running inside MessageQueue.nativePollOnce() right after doFrame().
    ShadowLooper.shadowMainLooper().runOneTask();

    // Now simulate Frame 2 arriving from nativePollOnce() BEFORE traversalCompleteRunnable runs.
    ShadowRecordingTransaction.reset();
    FlutterMutatorsStack stack2 = new FlutterMutatorsStack();
    stack2.pushTransform(new float[] {1, 0, 0, 0, 1, 25, 0, 0, 1});
    SurfaceControl.Transaction rasterTx2 = createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.onDisplayPlatformView(1, 0, 25, 50, 50, 50, 50, stack2);
    controller.swapTransactions();
    controller.onEndFrame();

    // Frame 2 must be staged immediately rather than trapped in queuedFrame.
    FlutterMutatorView parent = controller.getPlatformViewParent(1);
    assertEquals(25, ((FrameLayout.LayoutParams) parent.getLayoutParams()).topMargin);
    verify(rootSurfaceControl, times(2))
        .applyTransactionOnDraw(any(SurfaceControl.Transaction.class));
    assertEquals(Arrays.asList(rasterTx2), ShadowRecordingTransaction.merged);

    ShadowLooper.idleMainLooper();
  }

  // Sets up a controller with a created HCPP platform view (id 0) whose view is
  // `androidView`, and a renderer whose surface producers are `producer`.
  private PlatformViewsController2 setUpCapture(
      View androidView, TextureRegistry.SurfaceProducer producer) {
    PlatformViewsController2 controller = new PlatformViewsController2();
    controller.setRegistry(new PlatformViewRegistryImpl());
    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    PlatformView platformView = mock(PlatformView.class);
    when(platformView.getView()).thenReturn(androidView);
    when(viewFactory.create(any(), eq(0), any())).thenReturn(platformView);
    controller.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    jni.attachToNative();
    controller.setFlutterJNI(jni);
    attach(jni, controller);
    FlutterRenderer renderer = mock(FlutterRenderer.class);
    when(renderer.createSurfaceProducer()).thenReturn(producer);
    controller.attachToFlutterRenderer(renderer);

    createPlatformView(jni, controller, 0, "testType");
    assertTrue(controller.initializePlatformViewIfNeeded(0));
    return controller;
  }

  private static TextureRegistry.SurfaceProducer mockCaptureProducer() {
    return mock(
        TextureRegistry.SurfaceProducer.class,
        withSettings().extraInterfaces(TextureRegistry.ImageConsumer.class));
  }

  @Test
  @Config(
      shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class},
      minSdk = 34)
  public void capturePlatformViewReturnsStableConsumer() {
    TextureRegistry.SurfaceProducer producer = mockCaptureProducer();
    View androidView = new View(ApplicationProvider.getApplicationContext());
    PlatformViewsController2 controller = setUpCapture(androidView, producer);

    java.lang.ref.WeakReference<TextureRegistry.ImageConsumer> first =
        controller.capturePlatformView(0, false);
    assertNotNull(first);
    assertSame(producer, first.get());
    // Later frames reuse the same capture.
    assertSame(first, controller.capturePlatformView(0, false));
    assertTrue(controller.hasCapture(0));
  }

  @Test
  @Config(
      shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class},
      minSdk = 34)
  public void captureIsReleasedWhenAFrameDoesNotRequestIt() {
    TextureRegistry.SurfaceProducer producer = mockCaptureProducer();
    View androidView = new View(ApplicationProvider.getApplicationContext());
    PlatformViewsController2 controller = setUpCapture(androidView, producer);

    assertNotNull(controller.capturePlatformView(0, false));
    // Requested in this frame: kept.
    controller.onEndFrame();
    assertTrue(controller.hasCapture(0));
    verify(producer, never()).release();

    // Not requested in the next frame: released.
    controller.onEndFrame();
    assertFalse(controller.hasCapture(0));
    verify(producer).release();
  }

  @Test
  @Config(
      shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class},
      minSdk = 34)
  public void surfaceViewsAreNotCaptured() {
    TextureRegistry.SurfaceProducer producer = mockCaptureProducer();
    SurfaceView androidView = new SurfaceView(ApplicationProvider.getApplicationContext());
    PlatformViewsController2 controller = setUpCapture(androidView, producer);

    assertNull(controller.capturePlatformView(0, false));
    assertFalse(controller.hasCapture(0));
  }

  @Test
  @Config(
      shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class},
      minSdk = 34)
  public void producersThatAreNotImageConsumersAreNotUsed() {
    // For example the SurfaceTexture-based producer used on devices with broken
    // HardwareBuffer support.
    TextureRegistry.SurfaceProducer producer = mock(TextureRegistry.SurfaceProducer.class);
    View androidView = new View(ApplicationProvider.getApplicationContext());
    PlatformViewsController2 controller = setUpCapture(androidView, producer);
    FlutterRenderer renderer = mock(FlutterRenderer.class);
    when(renderer.createSurfaceProducer()).thenReturn(producer);
    controller.attachToFlutterRenderer(renderer);

    assertNull(controller.capturePlatformView(0, false));
    assertNull(controller.capturePlatformView(0, false));
    verify(renderer, times(1)).createSurfaceProducer();
    verify(producer, times(1)).release();
  }

  @Test
  @Config(
      shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class},
      minSdk = 34)
  public void captureIsReleasedWhenThePlatformViewIsDisposed() {
    TextureRegistry.SurfaceProducer producer = mockCaptureProducer();
    View androidView = new View(ApplicationProvider.getApplicationContext());
    PlatformViewsController2 controller = setUpCapture(androidView, producer);

    assertNotNull(controller.capturePlatformView(0, false));
    controller.disposePlatformView(0);
    assertFalse(controller.hasCapture(0));
    verify(producer).release();
  }

  @Test
  @Config(
      shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class},
      minSdk = 34)
  public void substitutedViewsDoNotDrawToTheWindow() {
    TextureRegistry.SurfaceProducer producer = mockCaptureProducer();
    View androidView = new View(ApplicationProvider.getApplicationContext());
    PlatformViewsController2 controller = setUpCapture(androidView, producer);
    FlutterMutatorView parentView = controller.getPlatformViewParent(0);
    assertTrue(parentView.getDrawToWindow());

    // Captured ahead of time only: the live view keeps drawing.
    assertNotNull(controller.capturePlatformView(0, false));
    assertTrue(parentView.getDrawToWindow());

    // Drawn by the engine from the capture: the live view stops drawing.
    assertNotNull(controller.capturePlatformView(0, true));
    assertFalse(parentView.getDrawToWindow());
    controller.onEndFrame();
    assertFalse(parentView.getDrawToWindow());

    // No longer under a filter: the capture is released and the live view draws again.
    controller.onEndFrame();
    assertTrue(parentView.getDrawToWindow());
  }

  @Test
  @Config(
      shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class},
      minSdk = 34)
  public void disposingASubstitutedViewRestoresWindowDrawing() {
    TextureRegistry.SurfaceProducer producer = mockCaptureProducer();
    View androidView = new View(ApplicationProvider.getApplicationContext());
    PlatformViewsController2 controller = setUpCapture(androidView, producer);
    FlutterMutatorView parentView = controller.getPlatformViewParent(0);

    assertNotNull(controller.capturePlatformView(0, true));
    assertFalse(parentView.getDrawToWindow());
    controller.disposePlatformView(0);
    assertTrue(parentView.getDrawToWindow());
  }

  @Test
  @Config(
      shadows = {
        ShadowFlutterJNI.class,
        ShadowPlatformTaskQueue.class,
        ShadowRecordingTransaction.class
      },
      minSdk = 34)
  public void capturePlatformView_defersInitializingNewViewUntilStagedTraversalCompletes() {
    ShadowRecordingTransaction.reset();
    PlatformViewsController2 controller =
        new PlatformViewsController2() {
          @Override
          boolean shouldDeferUntilNextTraversal() {
            return true;
          }
        };
    controller.setRegistry(new PlatformViewRegistryImpl());

    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    when(viewFactory.create(any(), anyInt(), any()))
        .thenAnswer(
            invocation -> {
              PlatformView pv = mock(PlatformView.class);
              when(pv.getView()).thenReturn(new View(ApplicationProvider.getApplicationContext()));
              return pv;
            });
    controller.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    jni.attachToNative();
    AttachedSurfaceControl rootSurfaceControl = mock(AttachedSurfaceControl.class);
    Context context = ApplicationProvider.getApplicationContext();
    FlutterView flutterView =
        new FlutterView(context, new FlutterSurfaceView(context)) {
          @Override
          public AttachedSurfaceControl getRootSurfaceControl() {
            return rootSurfaceControl;
          }
        };
    attachToFlutterView(jni, controller, flutterView);
    TextureRegistry.SurfaceProducer producer = mockCaptureProducer();
    FlutterRenderer renderer = mock(FlutterRenderer.class);
    when(renderer.createSurfaceProducer()).thenReturn(producer);
    controller.attachToFlutterRenderer(renderer);

    createPlatformView(jni, controller, 0, "testType");
    createPlatformView(jni, controller, 1, "testType");

    // Frame 1 displays view 0 and stages the frame for Choreographer traversal.
    createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.onDisplayPlatformView(0, 0, 0, 50, 50, 50, 50, new FlutterMutatorsStack());
    controller.swapTransactions();
    controller.onEndFrame();

    // Frame 2 arrives before Frame 1's traversal runs and first displays + captures view 1.
    createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.onDisplayPlatformView(1, 0, 60, 50, 50, 50, 50, new FlutterMutatorsStack());
    java.lang.ref.WeakReference<TextureRegistry.ImageConsumer> consumer =
        controller.capturePlatformView(1, true);
    assertNotNull(consumer);
    assertSame(producer, consumer.get());
    controller.swapTransactions();
    controller.onEndFrame();

    // View 1 must not be added to FlutterView before Frame 1's traversal finishes, and drawing
    // Frame 1 must not throw on an unready FlutterMutatorView.
    assertNull(controller.getPlatformViewParent(1));
    flutterView.draw(new Canvas());

    // Once Frame 1's traversal completes, view 1 is initialized, readyToDisplay, and substituted.
    ShadowLooper.idleMainLooper();
    FlutterMutatorView parent1 = controller.getPlatformViewParent(1);
    assertNotNull(parent1);
    assertFalse(parent1.getDrawToWindow());
    flutterView.draw(new Canvas());
  }

  @Test
  @Config(
      shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class},
      minSdk = 34)
  public void onDisplayPlatformView_preservesRelativeZOrderWithoutSteadyStateBringToFront() {
    PlatformViewsController2 controller = new PlatformViewsController2();
    controller.setRegistry(new PlatformViewRegistryImpl());

    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    when(viewFactory.create(any(), anyInt(), any()))
        .thenAnswer(
            invocation -> {
              PlatformView pv = mock(PlatformView.class);
              when(pv.getView()).thenReturn(new View(ApplicationProvider.getApplicationContext()));
              return pv;
            });
    controller.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    jni.attachToNative();
    FlutterView flutterView = attach(jni, controller);

    createPlatformView(jni, controller, 0, "testType");
    createPlatformView(jni, controller, 1, "testType");

    // Frame 1 displays view 0 then view 1, followed by layout.
    controller.onDisplayPlatformView(0, 0, 0, 50, 50, 50, 50, new FlutterMutatorsStack());
    controller.onDisplayPlatformView(1, 0, 60, 50, 50, 50, 50, new FlutterMutatorsStack());
    controller.onEndFrame();
    flutterView.measure(
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
    flutterView.layout(0, 0, 100, 100);

    FlutterMutatorView parent0 = controller.getPlatformViewParent(0);
    FlutterMutatorView parent1 = controller.getPlatformViewParent(1);
    assertTrue(flutterView.indexOfChild(parent0) < flutterView.indexOfChild(parent1));
    assertFalse(flutterView.isLayoutRequested());

    // Frame 2 displays view 0 then view 1 in the same order: neither calls bringToFront(), so
    // FlutterView does not request a layout pass.
    controller.onDisplayPlatformView(0, 5, 0, 50, 50, 50, 50, new FlutterMutatorsStack());
    controller.onDisplayPlatformView(1, 5, 60, 50, 50, 50, 50, new FlutterMutatorsStack());
    controller.onEndFrame();
    assertTrue(flutterView.indexOfChild(parent0) < flutterView.indexOfChild(parent1));
    assertFalse(flutterView.isLayoutRequested());

    // Frame 3 swaps composition order to view 1 then view 0: view 0 is brought to front.
    controller.onDisplayPlatformView(1, 5, 60, 50, 50, 50, 50, new FlutterMutatorsStack());
    controller.onDisplayPlatformView(0, 5, 0, 50, 50, 50, 50, new FlutterMutatorsStack());
    controller.onEndFrame();
    assertTrue(flutterView.indexOfChild(parent1) < flutterView.indexOfChild(parent0));
  }

  @Test
  @Config(
      shadows = {
        ShadowFlutterJNI.class,
        ShadowPlatformTaskQueue.class,
        ShadowRecordingTransaction.class
      },
      minSdk = 34)
  public void
      capturePlatformView_defersReleaseAndSchedulesFrameWhenSubstitutedViewBecomesUncapturable() {
    ShadowRecordingTransaction.reset();
    ShadowFlutterJNI.scheduleFrameCalls = 0;
    PlatformViewsController2 controller =
        new PlatformViewsController2() {
          @Override
          boolean shouldDeferUntilNextTraversal() {
            return true;
          }
        };
    controller.setRegistry(new PlatformViewRegistryImpl());

    Context context = ApplicationProvider.getApplicationContext();
    FrameLayout containerView = new FrameLayout(context);
    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    PlatformView platformView = mock(PlatformView.class);
    when(platformView.getView()).thenReturn(containerView);
    when(viewFactory.create(any(), eq(0), any())).thenReturn(platformView);
    controller.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    jni.attachToNative();
    controller.setFlutterJNI(jni);
    FlutterView flutterView = spy(attach(jni, controller));
    AttachedSurfaceControl rootSurfaceControl = mock(AttachedSurfaceControl.class);
    doReturn(rootSurfaceControl).when(flutterView).getRootSurfaceControl();
    controller.attachToView(flutterView);
    TextureRegistry.SurfaceProducer producer = mockCaptureProducer();
    FlutterRenderer renderer = mock(FlutterRenderer.class);
    when(renderer.createSurfaceProducer()).thenReturn(producer);
    controller.attachToFlutterRenderer(renderer);

    createPlatformView(jni, controller, 0, "testType");

    // Frame 1 substitutes view 0 and stages the frame for Choreographer traversal.
    createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.onDisplayPlatformView(0, 0, 0, 50, 50, 50, 50, new FlutterMutatorsStack());
    assertNotNull(controller.capturePlatformView(0, true));
    controller.swapTransactions();
    controller.onEndFrame();

    FlutterMutatorView parent0 = controller.getPlatformViewParent(0);
    assertFalse(parent0.getDrawToWindow());

    // Before Frame 1's traversal runs, view 0 adds a SurfaceView child (making it uncapturable)
    // and Frame 2 arrives with substituted=true.
    containerView.addView(new SurfaceView(context));
    createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.onDisplayPlatformView(0, 0, 0, 50, 50, 50, 50, new FlutterMutatorsStack());
    assertNull(controller.capturePlatformView(0, true));
    controller.swapTransactions();
    controller.onEndFrame();

    // A fallback frame is scheduled so the engine re-renders without substitution, and the capture
    // is not released until Frame 1's staged traversal completes.
    assertEquals(1, ShadowFlutterJNI.scheduleFrameCalls);
    assertFalse(parent0.getDrawToWindow());
    verify(producer, never()).release();

    // Once Frame 1's traversal finishes, Frame 2 commits and releases the unrequested capture.
    ShadowLooper.idleMainLooper();
    assertTrue(parent0.getDrawToWindow());
    assertFalse(controller.hasCapture(0));
    verify(producer, times(1)).release();
  }

  @Test
  @Config(
      shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class},
      minSdk = 34)
  public void capturePlatformView_translatesCanvasByViewScrollOffset() {
    TextureRegistry.SurfaceProducer producer = mockCaptureProducer();
    Surface surface = mock(Surface.class);
    Canvas canvas = mock(Canvas.class);
    when(surface.isValid()).thenReturn(true);
    when(surface.lockHardwareCanvas()).thenReturn(canvas);
    when(producer.getSurface()).thenReturn(surface);

    View androidView = spy(new View(ApplicationProvider.getApplicationContext()));
    PlatformViewsController2 controller = setUpCapture(androidView, producer);
    androidView.layout(0, 0, 100, 100);
    androidView.scrollTo(15, 35);

    assertNotNull(controller.capturePlatformView(0, true));

    org.mockito.InOrder inOrder = inOrder(canvas, androidView, surface);
    inOrder.verify(canvas).translate(-15f, -35f);
    inOrder.verify(androidView).draw(canvas);
    inOrder.verify(surface).unlockCanvasAndPost(canvas);
  }

  @Test
  @Config(
      shadows = {ShadowFlutterJNI.class, ShadowPlatformTaskQueue.class},
      minSdk = 34)
  public void capturePlatformView_recapturesOnContentDrawnWhenSizeChangesAfterLayout() {
    TextureRegistry.SurfaceProducer producer = mockCaptureProducer();
    final int[] producerSize = new int[2];
    doAnswer(
            invocation -> {
              producerSize[0] = invocation.getArgument(0);
              producerSize[1] = invocation.getArgument(1);
              return null;
            })
        .when(producer)
        .setSize(anyInt(), anyInt());
    when(producer.getWidth()).thenAnswer(invocation -> producerSize[0]);
    when(producer.getHeight()).thenAnswer(invocation -> producerSize[1]);
    Surface surface = mock(Surface.class);
    when(surface.isValid()).thenReturn(true);
    when(surface.lockHardwareCanvas()).thenReturn(mock(Canvas.class));
    when(producer.getSurface()).thenReturn(surface);

    View androidView = new View(ApplicationProvider.getApplicationContext());
    PlatformViewsController2 controller = setUpCapture(androidView, producer);
    FlutterMutatorView parentView = controller.getPlatformViewParent(0);

    // Initial frame at 50x50.
    controller.onDisplayPlatformView(0, 0, 0, 50, 50, 50, 50, new FlutterMutatorsStack());
    parentView.measure(
        View.MeasureSpec.makeMeasureSpec(50, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(50, View.MeasureSpec.EXACTLY));
    parentView.layout(0, 0, 50, 50);
    assertNotNull(controller.capturePlatformView(0, true));
    assertEquals(50, producer.getWidth());
    assertEquals(50, producer.getHeight());
    verify(surface, times(1)).lockHardwareCanvas();

    // Resize frame: onDisplayPlatformView updates LayoutParams (marking dirty=true and requesting
    // layout while the view still has pre-layout 50x50 dimensions), and capturePlatformView runs
    // before layout. Because isLayoutRequested() is true, capture() defers until layout completes.
    controller.onDisplayPlatformView(0, 0, 0, 80, 90, 80, 90, new FlutterMutatorsStack());
    assertNotNull(controller.capturePlatformView(0, true));
    assertEquals(50, producer.getWidth());
    assertEquals(50, producer.getHeight());
    verify(surface, times(1)).lockHardwareCanvas();

    // When the traversal lays out the view at 80x90 and draws parentView, onContentDrawn()
    // captures the post-layout 80x90 view without an extra pre-layout capture.
    parentView.measure(
        View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(90, View.MeasureSpec.EXACTLY));
    parentView.layout(0, 0, 80, 90);
    parentView.draw(new Canvas());
    assertEquals(80, producer.getWidth());
    assertEquals(90, producer.getHeight());
    verify(surface, times(2)).lockHardwareCanvas();

    // Detaching and re-attaching to a new FlutterView releases captures, schedules a frame, and
    // recaptures on post-layout onLayout even when the child's 80x90 bounds are unchanged and
    // parentView.draw() is not called.
    ShadowFlutterJNI.scheduleFrameCalls = 0;
    controller.detachFromView();
    assertTrue(parentView.getDrawToWindow());
    controller.attachToView(new FlutterView(ApplicationProvider.getApplicationContext()));
    assertEquals(1, ShadowFlutterJNI.scheduleFrameCalls);

    androidView.requestLayout();
    assertNotNull(controller.capturePlatformView(0, true));
    verify(surface, times(2)).lockHardwareCanvas();
    parentView.measure(
        View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(90, View.MeasureSpec.EXACTLY));
    parentView.layout(0, 0, 80, 90);
    verify(surface, times(3)).lockHardwareCanvas();

    // A subsequent native requestLayout() without invalidate() at the same 80x90 outer size still
    // marks the capture dirty in onLayout and recaptures.
    androidView.requestLayout();
    parentView.measure(
        View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(90, View.MeasureSpec.EXACTLY));
    parentView.layout(0, 0, 80, 90);
    verify(surface, times(4)).lockHardwareCanvas();
  }

  @Test
  @Config(
      shadows = {
        ShadowFlutterJNI.class,
        ShadowPlatformTaskQueue.class,
        ShadowRecordingTransaction.class
      },
      minSdk = 34)
  public void
      onEndFrame_coalescedQueuedFramesResetLastDisplayedChildIndexAndSkipSupersededCapture() {
    ShadowRecordingTransaction.reset();
    PlatformViewsController2 controller =
        new PlatformViewsController2() {
          @Override
          boolean shouldDeferUntilNextTraversal() {
            return true;
          }
        };
    controller.setRegistry(new PlatformViewRegistryImpl());

    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    when(viewFactory.create(any(), anyInt(), any()))
        .thenAnswer(
            invocation -> {
              PlatformView pv = mock(PlatformView.class);
              when(pv.getView()).thenReturn(new View(ApplicationProvider.getApplicationContext()));
              return pv;
            });
    controller.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    jni.attachToNative();
    AttachedSurfaceControl rootSurfaceControl = mock(AttachedSurfaceControl.class);
    Context context = ApplicationProvider.getApplicationContext();
    FlutterView flutterView =
        new FlutterView(context, new FlutterSurfaceView(context)) {
          @Override
          public AttachedSurfaceControl getRootSurfaceControl() {
            return rootSurfaceControl;
          }
        };
    attachToFlutterView(jni, controller, flutterView);

    TextureRegistry.SurfaceProducer producer = mockCaptureProducer();
    Surface surface = mock(Surface.class);
    when(surface.isValid()).thenReturn(true);
    when(surface.lockHardwareCanvas()).thenReturn(mock(Canvas.class));
    when(producer.getSurface()).thenReturn(surface);
    FlutterRenderer renderer = mock(FlutterRenderer.class);
    when(renderer.createSurfaceProducer()).thenReturn(producer);
    controller.attachToFlutterRenderer(renderer);

    createPlatformView(jni, controller, 0, "testType");
    createPlatformView(jni, controller, 1, "testType");

    // Frame 1 displays view 0 then view 1 and stages the frame for traversal.
    createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.onDisplayPlatformView(0, 0, 0, 50, 50, 50, 50, new FlutterMutatorsStack());
    controller.onDisplayPlatformView(1, 0, 60, 50, 50, 50, 50, new FlutterMutatorsStack());
    controller.swapTransactions();
    controller.onEndFrame();

    flutterView.measure(
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY));
    flutterView.layout(0, 0, 100, 120);
    assertFalse(flutterView.isLayoutRequested());

    // While Frame 1's traversal is still staged, Frame 2 and Frame 3 both arrive and coalesce into
    // queuedFrame. Frame 2 requests a capture of view 0, then Frame 3 drops that capture while both
    // frames display view 0 and view 1 in the same order with unchanged bounds.
    createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.onDisplayPlatformView(0, 5, 0, 50, 50, 50, 50, new FlutterMutatorsStack());
    assertNotNull(controller.capturePlatformView(0, true));
    controller.onDisplayPlatformView(1, 5, 60, 50, 50, 50, 50, new FlutterMutatorsStack());
    controller.swapTransactions();
    controller.onEndFrame();

    createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.onDisplayPlatformView(0, 10, 0, 50, 50, 50, 50, new FlutterMutatorsStack());
    controller.onDisplayPlatformView(1, 10, 60, 50, 50, 50, 50, new FlutterMutatorsStack());
    controller.swapTransactions();
    controller.onEndFrame();

    // Replaying the coalesced queuedFrame must reset lastDisplayedChildIndex between Frame 2 and
    // Frame 3 (so neither calls bringToFront()/requestLayout()) and must skip Frame 2's superseded
    // capture without locking the hardware canvas before releasing the capture.
    ShadowLooper.idleMainLooper();
    FlutterMutatorView parent0 = controller.getPlatformViewParent(0);
    FlutterMutatorView parent1 = controller.getPlatformViewParent(1);
    assertTrue(flutterView.indexOfChild(parent0) < flutterView.indexOfChild(parent1));
    assertFalse(flutterView.isLayoutRequested());
    assertFalse(controller.hasCapture(0));
    verify(surface, never()).lockHardwareCanvas();
    verify(producer, times(1)).release();
  }

  @Test
  @Config(
      shadows = {
        ShadowFlutterJNI.class,
        ShadowPlatformTaskQueue.class,
        ShadowRecordingTransaction.class
      },
      minSdk = 34)
  public void onEndFrame_coalescedQueuedFramesRunOnlyLatestCaptureOpsAfterViewOps() {
    ShadowRecordingTransaction.reset();
    PlatformViewsController2 controller =
        new PlatformViewsController2() {
          @Override
          boolean shouldDeferUntilNextTraversal() {
            return true;
          }
        };
    controller.setRegistry(new PlatformViewRegistryImpl());

    Context context = ApplicationProvider.getApplicationContext();
    View view0 = new View(context);
    PlatformViewFactory viewFactory = mock(PlatformViewFactory.class);
    PlatformView platformView = mock(PlatformView.class);
    when(platformView.getView()).thenReturn(view0);
    when(viewFactory.create(any(), eq(0), any())).thenReturn(platformView);
    controller.getRegistry().registerViewFactory("testType", viewFactory);

    FlutterJNI jni = new FlutterJNI();
    jni.attachToNative();
    AttachedSurfaceControl rootSurfaceControl = mock(AttachedSurfaceControl.class);
    FlutterView flutterView =
        new FlutterView(context, new FlutterSurfaceView(context)) {
          @Override
          public AttachedSurfaceControl getRootSurfaceControl() {
            return rootSurfaceControl;
          }
        };
    attachToFlutterView(jni, controller, flutterView);

    TextureRegistry.SurfaceProducer producer = mockCaptureProducer();
    Surface surface = mock(Surface.class);
    when(surface.isValid()).thenReturn(true);
    when(surface.lockHardwareCanvas()).thenReturn(mock(Canvas.class));
    when(producer.getSurface()).thenReturn(surface);
    FlutterRenderer renderer = mock(FlutterRenderer.class);
    when(renderer.createSurfaceProducer()).thenReturn(producer);
    controller.attachToFlutterRenderer(renderer);

    createPlatformView(jni, controller, 0, "testType");

    // Frame 1 displays view 0 at 50x50 and stages the frame for traversal.
    createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.onDisplayPlatformView(0, 0, 0, 50, 50, 50, 50, new FlutterMutatorsStack());
    controller.swapTransactions();
    controller.onEndFrame();

    flutterView.measure(
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
    flutterView.layout(0, 0, 100, 100);
    assertFalse(flutterView.isLayoutRequested());

    // While Frame 1's traversal is staged, Frame 2 requests capture of view 0 at 50x50 with
    // substituted=true, and Frame 3 resizes view 0 to 80x90 and requests capture with
    // substituted=false (prefetch only).
    createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.onDisplayPlatformView(0, 0, 0, 50, 50, 50, 50, new FlutterMutatorsStack());
    assertNotNull(controller.capturePlatformView(0, true));
    controller.swapTransactions();
    controller.onEndFrame();

    createAndPublishRasterTransaction(controller);
    controller.sealFrameTransactions();
    controller.onDisplayPlatformView(0, 0, 0, 80, 90, 80, 90, new FlutterMutatorsStack());
    assertNotNull(controller.capturePlatformView(0, false));
    controller.swapTransactions();
    controller.onEndFrame();

    // Replaying the coalesced queuedFrame must run Frame 3's viewOps before Frame 3's captureOp
    // (skipping Frame 2's superseded captureOp), so capture() sees the pending 80x90 layout request
    // and defers locking the hardware canvas until post-layout draw, while drawToWindow stays true.
    ShadowLooper.idleMainLooper();
    FlutterMutatorView parent0 = controller.getPlatformViewParent(0);
    assertTrue(parent0.getDrawToWindow());
    assertEquals(80, view0.getLayoutParams().width);
    assertEquals(90, view0.getLayoutParams().height);
    assertTrue(parent0.isLayoutRequested());
    assertTrue(controller.hasCapture(0));
    verify(surface, never()).lockHardwareCanvas();
  }

  private static ByteBuffer encodeMethodCall(MethodCall call) {
    final ByteBuffer buffer = StandardMethodCodec.INSTANCE.encodeMethodCall(call);
    buffer.rewind();
    return buffer;
  }

  private static void createPlatformView(
      FlutterJNI jni,
      PlatformViewsController2 PlatformViewsController2,
      int platformViewId,
      String viewType) {
    final Map<String, Object> args = new HashMap<>();
    args.put("id", platformViewId);
    args.put("viewType", viewType);
    args.put("direction", 0);
    args.put("width", 1.0);
    args.put("height", 1.0);

    final MethodCall platformCreateMethodCall = new MethodCall("create", args);

    jni.handlePlatformMessage(
        "flutter/platform_views_2",
        encodeMethodCall(platformCreateMethodCall),
        /*replyId=*/ 0,
        /*messageData=*/ 0);
  }

  private static void setLayoutDirection(
      FlutterJNI jni,
      PlatformViewsController2 PlatformViewsController2,
      int platformViewId,
      int direction) {
    final Map<String, Object> args = new HashMap<>();
    args.put("id", platformViewId);
    args.put("direction", direction);

    final MethodCall platformSetDirectionMethodCall = new MethodCall("setDirection", args);

    jni.handlePlatformMessage(
        "flutter/platform_views_2",
        encodeMethodCall(platformSetDirectionMethodCall),
        /*replyId=*/ 0,
        /*messageData=*/ 0);
  }

  private static void disposePlatformView(
      FlutterJNI jni, PlatformViewsController2 PlatformViewsController2, int platformViewId) {

    final Map<String, Object> args = new HashMap<>();
    args.put("id", platformViewId);

    final MethodCall platformDisposeMethodCall = new MethodCall("dispose", args);

    jni.handlePlatformMessage(
        "flutter/platform_views_2",
        encodeMethodCall(platformDisposeMethodCall),
        /*replyId=*/ 0,
        /*messageData=*/ 0);
  }

  private static void rejectGesturePlatformView(
      FlutterJNI jni,
      PlatformViewsController2 platformViewsController,
      int platformViewId,
      long gestureId) {
    final Map<String, Object> args = new HashMap<>();
    args.put("id", platformViewId);
    args.put("gestureId", gestureId);

    final MethodCall platformRejectGestureMethodCall = new MethodCall("rejectGesture", args);

    jni.handlePlatformMessage(
        "flutter/platform_views_2",
        encodeMethodCall(platformRejectGestureMethodCall),
        /*replyId=*/ 0,
        /*messageData=*/ 0);
  }

  private static void synchronizeToNativeViewHierarchy(
      FlutterJNI jni, PlatformViewsController2 PlatformViewsController2, boolean yes) {

    final MethodCall convertMethodCall = new MethodCall("synchronizeToNativeViewHierarchy", yes);

    jni.handlePlatformMessage(
        "flutter/platform_views_2",
        encodeMethodCall(convertMethodCall),
        /*replyId=*/ 0,
        /*messageData=*/ 0);
  }

  private static FlutterView attach(
      FlutterJNI jni, PlatformViewsController2 PlatformViewsController2) {
    final Context context = ApplicationProvider.getApplicationContext();
    final FlutterView flutterView =
        new FlutterView(context, new FlutterSurfaceView(context)) {
          @Override
          public FlutterImageView createImageView() {
            final FlutterImageView view = mock(FlutterImageView.class);
            when(view.acquireLatestImage()).thenReturn(true);
            return mock(FlutterImageView.class);
          }
        };
    attachToFlutterView(jni, PlatformViewsController2, flutterView);
    return flutterView;
  }

  private static void attachToFlutterView(
      FlutterJNI jni, PlatformViewsController2 PlatformViewsController2, FlutterView flutterView) {
    final DartExecutor executor = new DartExecutor(jni, mock(AssetManager.class));
    executor.onAttachedToJNI();
    final TextureRegistry registry =
        spy(
            new TextureRegistry() {
              public void TextureRegistry() {}

              @NonNull
              @Override
              public SurfaceTextureEntry createSurfaceTexture() {
                return registerSurfaceTexture(mock(SurfaceTexture.class));
              }

              @NonNull
              @Override
              public SurfaceTextureEntry registerSurfaceTexture(
                  @NonNull SurfaceTexture surfaceTexture) {
                return new SurfaceTextureEntry() {
                  @NonNull
                  @Override
                  public SurfaceTexture surfaceTexture() {
                    return mock(SurfaceTexture.class);
                  }

                  @Override
                  public long id() {
                    return 0;
                  }

                  @Override
                  public void release() {}
                };
              }

              @NonNull
              @Override
              public ImageTextureEntry createImageTexture() {
                return new ImageTextureEntry() {
                  @Override
                  public long id() {
                    return 0;
                  }

                  @Override
                  public void release() {}

                  @Override
                  public void pushImage(Image image) {}
                };
              }

              @NonNull
              @Override
              public SurfaceProducer createSurfaceProducer(SurfaceLifecycle lifecycle) {
                return new SurfaceProducer() {
                  @Override
                  public void setCallback(SurfaceProducer.Callback cb) {}

                  @Override
                  public long id() {
                    return 0;
                  }

                  @Override
                  public void release() {}

                  @Override
                  public int getWidth() {
                    return 0;
                  }

                  @Override
                  public int getHeight() {
                    return 0;
                  }

                  @Override
                  public void setSize(int width, int height) {}

                  @Override
                  public Surface getSurface() {
                    return null;
                  }

                  @Override
                  public Surface getForcedNewSurface() {
                    return null;
                  }

                  @Override
                  public boolean handlesCropAndRotation() {
                    return false;
                  }

                  public void scheduleFrame() {}
                };
              }
            });

    final Context context = ApplicationProvider.getApplicationContext();

    PlatformViewsController oldController = new PlatformViewsController();

    PlatformViewsControllerDelegator platformViewsControllerDelegator =
        new PlatformViewsControllerDelegator(oldController, PlatformViewsController2);

    final FlutterEngine engine = mock(FlutterEngine.class);
    when(engine.getRenderer()).thenReturn(new FlutterRenderer(jni));
    when(engine.getMouseCursorChannel()).thenReturn(mock(MouseCursorChannel.class));
    when(engine.getTextInputChannel()).thenReturn(mock(TextInputChannel.class));
    when(engine.getSettingsChannel()).thenReturn(new SettingsChannel(executor));
    when(engine.getScribeChannel()).thenReturn(mock(ScribeChannel.class));
    when(engine.getPlatformViewsController2()).thenReturn(PlatformViewsController2);
    when(engine.getPlatformViewsController()).thenReturn(oldController);
    when(engine.getPlatformViewsControllerDelegator()).thenReturn(platformViewsControllerDelegator);
    when(engine.getLocalizationPlugin()).thenReturn(mock(LocalizationPlugin.class));
    when(engine.getAccessibilityChannel()).thenReturn(mock(AccessibilityChannel.class));
    when(engine.getDartExecutor()).thenReturn(executor);

    flutterView.attachToFlutterEngine(engine);
    PlatformViewsController2.attachToView(flutterView);
    platformViewsControllerDelegator.attach(context, registry, executor);
  }

  /**
   * For convenience when writing tests, this allows us to make fake messages from Flutter via
   * Platform Channels. Typically those calls happen on the ui thread which dispatches to the
   * platform thread. Since tests run on the platform thread it makes it difficult to test without
   * this, but isn't technically required.
   */
  @Implements(io.flutter.embedding.engine.dart.PlatformTaskQueue.class)
  public static class ShadowPlatformTaskQueue {
    @Implementation
    public void dispatch(Runnable runnable) {
      runnable.run();
    }
  }

  /**
   * The shadow class of {@link Presentation} to simulate Presentation showing logic.
   *
   * <p>Robolectric doesn't support VirtualDisplay creating correctly now, so this shadow class is
   * used to simulate custom logic for Presentation.
   */
  @Implements(Presentation.class)
  public static class ShadowPresentation extends ShadowDialog {
    private boolean isShowing = false;

    public ShadowPresentation() {}

    @Implementation
    protected void show() {
      isShowing = true;
    }

    @Implementation
    protected void dismiss() {
      isShowing = false;
    }

    @Implementation
    protected boolean isShowing() {
      return isShowing;
    }
  }

  @Implements(FlutterJNI.class)
  public static class ShadowFlutterJNI {
    private static SparseArray<ByteBuffer> replies = new SparseArray<>();
    static int scheduleFrameCalls = 0;

    public ShadowFlutterJNI() {}

    @Implementation
    public void scheduleFrame() {
      scheduleFrameCalls++;
    }

    @Implementation
    public boolean getIsSoftwareRenderingEnabled() {
      return false;
    }

    @Implementation
    public long performNativeAttach(FlutterJNI flutterJNI) {
      return 1;
    }

    @Implementation
    public void dispatchPlatformMessage(
        String channel, ByteBuffer message, int position, int responseId) {}

    @Implementation
    public void onSurfaceCreated(Surface surface) {}

    @Implementation
    public void onSurfaceDestroyed() {}

    @Implementation
    public void onSurfaceWindowChanged(Surface surface) {}

    @Implementation
    public void setViewportMetrics(
        float devicePixelRatio,
        int physicalWidth,
        int physicalHeight,
        int physicalPaddingTop,
        int physicalPaddingRight,
        int physicalPaddingBottom,
        int physicalPaddingLeft,
        int physicalViewInsetTop,
        int physicalViewInsetRight,
        int physicalViewInsetBottom,
        int physicalViewInsetLeft,
        int systemGestureInsetTop,
        int systemGestureInsetRight,
        int systemGestureInsetBottom,
        int systemGestureInsetLeft,
        int physicalTouchSlop,
        int[] displayFeaturesBounds,
        int[] displayFeaturesType,
        int[] displayFeaturesState) {}

    @Implementation
    public void invokePlatformMessageResponseCallback(
        int responseId, ByteBuffer message, int position) {
      replies.put(responseId, message);
    }

    public static SparseArray<ByteBuffer> getResponses() {
      return replies;
    }
  }

  @Implements(SurfaceView.class)
  public static class ShadowFlutterSurfaceView extends ShadowSurfaceView {
    private final FakeSurfaceHolder holder = new FakeSurfaceHolder();

    public static class FakeSurfaceHolder extends ShadowSurfaceView.FakeSurfaceHolder {
      private final Surface surface = mock(Surface.class);

      public Surface getSurface() {
        return surface;
      }

      @Implementation
      public void addCallback(SurfaceHolder.Callback callback) {
        callback.surfaceCreated(this);
      }
    }

    public ShadowFlutterSurfaceView() {}

    @Implementation
    public SurfaceHolder getHolder() {
      return holder;
    }
  }

  /**
   * Mirrors the native raster-thread flow: create an unpublished transaction, then publish it once
   * native writes are done. Tests that do not model in-flight writes publish immediately.
   */
  private static SurfaceControl.Transaction createAndPublishRasterTransaction(
      PlatformViewsController2 controller) {
    final SurfaceControl.Transaction tx = controller.createUnpublishedTransaction();
    controller.publishTransaction(tx);
    return tx;
  }

  /** Records every transaction merged into another, so tests can see what a frame picked up. */
  @Implements(SurfaceControl.Transaction.class)
  public static class ShadowRecordingTransaction {
    static final List<SurfaceControl.Transaction> merged = new ArrayList<>();

    @RealObject private SurfaceControl.Transaction realObject;

    static void reset() {
      merged.clear();
    }

    @Implementation
    protected SurfaceControl.Transaction merge(SurfaceControl.Transaction other) {
      merged.add(other);
      return realObject;
    }
  }
}
