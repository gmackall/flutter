// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine.renderer;

import static android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
import static android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.shadows.ShadowLooper.shadowMainLooper;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.SyncFence;
import android.media.Image;
import android.media.ImageReader;
import android.view.Surface;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.view.TextureRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;

@RunWith(AndroidJUnit4.class)
public class FlutterRendererTest {
  @Rule(order = 1)
  public final FlutterEngineRule engineRule = new FlutterEngineRule();

  @Rule(order = 2)
  public final ActivityScenarioRule<FlutterActivity> scenarioRule =
      new ActivityScenarioRule<>(engineRule.makeIntent());

  private FlutterJNI fakeFlutterJNI;

  @Before
  public void init() {
    // Uncomment the following line to enable logging output in test.
    // ShadowLog.stream = System.out;
  }

  @Before
  public void setup() {
    FlutterRenderer.debugUseBackgroundImageReaderThread = true;
    fakeFlutterJNI = engineRule.getFlutterJNI();
  }

  private static void idleImageReaderAndMainLoopers(
      FlutterRenderer.ImageReaderSurfaceProducer texture) {
    org.robolectric.Shadows.shadowOf(texture.getImageReaderLooperForTest()).idle();
    shadowMainLooper().idle();
  }

  @Test
  public void itForwardsSurfaceCreationNotificationToFlutterJNI() {
    // Setup the test.
    Surface fakeSurface = mock(Surface.class);
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    // Execute the behavior under test.
    flutterRenderer.startRenderingToSurface(fakeSurface, false);

    // Verify the behavior under test.
    verify(fakeFlutterJNI, times(1)).onSurfaceCreated(eq(fakeSurface));
  }

  @Test
  public void itForwardsSurfaceChangeNotificationToFlutterJNI() {
    // Setup the test.
    Surface fakeSurface = mock(Surface.class);
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    flutterRenderer.startRenderingToSurface(fakeSurface, false);

    // Execute the behavior under test.
    flutterRenderer.surfaceChanged(100, 50);

    // Verify the behavior under test.
    verify(fakeFlutterJNI, times(1)).onSurfaceChanged(eq(100), eq(50));
  }

  @Test
  public void itForwardsSurfaceDestructionNotificationToFlutterJNI() {
    // Setup the test.
    Surface fakeSurface = mock(Surface.class);
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    flutterRenderer.startRenderingToSurface(fakeSurface, false);

    // Execute the behavior under test.
    flutterRenderer.stopRenderingToSurface();

    // Verify the behavior under test.
    verify(fakeFlutterJNI, times(1)).onSurfaceDestroyed();
  }

  @Test
  public void itStopsRenderingToOneSurfaceBeforeRenderingToANewSurface() {
    // Setup the test.
    Surface fakeSurface = mock(Surface.class);
    Surface fakeSurface2 = mock(Surface.class);
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    flutterRenderer.startRenderingToSurface(fakeSurface, false);

    // Execute behavior under test.
    flutterRenderer.startRenderingToSurface(fakeSurface2, false);

    // Verify behavior under test.
    verify(fakeFlutterJNI, times(1)).onSurfaceDestroyed(); // notification of 1st surface's removal.
  }

  @Test
  public void itStopsRenderingToSurfaceWhenRequested() {
    // Setup the test.
    Surface fakeSurface = mock(Surface.class);
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    flutterRenderer.startRenderingToSurface(fakeSurface, false);

    // Execute the behavior under test.
    flutterRenderer.stopRenderingToSurface();

    // Verify behavior under test.
    verify(fakeFlutterJNI, times(1)).onSurfaceDestroyed();
  }

  @Test
  public void iStopsRenderingToSurfaceWhenSurfaceAlreadySet() {
    // Setup the test.
    Surface fakeSurface = mock(Surface.class);
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    flutterRenderer.startRenderingToSurface(fakeSurface, false);
    flutterRenderer.startRenderingToSurface(fakeSurface, false);

    // Verify behavior under test.
    verify(fakeFlutterJNI, times(1)).onSurfaceDestroyed();
  }

  @Test
  public void itNeverStopsRenderingToSurfaceWhenRequested() {
    // Setup the test.
    Surface fakeSurface = mock(Surface.class);
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    flutterRenderer.startRenderingToSurface(fakeSurface, false);
    flutterRenderer.startRenderingToSurface(fakeSurface, true);

    // Verify behavior under test.
    verify(fakeFlutterJNI, never()).onSurfaceDestroyed();
  }

  @Test
  public void itStopsSurfaceTextureCallbackWhenDetached() {
    // Setup the test.
    Surface fakeSurface = mock(Surface.class);
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    FlutterRenderer.SurfaceTextureRegistryEntry entry =
        (FlutterRenderer.SurfaceTextureRegistryEntry) flutterRenderer.createSurfaceTexture();
    flutterRenderer.startRenderingToSurface(fakeSurface, false);

    // Execute the behavior under test.
    flutterRenderer.stopRenderingToSurface();

    // Verify behavior under test.
    verify(fakeFlutterJNI, times(0)).markTextureFrameAvailable(eq(entry.id()));
  }

  @Test
  public void itRegistersExistingSurfaceTexture() {
    // Setup the test.
    Surface fakeSurface = mock(Surface.class);
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    SurfaceTexture surfaceTexture = new SurfaceTexture(0);

    // Execute the behavior under test.
    FlutterRenderer.SurfaceTextureRegistryEntry entry =
        (FlutterRenderer.SurfaceTextureRegistryEntry)
            flutterRenderer.registerSurfaceTexture(surfaceTexture);

    flutterRenderer.startRenderingToSurface(fakeSurface, false);

    // Verify behavior under test.
    assertEquals(surfaceTexture, entry.surfaceTexture());

    verify(fakeFlutterJNI, times(1)).registerTexture(eq(entry.id()), eq(entry.textureWrapper()));
  }

  @Test
  public void itUnregistersTextureWhenSurfaceTextureFinalized() {
    // Setup the test.
    Surface fakeSurface = mock(Surface.class);
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    FlutterRenderer.SurfaceTextureRegistryEntry entry =
        (FlutterRenderer.SurfaceTextureRegistryEntry) flutterRenderer.createSurfaceTexture();
    long id = entry.id();

    flutterRenderer.startRenderingToSurface(fakeSurface, false);

    // Execute the behavior under test.
    runFinalization(entry);

    shadowMainLooper().idle();

    flutterRenderer.stopRenderingToSurface();

    // Verify behavior under test.
    verify(fakeFlutterJNI, times(1)).unregisterTexture(eq(id));
  }

  @Test
  public void itStopsUnregisteringTextureWhenDetached() {
    // Setup the test.
    Surface fakeSurface = mock(Surface.class);
    engineRule.setJniIsAttached(false);
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    FlutterRenderer.SurfaceTextureRegistryEntry entry =
        (FlutterRenderer.SurfaceTextureRegistryEntry) flutterRenderer.createSurfaceTexture();
    long id = entry.id();

    flutterRenderer.startRenderingToSurface(fakeSurface, false);
    flutterRenderer.stopRenderingToSurface();

    // Execute the behavior under test.
    runFinalization(entry);

    shadowMainLooper().idle();

    // Verify behavior under test.
    verify(fakeFlutterJNI, times(0)).unregisterTexture(eq(id));
  }

  /** @noinspection FinalizeCalledExplicitly */
  void runFinalization(FlutterRenderer.SurfaceTextureRegistryEntry entry) {
    CountDownLatch latch = new CountDownLatch(1);
    Thread fakeFinalizer =
        new Thread(
            () -> {
              try {
                entry.finalize();
                latch.countDown();
              } catch (Throwable e) {
                // do nothing
              }
            });
    fakeFinalizer.start();
    try {
      latch.await();
    } catch (InterruptedException e) {
      // do nothing
    }
  }

  @Test
  public void itConvertsDisplayFeatureArrayToPrimitiveArrays() {
    // Intentionally do not use 'engineRule' in this test, because we are testing a very narrow
    // API (the side-effects of 'setViewportMetrics'). Under normal construction, the engine will
    // invoke 'setViewportMetrics' a number of times automatically, making testing the side-effects
    // of the method call more difficult than needed.
    FlutterJNI fakeFlutterJNI = mock(FlutterJNI.class);
    FlutterRenderer flutterRenderer = new FlutterRenderer(fakeFlutterJNI);

    // Setup the test.
    FlutterRenderer.ViewportMetrics metrics = new FlutterRenderer.ViewportMetrics();
    metrics.width = 1000;
    metrics.height = 1000;
    metrics.devicePixelRatio = 2;
    metrics
        .getDisplayFeatures()
        .add(
            new FlutterRenderer.DisplayFeature(
                new Rect(10, 20, 30, 40),
                FlutterRenderer.DisplayFeatureType.FOLD,
                FlutterRenderer.DisplayFeatureState.POSTURE_HALF_OPENED));
    metrics
        .getDisplayCutouts()
        .add(
            new FlutterRenderer.DisplayFeature(
                new Rect(50, 60, 70, 80),
                FlutterRenderer.DisplayFeatureType.CUTOUT,
                FlutterRenderer.DisplayFeatureState.UNKNOWN));

    // Execute the behavior under test.
    flutterRenderer.setViewportMetrics(metrics);

    // Verify behavior under test.
    ArgumentCaptor<int[]> boundsCaptor = ArgumentCaptor.forClass(int[].class);
    ArgumentCaptor<int[]> typeCaptor = ArgumentCaptor.forClass(int[].class);
    ArgumentCaptor<int[]> stateCaptor = ArgumentCaptor.forClass(int[].class);
    verify(fakeFlutterJNI)
        .setViewportMetrics(
            anyFloat(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            boundsCaptor.capture(),
            typeCaptor.capture(),
            stateCaptor.capture(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt());

    assertArrayEquals(new int[] {10, 20, 30, 40, 50, 60, 70, 80}, boundsCaptor.getValue());
    assertArrayEquals(
        new int[] {
          FlutterRenderer.DisplayFeatureType.FOLD.encodedValue,
          FlutterRenderer.DisplayFeatureType.CUTOUT.encodedValue
        },
        typeCaptor.getValue());
    assertArrayEquals(
        new int[] {
          FlutterRenderer.DisplayFeatureState.POSTURE_HALF_OPENED.encodedValue,
          FlutterRenderer.DisplayFeatureState.UNKNOWN.encodedValue
        },
        stateCaptor.getValue());
  }

  @Test
  public void itNotifyImageFrameListener() {
    // Setup the test.
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    AtomicInteger invocationCount = new AtomicInteger(0);
    final TextureRegistry.OnFrameConsumedListener listener = invocationCount::incrementAndGet;

    FlutterRenderer.SurfaceTextureRegistryEntry entry =
        (FlutterRenderer.SurfaceTextureRegistryEntry) flutterRenderer.createSurfaceTexture();
    entry.setOnFrameConsumedListener(listener);

    // Execute the behavior under test.
    entry.textureWrapper().updateTexImage();

    // Verify behavior under test.
    assertEquals(1, invocationCount.get());
  }

  @Test
  public void itAddsListenerWhenSurfaceTextureEntryCreated() {
    // Setup the test.
    FlutterRenderer flutterRenderer = spy(engineRule.getFlutterEngine().getRenderer());

    // Execute the behavior under test.
    FlutterRenderer.SurfaceTextureRegistryEntry entry =
        (FlutterRenderer.SurfaceTextureRegistryEntry) flutterRenderer.createSurfaceTexture();

    // Verify behavior under test.
    verify(flutterRenderer, times(1)).addOnTrimMemoryListener(entry);
  }

  @Test
  public void itRemovesListenerWhenSurfaceTextureEntryReleased() {
    // Setup the test.
    FlutterRenderer flutterRenderer = spy(engineRule.getFlutterEngine().getRenderer());
    FlutterRenderer.SurfaceTextureRegistryEntry entry =
        (FlutterRenderer.SurfaceTextureRegistryEntry) flutterRenderer.createSurfaceTexture();

    // Execute the behavior under test.
    entry.release();

    // Verify behavior under test.
    verify(flutterRenderer, times(1)).removeOnTrimMemoryListener(entry);
  }

  @Test
  @SuppressWarnings("deprecation")
  // TRIM_MEMORY_COMPLETE
  public void itNotifySurfaceTextureEntryWhenMemoryPressureWarning() {
    // Setup the test.
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    AtomicInteger invocationCount = new AtomicInteger(0);
    final TextureRegistry.OnTrimMemoryListener listener =
        level -> invocationCount.incrementAndGet();

    FlutterRenderer.SurfaceTextureRegistryEntry entry =
        (FlutterRenderer.SurfaceTextureRegistryEntry) flutterRenderer.createSurfaceTexture();
    entry.setOnTrimMemoryListener(listener);

    // Execute the behavior under test.
    flutterRenderer.onTrimMemory(TRIM_MEMORY_COMPLETE);

    // Verify behavior under test.
    assertEquals(1, invocationCount.get());
  }

  @Test
  public void itDoesDispatchSurfaceDestructionNotificationOnlyOnce() {
    // Setup the test.
    Surface fakeSurface = mock(Surface.class);
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    flutterRenderer.startRenderingToSurface(fakeSurface, false);

    // Execute the behavior under test.
    // Simulate calling |FlutterRenderer#stopRenderingToSurface| twice with different code paths.
    flutterRenderer.stopRenderingToSurface();
    flutterRenderer.stopRenderingToSurface();

    // Verify behavior under test.
    verify(fakeFlutterJNI, times(1)).onSurfaceDestroyed();
  }

  @Test
  public void itInvokesCreatesSurfaceWhenStartingRendering() {
    Surface fakeSurface = mock(Surface.class);
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    flutterRenderer.startRenderingToSurface(fakeSurface, false);
    verify(fakeFlutterJNI, times(1)).onSurfaceCreated(eq(fakeSurface));
  }

  @Test
  public void itDoesNotInvokeCreatesSurfaceWhenResumingRendering() {
    Surface fakeSurface = mock(Surface.class);
    Surface fakeSurface2 = mock(Surface.class);
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    // The following call sequence mimics the behaviour of FlutterView when it exits from hybrid
    // composition mode.

    // Install initial rendering surface.
    flutterRenderer.startRenderingToSurface(fakeSurface, false);
    verify(fakeFlutterJNI, times(1)).onSurfaceCreated(eq(fakeSurface));
    verify(fakeFlutterJNI, times(0)).onSurfaceWindowChanged(eq(fakeSurface));

    // Install the image view.
    flutterRenderer.startRenderingToSurface(fakeSurface2, true);
    verify(fakeFlutterJNI, times(1)).onSurfaceCreated(eq(fakeSurface));
    verify(fakeFlutterJNI, times(0)).onSurfaceWindowChanged(eq(fakeSurface));
    verify(fakeFlutterJNI, times(0)).onSurfaceCreated(eq(fakeSurface2));
    verify(fakeFlutterJNI, times(1)).onSurfaceWindowChanged(eq(fakeSurface2));

    flutterRenderer.startRenderingToSurface(fakeSurface, true);
    verify(fakeFlutterJNI, times(1)).onSurfaceCreated(eq(fakeSurface));
    verify(fakeFlutterJNI, times(1)).onSurfaceWindowChanged(eq(fakeSurface));
    verify(fakeFlutterJNI, times(0)).onSurfaceCreated(eq(fakeSurface2));
    verify(fakeFlutterJNI, times(1)).onSurfaceWindowChanged(eq(fakeSurface2));
  }

  @Test
  public void ImageReaderSurfaceProducerProducesImageOfCorrectSize() {
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
    TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();
    FlutterRenderer.ImageReaderSurfaceProducer texture =
        (FlutterRenderer.ImageReaderSurfaceProducer) producer;
    texture.disableFenceForTest();

    // Returns a null image when one hasn't been produced.
    assertNull(texture.acquireLatestImage());

    // Give the texture an initial size.
    texture.setSize(1, 1);

    // Render a frame.
    Surface surface = texture.getSurface();
    assertNotNull(surface);
    Canvas canvas = surface.lockHardwareCanvas();
    canvas.drawARGB(255, 255, 0, 0);
    surface.unlockCanvasAndPost(canvas);

    // Let callbacks run.
    idleImageReaderAndMainLoopers(texture);

    // Extract the image and check its size.
    Image image = texture.acquireLatestImage();
    assert image != null;
    assertEquals(1, image.getWidth());
    assertEquals(1, image.getHeight());
    image.close();

    // Resize the texture.
    texture.setSize(5, 5);

    // Render a frame.
    surface = texture.getSurface();
    assertNotNull(surface);
    canvas = surface.lockHardwareCanvas();
    canvas.drawARGB(255, 255, 0, 0);
    surface.unlockCanvasAndPost(canvas);

    // Let callbacks run.
    idleImageReaderAndMainLoopers(texture);

    // Extract the image and check its size.
    image = texture.acquireLatestImage();
    assert image != null;
    assertEquals(5, image.getWidth());
    assertEquals(5, image.getHeight());
    image.close();

    assertNull(texture.acquireLatestImage());

    texture.release();
  }

  @Test
  public void ImageReaderSurfaceProducerDoesNotDropFramesWhenResizeInFlight() {
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
    TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();
    FlutterRenderer.ImageReaderSurfaceProducer texture =
        (FlutterRenderer.ImageReaderSurfaceProducer) producer;
    texture.disableFenceForTest();

    // Returns a null image when one hasn't been produced.
    assertNull(texture.acquireLatestImage());

    // Give the texture an initial size.
    texture.setSize(1, 1);

    // Render a frame.
    Surface surface = texture.getSurface();
    assertNotNull(surface);
    Canvas canvas = surface.lockHardwareCanvas();
    canvas.drawARGB(255, 255, 0, 0);
    surface.unlockCanvasAndPost(canvas);

    // Resize.
    texture.setSize(4, 4);

    // Let callbacks run. The rendered frame will manifest here.
    idleImageReaderAndMainLoopers(texture);

    // We acquired the frame produced above.
    assertNotNull(texture.acquireLatestImage());
  }

  @Test
  public void ImageReaderSurfaceProducerImageReadersAndImagesCount() {
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
    TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();
    FlutterRenderer.ImageReaderSurfaceProducer texture =
        (FlutterRenderer.ImageReaderSurfaceProducer) producer;
    texture.disableFenceForTest();

    // Returns a null image when one hasn't been produced.
    assertNull(texture.acquireLatestImage());

    // Give the texture an initial size.
    texture.setSize(1, 1);

    // Grab the surface so we can render a frame at 1x1 after resizing.
    Surface surface = texture.getSurface();
    assertNotNull(surface);
    Canvas canvas = surface.lockHardwareCanvas();
    canvas.drawARGB(255, 255, 0, 0);
    surface.unlockCanvasAndPost(canvas);

    // Let callbacks run, this will produce a single frame.
    idleImageReaderAndMainLoopers(texture);

    assertEquals(1, texture.numImageReaders());
    assertEquals(1, texture.numImages());
    assertEquals(0, texture.pendingDequeuedImages());

    // Resize.
    texture.setSize(4, 4);

    // Render a frame at the old size (by using the pre-resized Surface)
    canvas = surface.lockHardwareCanvas();
    canvas.drawARGB(255, 255, 0, 0);
    surface.unlockCanvasAndPost(canvas);

    // Let callbacks run.
    idleImageReaderAndMainLoopers(texture);

    assertEquals(1, texture.numImageReaders());
    assertEquals(2, texture.numImages());
    assertEquals(0, texture.pendingDequeuedImages());

    // Render a new frame with the current size.
    surface = texture.getSurface();
    assertNotNull(surface);
    canvas = surface.lockHardwareCanvas();
    canvas.drawARGB(255, 255, 0, 0);
    surface.unlockCanvasAndPost(canvas);

    // Let callbacks run.
    idleImageReaderAndMainLoopers(texture);

    assertEquals(2, texture.numImageReaders());
    assertEquals(3, texture.numImages());
    assertEquals(0, texture.pendingDequeuedImages());

    // Acquire first frame.
    Image produced = texture.acquireLatestImage();
    assertNotNull(produced);
    assertEquals(1, produced.getWidth());
    assertEquals(1, produced.getHeight());
    assertEquals(2, texture.numImageReaders());
    assertEquals(2, texture.numImages());
    assertEquals(1, texture.pendingDequeuedImages());

    // Acquire second frame. This won't result in the first reader being closed because it has
    // an active image from it.
    produced = texture.acquireLatestImage();
    assertNotNull(produced);
    assertEquals(1, produced.getWidth());
    assertEquals(1, produced.getHeight());
    assertEquals(2, texture.numImageReaders());
    assertEquals(1, texture.numImages());
    assertEquals(2, texture.pendingDequeuedImages());

    // Acquire third frame. We will now close the first reader.
    produced = texture.acquireLatestImage();
    assertNotNull(produced);
    assertEquals(4, produced.getWidth());
    assertEquals(4, produced.getHeight());
    assertEquals(1, texture.numImageReaders());
    assertEquals(0, texture.numImages());
    assertEquals(3, texture.pendingDequeuedImages());

    // Returns null image when no more images are queued.
    assertNull(texture.acquireLatestImage());
    assertEquals(1, texture.numImageReaders());
    assertEquals(0, texture.numImages());
    assertEquals(3, texture.pendingDequeuedImages());
  }

  @Test
  public void ImageReaderSurfaceProducerDequeueManyImages() {
    // Demonstrates maximum dequeued image count.
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
    TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();
    FlutterRenderer.ImageReaderSurfaceProducer texture =
        (FlutterRenderer.ImageReaderSurfaceProducer) producer;
    texture.disableFenceForTest();

    // Give the texture an initial size.
    texture.setSize(1, 1);

    Surface surface = texture.getSurface();
    Canvas canvas = surface.lockHardwareCanvas();
    canvas.drawARGB(255, 255, 0, 0);
    surface.unlockCanvasAndPost(canvas);
    idleImageReaderAndMainLoopers(texture);

    // Acquire first frame.
    Image produced = texture.acquireLatestImage();
    assertNotNull(produced);
    assertEquals(1, texture.pendingDequeuedImages());

    canvas = surface.lockHardwareCanvas();
    canvas.drawARGB(255, 255, 0, 0);
    surface.unlockCanvasAndPost(canvas);
    idleImageReaderAndMainLoopers(texture);

    // 2
    produced = texture.acquireLatestImage();
    assertEquals(2, texture.pendingDequeuedImages());

    canvas = surface.lockHardwareCanvas();
    canvas.drawARGB(255, 255, 0, 0);
    surface.unlockCanvasAndPost(canvas);
    idleImageReaderAndMainLoopers(texture);

    // 3
    produced = texture.acquireLatestImage();
    assertEquals(3, texture.pendingDequeuedImages());

    canvas = surface.lockHardwareCanvas();
    canvas.drawARGB(255, 255, 0, 0);
    surface.unlockCanvasAndPost(canvas);
    idleImageReaderAndMainLoopers(texture);

    // 4
    produced = texture.acquireLatestImage();
    assertEquals(3, texture.pendingDequeuedImages());
  }

  @Test
  public void ImageReaderSurfaceProducerTrimMemoryCallback() {
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
    TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();
    FlutterRenderer.ImageReaderSurfaceProducer texture =
        (FlutterRenderer.ImageReaderSurfaceProducer) producer;

    texture.disableFenceForTest();

    // Returns a null image when one hasn't been produced.
    assertNull(texture.acquireLatestImage());

    // Give the texture an initial size.
    texture.setSize(1, 1);

    // Grab the surface so we can render a frame at 1x1 after resizing.
    Surface surface = texture.getSurface();
    assertNotNull(surface);
    Canvas canvas = surface.lockHardwareCanvas();
    canvas.drawARGB(255, 255, 0, 0);
    surface.unlockCanvasAndPost(canvas);

    // Let callbacks run, this will produce a single frame.
    idleImageReaderAndMainLoopers(texture);

    assertEquals(1, texture.numImageReaders());
    assertEquals(1, texture.numImages());

    // Invoke the onTrimMemory callback with level 0.
    // This should do nothing.
    texture.onTrimMemory(0);
    idleImageReaderAndMainLoopers(texture);

    assertEquals(1, texture.numImageReaders());
    assertEquals(1, texture.numImages());
    assertEquals(0, texture.numTrims());

    // Invoke the onTrimMemory callback with level 40.
    // This should result in a trim.
    texture.onTrimMemory(TRIM_MEMORY_BACKGROUND);
    idleImageReaderAndMainLoopers(texture);

    assertEquals(0, texture.numImageReaders());
    assertEquals(0, texture.numImages());
    assertEquals(1, texture.numTrims());

    // Request the surface, this should result in a new image reader.
    surface = texture.getSurface();
    assertEquals(1, texture.numImageReaders());
    assertEquals(0, texture.numImages());
    assertEquals(1, texture.numTrims());

    // Render an image.
    canvas = surface.lockHardwareCanvas();
    canvas.drawARGB(255, 255, 0, 0);
    surface.unlockCanvasAndPost(canvas);

    // Let callbacks run, this will produce a single frame.
    idleImageReaderAndMainLoopers(texture);

    assertEquals(1, texture.numImageReaders());
    assertEquals(1, texture.numImages());
    assertEquals(1, texture.numTrims());
  }

  // A 0x0 ImageReader is a runtime error.
  @Test
  public void ImageReaderSurfaceProducerClampsWidthAndHeightTo1() {
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
    TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();

    // Default values.
    assertEquals(1, producer.getWidth());
    assertEquals(1, producer.getHeight());

    // Try setting width and height to 0.
    producer.setSize(0, 0);

    // Ensure we can still create/get a surface without an exception being raised.
    assertNotNull(producer.getSurface());

    // Expect clamp to 1.
    assertEquals(1, producer.getWidth());
    assertEquals(1, producer.getHeight());
  }

  @Test
  public void SurfaceTextureSurfaceProducerCreatesAConnectedTexture() {
    // Force creating a SurfaceTextureSurfaceProducer regardless of Android API version.
    Surface fakeSurface = mock(Surface.class);
    try {
      FlutterRenderer.debugForceSurfaceProducerGlTextures = true;
      FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
      TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();

      flutterRenderer.startRenderingToSurface(fakeSurface, false);

      // Verify behavior under test.
      assertEquals(0, producer.id());
      verify(fakeFlutterJNI, times(1)).registerTexture(eq(producer.id()), any());
    } finally {
      FlutterRenderer.debugForceSurfaceProducerGlTextures = false;
    }
  }

  @Test
  public void SurfaceTextureSurfaceProducerDoesNotCropOrRotate() {
    try {
      FlutterRenderer.debugForceSurfaceProducerGlTextures = true;
      FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
      TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();

      assertTrue(producer.handlesCropAndRotation());
    } finally {
      FlutterRenderer.debugForceSurfaceProducerGlTextures = false;
    }
  }

  @Test
  @Config(sdk = 29)
  public void createSurfaceProducer_usesSurfaceTextureWhenHardwareBufferDefect() {
    // Setup: Simulate a Huawei device on API 29 where HardwareBuffer has known defects.
    // See: https://github.com/flutter/flutter/issues/166481
    ShadowBuild.setManufacturer("HUAWEI");

    // Execute the behavior under test.
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
    TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();

    // Verify: Should return SurfaceTextureSurfaceProducer instead of ImageReaderSurfaceProducer.
    assertTrue(
        "Expected SurfaceTextureSurfaceProducer on Huawei API <= 29 due to HardwareBuffer defect causing video playback failures",
        producer instanceof SurfaceTextureSurfaceProducer);
  }

  @Test
  public void ImageReaderSurfaceProducerDoesNotCropOrRotate() {
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
    TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();

    assertFalse(producer.handlesCropAndRotation());
  }

  @Test
  @SuppressWarnings({"deprecation", "removal"})
  public void ImageReaderSurfaceProducerIsCleanedUpOnTrimMemory() {
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
    TextureRegistry.SurfaceProducer producer =
        flutterRenderer.createSurfaceProducer(TextureRegistry.SurfaceLifecycle.resetInBackground);

    // Create and set a mock callback.
    TextureRegistry.SurfaceProducer.Callback callback =
        mock(TextureRegistry.SurfaceProducer.Callback.class);
    producer.setCallback(callback);

    // Trim memory.
    flutterRenderer.onTrimMemory(TRIM_MEMORY_BACKGROUND);

    // Verify.
    verify(callback).onSurfaceCleanup();
  }

  private static class TestSurfaceState {
    Surface beingDestroyed;
  }

  @Test
  public void ImageReaderSurfaceProducerSignalsCleanupBeforeDestroying() throws Exception {
    // Regression test for https://github.com/flutter/flutter/issues/160933.
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
    TextureRegistry.SurfaceProducer producer =
        flutterRenderer.createSurfaceProducer(TextureRegistry.SurfaceLifecycle.resetInBackground);

    // Ensure the callbacks were actually called.
    // Note this needs to be an object in order to be accessed in the callback.
    final TestSurfaceState state = new TestSurfaceState();
    state.beingDestroyed = producer.getSurface();

    // Create and set a callback that ensures the surface is not yet released.
    CountDownLatch latch = new CountDownLatch(1);
    producer.setCallback(
        new TextureRegistry.SurfaceProducer.Callback() {
          @Override
          public void onSurfaceCleanup() {
            state.beingDestroyed = producer.getSurface();
            assertTrue("Not released yet", state.beingDestroyed.isValid());

            state.beingDestroyed.release();
            latch.countDown();
          }
        });

    // Trim.
    flutterRenderer.onTrimMemory(TRIM_MEMORY_BACKGROUND);
    latch.await();

    // Destroy.
    assertFalse("Should be destroyed", state.beingDestroyed.isValid());
  }

  @Test
  @SuppressWarnings({"deprecation", "removal"})
  public void ImageReaderSurfaceProducerSignalsCleanupCallsDestroy() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    TextureRegistry.SurfaceProducer.Callback callback =
        new TextureRegistry.SurfaceProducer.Callback() {
          @Override
          public void onSurfaceDestroyed() {
            latch.countDown();
          }
        };

    // Tests that cleanup, if not provided, just calls destroyed.
    callback.onSurfaceCleanup();
    latch.await();
  }

  @Test
  @SuppressWarnings({"deprecation", "removal"})
  public void ImageReaderSurfaceProducerUnsubscribesWhenReleased() {
    // Regression test for https://github.com/flutter/flutter/issues/156434.
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
    TextureRegistry.SurfaceProducer producer =
        flutterRenderer.createSurfaceProducer(TextureRegistry.SurfaceLifecycle.resetInBackground);

    // Create and set a mock callback.
    TextureRegistry.SurfaceProducer.Callback callback =
        mock(TextureRegistry.SurfaceProducer.Callback.class);
    producer.setCallback(callback);

    // Release the surface.
    producer.release();

    // Call trim memory.
    flutterRenderer.onTrimMemory(TRIM_MEMORY_BACKGROUND);

    // Verify was not called.
    verify(callback, never()).onSurfaceCleanup();
    verify(callback, never()).onSurfaceDestroyed();
  }

  @Test
  public void ImageReaderSurfaceProducerSchedulesFrameIfQueueNotEmpty() throws Exception {
    FlutterRenderer flutterRenderer = spy(engineRule.getFlutterEngine().getRenderer());
    TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();
    FlutterRenderer.ImageReaderSurfaceProducer texture =
        (FlutterRenderer.ImageReaderSurfaceProducer) producer;
    texture.disableFenceForTest();
    texture.setSize(1, 1);

    // Render two frames.
    for (int i = 0; i < 2; i++) {
      Surface surface = texture.getSurface();
      assertNotNull(surface);
      Canvas canvas = surface.lockHardwareCanvas();
      canvas.drawARGB(255, 255, 0, 0);
      surface.unlockCanvasAndPost(canvas);
      idleImageReaderAndMainLoopers(texture);
    }

    // Each enqueue of an image should result in a call to scheduleEngineFrame.
    verify(flutterRenderer, times(2)).scheduleEngineFrame();

    // Consume the first image.
    Image image = texture.acquireLatestImage();
    idleImageReaderAndMainLoopers(texture);

    // The dequeue should call scheduleEngineFrame because another image
    // remains in the queue.
    verify(flutterRenderer, times(3)).scheduleEngineFrame();

    // Consume the second image.
    image = texture.acquireLatestImage();
    idleImageReaderAndMainLoopers(texture);

    // The dequeue should not call scheduleEngineFrame because the queue
    // is now empty.
    verify(flutterRenderer, times(3)).scheduleEngineFrame();
  }

  @Test
  public void itDoesNotScheduleFramesOnADetachedFlutterJNI() {
    // Setup the test.
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();

    // While attached the frame request is forwarded.
    flutterRenderer.scheduleEngineFrame();
    verify(fakeFlutterJNI, times(1)).scheduleFrame();

    // Execute the behavior under test.
    engineRule.setJniIsAttached(false);
    flutterRenderer.scheduleEngineFrame();

    // Verify the behavior under test: still only the call made while attached.
    verify(fakeFlutterJNI, times(1)).scheduleFrame();
  }

  @Test
  public void ImageReaderSurfaceProducerDoesNotScheduleFrameWhenDetached() throws Exception {
    // Regression test for https://github.com/flutter/flutter/issues/188300.
    FlutterRenderer flutterRenderer = spy(engineRule.getFlutterEngine().getRenderer());
    TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();
    FlutterRenderer.ImageReaderSurfaceProducer texture =
        (FlutterRenderer.ImageReaderSurfaceProducer) producer;
    texture.disableFenceForTest();
    texture.setSize(1, 1);

    // The engine detaches, e.g. because the Activity was destroyed, while this producer still has
    // a frame in flight.
    engineRule.setJniIsAttached(false);

    // Render a frame. The ImageReader callback is delivered on the background reader thread and
    // reaches scheduleEngineFrame on the main looper after the detach.
    Surface surface = texture.getSurface();
    assertNotNull(surface);
    Canvas canvas = surface.lockHardwareCanvas();
    canvas.drawARGB(255, 255, 0, 0);
    surface.unlockCanvasAndPost(canvas);
    idleImageReaderAndMainLoopers(texture);

    // The image still reaches scheduleEngineFrame, ...
    verify(flutterRenderer, times(1)).scheduleEngineFrame();
    // ... but it must not be forwarded to the detached FlutterJNI, which would throw.
    verify(fakeFlutterJNI, never()).scheduleFrame();
  }

  @Test
  public void getSurface_doesNotReturnInvalidSurface() {
    FlutterRenderer flutterRenderer = spy(engineRule.getFlutterEngine().getRenderer());
    TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();
    FlutterRenderer.ImageReaderSurfaceProducer spyImageReaderSurfaceProducer =
        spy((FlutterRenderer.ImageReaderSurfaceProducer) producer);
    ImageReader mockImageReader = mock(ImageReader.class);
    ImageReader mockSecondImageReader = mock(ImageReader.class);
    Surface firstMockSurface = mock(Surface.class);
    Surface secondMockSurface = mock(Surface.class);

    when(mockImageReader.getSurface()).thenReturn(firstMockSurface);
    when(mockSecondImageReader.getSurface()).thenReturn(secondMockSurface);
    when(firstMockSurface.isValid()).thenReturn(false);
    when(spyImageReaderSurfaceProducer.createImageReader())
        .thenReturn(mockImageReader)
        .thenReturn(mockSecondImageReader);

    Surface firstSurface = spyImageReaderSurfaceProducer.getSurface();
    Surface secondSurface = spyImageReaderSurfaceProducer.getSurface();

    assertNotEquals(firstSurface, secondSurface);
    assertEquals(firstSurface, firstMockSurface);
    assertEquals(secondSurface, secondMockSurface);
  }

  @Test
  public void getSurface_consecutiveCallsReturnSameSurfaceIfStillValid() {
    FlutterRenderer flutterRenderer = spy(engineRule.getFlutterEngine().getRenderer());
    TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();
    FlutterRenderer.ImageReaderSurfaceProducer spyImageReaderSurfaceProducer =
        spy((FlutterRenderer.ImageReaderSurfaceProducer) producer);
    ImageReader mockImageReader = mock(ImageReader.class);
    Surface mockSurface = mock(Surface.class);

    when(mockSurface.isValid()).thenReturn(true);
    when(mockImageReader.getSurface()).thenReturn(mockSurface);
    when(spyImageReaderSurfaceProducer.createImageReader()).thenReturn(mockImageReader);

    Surface firstSurface = spyImageReaderSurfaceProducer.getSurface();
    Surface secondSurface = spyImageReaderSurfaceProducer.getSurface();

    assertEquals(firstSurface, secondSurface);
    assertEquals(firstSurface, mockSurface);
  }

  @Test
  public void getForcedNewSurface_returnsNewSurface() {
    FlutterRenderer flutterRenderer = spy(engineRule.getFlutterEngine().getRenderer());
    TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();

    Surface firstSurface = producer.getSurface();
    Surface secondSurface = producer.getForcedNewSurface();

    assertNotEquals(firstSurface, secondSurface);
  }

  @Test
  public void getSurface_doesNotReturnNewSurface() {
    FlutterRenderer flutterRenderer = spy(engineRule.getFlutterEngine().getRenderer());
    TextureRegistry.SurfaceProducer producer = flutterRenderer.createSurfaceProducer();

    Surface firstSurface = producer.getSurface();
    Surface secondSurface = producer.getSurface();

    assertEquals(firstSurface, secondSurface);
  }

  @Test
  public void restoreSurfaceProducers_restoresImageReaderSurfaceProducersAsIfApplicationResumed() {
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
    FlutterRenderer.ImageReaderSurfaceProducer imageReaderProducer1 =
        (FlutterRenderer.ImageReaderSurfaceProducer) flutterRenderer.createSurfaceProducer();
    FlutterRenderer.ImageReaderSurfaceProducer imageReaderProducer2 =
        (FlutterRenderer.ImageReaderSurfaceProducer) flutterRenderer.createSurfaceProducer();
    imageReaderProducer1.callback = mock(TextureRegistry.SurfaceProducer.Callback.class);
    imageReaderProducer2.callback = mock(TextureRegistry.SurfaceProducer.Callback.class);
    imageReaderProducer1.notifiedDestroy = true;
    imageReaderProducer2.notifiedDestroy = true;

    flutterRenderer.restoreSurfaceProducers();

    verify(imageReaderProducer1.callback).onSurfaceAvailable();
    verify(imageReaderProducer2.callback).onSurfaceAvailable();
    assertFalse(imageReaderProducer1.notifiedDestroy);
    assertFalse(imageReaderProducer2.notifiedDestroy);
  }

  @Test
  public void waitOnFence_closesFence() throws Exception {
    FlutterRenderer.ImageReaderSurfaceProducer producer =
        (FlutterRenderer.ImageReaderSurfaceProducer)
            engineRule.getFlutterEngine().getRenderer().createSurfaceProducer();
    Image image = mock(Image.class);
    SyncFence fence = mock(SyncFence.class);
    when(image.getFence()).thenReturn(fence);
    producer.waitOnFence(image);
    verify(fence, times(1)).close();
  }

  @Test
  public void scheduleFrame_latestFrameOnlyCapsQueueAtOneWhenUnconsumed() {
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
    FlutterRenderer.ImageReaderSurfaceProducer texture =
        (FlutterRenderer.ImageReaderSurfaceProducer) flutterRenderer.createSurfaceProducer();
    texture.disableFenceForTest();
    texture.setSize(1, 1);

    Surface surface = texture.getSurface();
    assertNotNull(surface);
    assertEquals(surface, texture.getSurface());

    for (int i = 0; i < 4; i++) {
      Canvas canvas = surface.lockHardwareCanvas();
      canvas.drawARGB(255, i * 40, 0, 0);
      surface.unlockCanvasAndPost(canvas);
      texture.scheduleFrame();
      idleImageReaderAndMainLoopers(texture);
    }

    assertEquals(1, texture.numImages());
    assertNotNull(texture.acquireLatestImage());
    assertEquals(0, texture.numImages());
    texture.release();
  }

  @Test
  public void acquireLatestHardwareBuffer_cachesHardwareBufferAndClosesOnEvict() {
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
    FlutterRenderer.ImageReaderSurfaceProducer texture =
        (FlutterRenderer.ImageReaderSurfaceProducer) flutterRenderer.createSurfaceProducer();
    texture.disableFenceForTest();
    ImageReader mockReader = mock(ImageReader.class);
    Surface mockSurface = mock(Surface.class);
    when(mockReader.getSurface()).thenReturn(mockSurface);

    Image firstImage = mock(Image.class);
    android.hardware.HardwareBuffer firstHb = mock(android.hardware.HardwareBuffer.class);
    when(firstImage.getHardwareBuffer()).thenReturn(firstHb);

    texture.onImage(mockReader, firstImage);
    assertEquals(firstHb, texture.acquireLatestHardwareBuffer());
    verify(firstImage, times(1)).getHardwareBuffer();

    for (int i = 0; i < 3; i++) {
      Image nextImage = mock(Image.class);
      android.hardware.HardwareBuffer nextHb = mock(android.hardware.HardwareBuffer.class);
      when(nextImage.getHardwareBuffer()).thenReturn(nextHb);
      texture.onImage(mockReader, nextImage);
      assertEquals(nextHb, texture.acquireLatestHardwareBuffer());
    }

    org.robolectric.Shadows.shadowOf(texture.getImageReaderLooperForTest())
        .idleFor(java.time.Duration.ofMillis(32));
    verify(firstHb, times(1)).close();
    verify(firstImage, times(1)).close();
    texture.release();
  }

  @Test
  public void backgroundImageReaderThread_usesDedicatedLooperAndCoalescesMainSchedule() {
    FlutterRenderer.debugUseBackgroundImageReaderThread = true;
    try {
      FlutterRenderer flutterRenderer = spy(engineRule.getFlutterEngine().getRenderer());
      FlutterRenderer.ImageReaderSurfaceProducer rawTexture =
          (FlutterRenderer.ImageReaderSurfaceProducer) flutterRenderer.createSurfaceProducer();
      FlutterRenderer.ImageReaderSurfaceProducer texture = spy(rawTexture);
      texture.disableFenceForTest();

      assertNotEquals(android.os.Looper.getMainLooper(), texture.getImageReaderLooperForTest());

      ImageReader mockReader = mock(ImageReader.class);
      Surface mockSurface = mock(Surface.class);
      when(mockSurface.isValid()).thenReturn(true);
      when(mockReader.getSurface()).thenReturn(mockSurface);
      when(texture.createImageReader()).thenReturn(mockReader);

      final org.mockito.ArgumentCaptor<ImageReader.OnImageAvailableListener> listenerCaptor =
          org.mockito.ArgumentCaptor.forClass(ImageReader.OnImageAvailableListener.class);
      final org.mockito.ArgumentCaptor<android.os.Handler> handlerCaptor =
          org.mockito.ArgumentCaptor.forClass(android.os.Handler.class);

      Image img1 = mock(Image.class);
      Image img2 = mock(Image.class);
      android.hardware.HardwareBuffer hb1 = mock(android.hardware.HardwareBuffer.class);
      android.hardware.HardwareBuffer hb2 = mock(android.hardware.HardwareBuffer.class);
      when(img1.getHardwareBuffer()).thenReturn(hb1);
      when(img2.getHardwareBuffer()).thenReturn(hb2);
      when(mockReader.acquireLatestImage()).thenReturn(img1, img2);

      // Mark consumer as HardwareBuffer-backed so background listener preloads HardwareBuffers.
      assertNull(texture.acquireLatestHardwareBuffer());
      assertEquals(mockSurface, texture.getSurface());
      verify(mockReader)
          .setOnImageAvailableListener(listenerCaptor.capture(), handlerCaptor.capture());
      assertEquals(texture.getImageReaderLooperForTest(), handlerCaptor.getValue().getLooper());

      // Simulate two back-to-back callbacks on a non-main thread before MainLooper drains.
      Thread bgSim =
          new Thread(
              () -> {
                listenerCaptor.getValue().onImageAvailable(mockReader);
                listenerCaptor.getValue().onImageAvailable(mockReader);
              });
      bgSim.start();
      try {
        bgSim.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      // Main looper has not run yet, so scheduleEngineFrame has not run on the wrong thread;
      // meanwhile HardwareBuffers were already preloaded off the main thread.
      verify(flutterRenderer, never()).scheduleEngineFrame();
      verify(img1, times(1)).getHardwareBuffer();
      verify(img2, times(1)).getHardwareBuffer();

      shadowMainLooper().idle();
      // Coalesced into a single main-looper scheduleEngineFrame wakeup!
      verify(flutterRenderer, times(1)).scheduleEngineFrame();

      assertEquals(hb1, texture.acquireLatestHardwareBuffer());
      assertEquals(hb2, texture.acquireLatestHardwareBuffer());
      texture.release();
    } finally {
      FlutterRenderer.debugUseBackgroundImageReaderThread = true;
    }
  }

  @Test
  public void backgroundImageReaderThread_drainsEvictedImagesOnNextAcquire() {
    FlutterRenderer.debugUseBackgroundImageReaderThread = true;
    try {
      FlutterRenderer flutterRenderer = spy(engineRule.getFlutterEngine().getRenderer());
      FlutterRenderer.ImageReaderSurfaceProducer rawTexture =
          (FlutterRenderer.ImageReaderSurfaceProducer) flutterRenderer.createSurfaceProducer();
      FlutterRenderer.ImageReaderSurfaceProducer texture = spy(rawTexture);
      texture.disableFenceForTest();

      ImageReader mockReader = mock(ImageReader.class);
      Surface mockSurface = mock(Surface.class);
      when(mockSurface.isValid()).thenReturn(true);
      when(mockReader.getSurface()).thenReturn(mockSurface);
      when(texture.createImageReader()).thenReturn(mockReader);

      final org.mockito.ArgumentCaptor<ImageReader.OnImageAvailableListener> listenerCaptor =
          org.mockito.ArgumentCaptor.forClass(ImageReader.OnImageAvailableListener.class);
      assertNull(texture.acquireLatestHardwareBuffer());
      assertEquals(mockSurface, texture.getSurface());
      verify(mockReader).setOnImageAvailableListener(listenerCaptor.capture(), any());

      Image[] imgs = new Image[5];
      android.hardware.HardwareBuffer[] hbs = new android.hardware.HardwareBuffer[5];
      for (int i = 0; i < 5; i++) {
        imgs[i] = mock(Image.class);
        hbs[i] = mock(android.hardware.HardwareBuffer.class);
        when(imgs[i].getHardwareBuffer()).thenReturn(hbs[i]);
      }
      when(mockReader.acquireLatestImage()).thenReturn(imgs[0], imgs[1], imgs[2], imgs[3], imgs[4]);

      // Dequeue 4 images so lastDequeuedImage exceeds MAX_DEQUEUED_IMAGES (2 + current = 3) on the
      // 4th dequeue, queueing imgs[0] into asyncImagesToClose.
      for (int i = 0; i < 4; i++) {
        listenerCaptor.getValue().onImageAvailable(mockReader);
        assertEquals(hbs[i], texture.acquireLatestHardwareBuffer());
      }
      // imgs[0] is deferred for background close (not closed synchronously on Raster thread).
      verify(imgs[0], never()).close();

      // Next onImageAvailable drains asyncImagesToClose immediately before acquireLatestImage()!
      listenerCaptor.getValue().onImageAvailable(mockReader);
      verify(hbs[0], times(1)).close();
      verify(imgs[0], times(1)).close();
      assertEquals(hbs[4], texture.acquireLatestHardwareBuffer());

      texture.release();
    } finally {
      FlutterRenderer.debugUseBackgroundImageReaderThread = true;
    }
  }

  @Test
  public void dequeueImageFailsBackToDirectAcquireOnVSyncRace() {
    FlutterRenderer.debugUseBackgroundImageReaderThread = true;
    try {
      FlutterRenderer flutterRenderer = spy(engineRule.getFlutterEngine().getRenderer());
      FlutterRenderer.ImageReaderSurfaceProducer rawTexture =
          (FlutterRenderer.ImageReaderSurfaceProducer) flutterRenderer.createSurfaceProducer();
      FlutterRenderer.ImageReaderSurfaceProducer texture = spy(rawTexture);
      texture.disableFenceForTest();

      ImageReader mockReader = mock(ImageReader.class);
      Surface mockSurface = mock(Surface.class);
      when(mockSurface.isValid()).thenReturn(true);
      when(mockReader.getSurface()).thenReturn(mockSurface);
      when(texture.createImageReader()).thenReturn(mockReader);

      final org.mockito.ArgumentCaptor<ImageReader.OnImageAvailableListener> listenerCaptor =
          org.mockito.ArgumentCaptor.forClass(ImageReader.OnImageAvailableListener.class);
      assertEquals(mockSurface, texture.getSurface());
      verify(mockReader).setOnImageAvailableListener(listenerCaptor.capture(), any());

      Image img1 = mock(Image.class);
      Image img2 = mock(Image.class);
      android.hardware.HardwareBuffer hb1 = mock(android.hardware.HardwareBuffer.class);
      android.hardware.HardwareBuffer hb2 = mock(android.hardware.HardwareBuffer.class);
      when(img1.getHardwareBuffer()).thenReturn(hb1);
      when(img2.getHardwareBuffer()).thenReturn(hb2);
      when(mockReader.acquireLatestImage()).thenReturn(img1, img2);

      // First frame sets hasBeenAcquiredWithImage = true.
      listenerCaptor.getValue().onImageAvailable(mockReader);
      assertEquals(hb1, texture.acquireLatestHardwareBuffer());

      // Second frame: Raster thread reaches acquireLatestHardwareBuffer() before the background
      // listener runs (imageQueue is empty and inFlightAcquire == 0). It should directly acquire
      // img2 from the active ImageReader instead of returning null and dropping a frame.
      assertEquals(hb2, texture.acquireLatestHardwareBuffer());
      texture.release();
    } finally {
      FlutterRenderer.debugUseBackgroundImageReaderThread = true;
    }
  }

  @Test
  public void acquireLatestHardwareBufferWaitsForInFlightAcquireFromBackgroundThread()
      throws Exception {
    FlutterRenderer.debugUseBackgroundImageReaderThread = true;
    try {
      FlutterRenderer flutterRenderer = spy(engineRule.getFlutterEngine().getRenderer());
      FlutterRenderer.ImageReaderSurfaceProducer rawTexture =
          (FlutterRenderer.ImageReaderSurfaceProducer) flutterRenderer.createSurfaceProducer();
      FlutterRenderer.ImageReaderSurfaceProducer texture = spy(rawTexture);
      texture.disableFenceForTest();

      ImageReader mockReader = mock(ImageReader.class);
      Surface mockSurface = mock(Surface.class);
      when(mockSurface.isValid()).thenReturn(true);
      when(mockReader.getSurface()).thenReturn(mockSurface);
      when(texture.createImageReader()).thenReturn(mockReader);

      final org.mockito.ArgumentCaptor<ImageReader.OnImageAvailableListener> listenerCaptor =
          org.mockito.ArgumentCaptor.forClass(ImageReader.OnImageAvailableListener.class);
      assertNull(texture.acquireLatestHardwareBuffer());
      assertEquals(mockSurface, texture.getSurface());
      verify(mockReader).setOnImageAvailableListener(listenerCaptor.capture(), any());

      Image img1 = mock(Image.class);
      android.hardware.HardwareBuffer hb1 = mock(android.hardware.HardwareBuffer.class);
      when(img1.getHardwareBuffer()).thenReturn(hb1);

      CountDownLatch enteredAcquire = new CountDownLatch(1);
      when(mockReader.acquireLatestImage())
          .thenAnswer(
              invocation -> {
                enteredAcquire.countDown();
                Thread.sleep(1L);
                return img1;
              });

      Thread bgReader = new Thread(() -> listenerCaptor.getValue().onImageAvailable(mockReader));
      bgReader.start();
      assertTrue(enteredAcquire.await(1, java.util.concurrent.TimeUnit.SECONDS));

      // Calling acquireLatestHardwareBuffer() while background acquireLatestImage() is in flight
      // waits up to 2ms and receives hb1 in the same frame instead of returning null.
      assertEquals(hb1, texture.acquireLatestHardwareBuffer());
      bgReader.join();
      texture.release();
    } finally {
      FlutterRenderer.debugUseBackgroundImageReaderThread = true;
    }
  }

  @Test
  public void release_closesOutstandingAcquiredHardwareBuffer() {
    FlutterRenderer flutterRenderer = engineRule.getFlutterEngine().getRenderer();
    FlutterRenderer.ImageReaderSurfaceProducer texture =
        (FlutterRenderer.ImageReaderSurfaceProducer) flutterRenderer.createSurfaceProducer();
    texture.disableFenceForTest();
    ImageReader mockReader = mock(ImageReader.class);
    Surface mockSurface = mock(Surface.class);
    when(mockReader.getSurface()).thenReturn(mockSurface);

    Image firstImage = mock(Image.class);
    android.hardware.HardwareBuffer firstHb = mock(android.hardware.HardwareBuffer.class);
    when(firstImage.getHardwareBuffer()).thenReturn(firstHb);

    Image secondImage = mock(Image.class);
    android.hardware.HardwareBuffer secondHb = mock(android.hardware.HardwareBuffer.class);
    when(secondImage.getHardwareBuffer()).thenReturn(secondHb);

    texture.onImage(mockReader, firstImage);
    assertEquals(firstHb, texture.acquireLatestHardwareBuffer());
    verify(firstHb, never()).close();

    // Acquiring a second frame clears inUseByRaster on firstImage and marks secondImage as
    // inUseByRaster = true.
    texture.onImage(mockReader, secondImage);
    assertEquals(secondHb, texture.acquireLatestHardwareBuffer());
    verify(secondHb, never()).close();

    // Releasing the producer on the main thread closes both Images immediately (freeing the
    // ImageReader slots) and closes firstHb immediately (no longer inUseByRaster), but defers
    // closing secondHb (still inUseByRaster) into retiredHardwareBuffers so a concurrent raster
    // import never races with HardwareBuffer.close() between fromHardwareBuffer and
    // AHardwareBuffer_acquire.
    texture.release();
    verify(firstImage, times(1)).close();
    verify(secondImage, times(1)).close();
    verify(firstHb, times(1)).close();
    verify(secondHb, never()).close();

    // The next raster-side dequeue/acquire drains retiredHardwareBuffers and closes secondHb.
    assertNull(texture.acquireLatestHardwareBuffer());
    verify(secondHb, times(1)).close();
  }

  @Test
  public void onImageAvailable_closesReaderWhenCleanupOccursDuringInFlightAcquire()
      throws Exception {
    FlutterRenderer flutterRenderer = spy(engineRule.getFlutterEngine().getRenderer());
    FlutterRenderer.ImageReaderSurfaceProducer rawTexture =
        (FlutterRenderer.ImageReaderSurfaceProducer) flutterRenderer.createSurfaceProducer();
    FlutterRenderer.ImageReaderSurfaceProducer texture = spy(rawTexture);
    texture.disableFenceForTest();

    ImageReader mockReader = mock(ImageReader.class);
    Surface mockSurface = mock(Surface.class);
    when(mockSurface.isValid()).thenReturn(true);
    when(mockReader.getSurface()).thenReturn(mockSurface);
    when(texture.createImageReader()).thenReturn(mockReader);

    final org.mockito.ArgumentCaptor<ImageReader.OnImageAvailableListener> listenerCaptor =
        org.mockito.ArgumentCaptor.forClass(ImageReader.OnImageAvailableListener.class);
    assertNull(texture.acquireLatestHardwareBuffer());
    assertEquals(mockSurface, texture.getSurface());
    verify(mockReader).setOnImageAvailableListener(listenerCaptor.capture(), any());

    Image lateImage = mock(Image.class);
    android.hardware.HardwareBuffer lateHb = mock(android.hardware.HardwareBuffer.class);
    when(lateImage.getHardwareBuffer()).thenReturn(lateHb);

    CountDownLatch enteredAcquire = new CountDownLatch(1);
    CountDownLatch allowAcquireToFinish = new CountDownLatch(1);
    when(mockReader.acquireLatestImage())
        .thenAnswer(
            invocation -> {
              enteredAcquire.countDown();
              assertTrue(allowAcquireToFinish.await(2, java.util.concurrent.TimeUnit.SECONDS));
              return lateImage;
            });

    Thread bgReader = new Thread(() -> listenerCaptor.getValue().onImageAvailable(mockReader));
    bgReader.start();
    assertTrue(enteredAcquire.await(2, java.util.concurrent.TimeUnit.SECONDS));

    // Trigger cleanup (onTrimMemory) while acquireLatestImage() is in flight on bgReader.
    // The reader must NOT be closed while inFlightAcquire > 0; instead closeWhenIdle is set.
    texture.onTrimMemory(TRIM_MEMORY_BACKGROUND);
    verify(mockReader, never()).close();

    // Unblock acquireLatestImage(); onImageAvailable must close lateHb, lateImage, and mockReader
    // via deferredClose.close() from inside the listener.
    allowAcquireToFinish.countDown();
    bgReader.join();

    verify(lateHb, times(1)).close();
    verify(lateImage, times(1)).close();
    verify(mockReader, times(1)).close();
    texture.release();
  }

  @Test
  public void dequeueImage_unblocksImmediatelyWhenReleasedDuringInFlightAcquireWait()
      throws Exception {
    FlutterRenderer flutterRenderer = spy(engineRule.getFlutterEngine().getRenderer());
    FlutterRenderer.ImageReaderSurfaceProducer rawTexture =
        (FlutterRenderer.ImageReaderSurfaceProducer) flutterRenderer.createSurfaceProducer();
    FlutterRenderer.ImageReaderSurfaceProducer texture = spy(rawTexture);
    texture.disableFenceForTest();

    ImageReader mockReader = mock(ImageReader.class);
    Surface mockSurface = mock(Surface.class);
    when(mockSurface.isValid()).thenReturn(true);
    when(mockReader.getSurface()).thenReturn(mockSurface);
    when(texture.createImageReader()).thenReturn(mockReader);

    final org.mockito.ArgumentCaptor<ImageReader.OnImageAvailableListener> listenerCaptor =
        org.mockito.ArgumentCaptor.forClass(ImageReader.OnImageAvailableListener.class);
    assertNull(texture.acquireLatestHardwareBuffer());
    assertEquals(mockSurface, texture.getSurface());
    verify(mockReader).setOnImageAvailableListener(listenerCaptor.capture(), any());

    CountDownLatch enteredAcquire = new CountDownLatch(1);
    CountDownLatch allowAcquireToFinish = new CountDownLatch(1);
    when(mockReader.acquireLatestImage())
        .thenAnswer(
            invocation -> {
              enteredAcquire.countDown();
              assertTrue(allowAcquireToFinish.await(2, java.util.concurrent.TimeUnit.SECONDS));
              return null;
            });

    Thread bgReader = new Thread(() -> listenerCaptor.getValue().onImageAvailable(mockReader));
    bgReader.start();
    assertTrue(enteredAcquire.await(2, java.util.concurrent.TimeUnit.SECONDS));

    CountDownLatch rasterStarted = new CountDownLatch(1);
    CountDownLatch rasterFinished = new CountDownLatch(1);
    final android.hardware.HardwareBuffer[] rasterResult = new android.hardware.HardwareBuffer[1];
    Thread rasterSim =
        new Thread(
            () -> {
              rasterStarted.countDown();
              rasterResult[0] = texture.acquireLatestHardwareBuffer();
              rasterFinished.countDown();
            });
    rasterSim.start();
    assertTrue(rasterStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));

    // Release from the main thread while the raster thread is waiting and bgReader is in-flight.
    texture.release();
    assertTrue(rasterFinished.await(2, java.util.concurrent.TimeUnit.SECONDS));
    assertNull(rasterResult[0]);

    allowAcquireToFinish.countDown();
    bgReader.join();
    verify(mockReader, times(1)).close();
  }

  @Test
  public void maxImagesExhaustion_drainsDeferredAsyncCloseQueueBeforeFallbackAcquire() {
    FlutterRenderer flutterRenderer = spy(engineRule.getFlutterEngine().getRenderer());
    FlutterRenderer.ImageReaderSurfaceProducer rawTexture =
        (FlutterRenderer.ImageReaderSurfaceProducer) flutterRenderer.createSurfaceProducer();
    FlutterRenderer.ImageReaderSurfaceProducer texture = spy(rawTexture);
    texture.disableFenceForTest();

    ImageReader mockReader = mock(ImageReader.class);
    Surface mockSurface = mock(Surface.class);
    when(mockSurface.isValid()).thenReturn(true);
    when(mockReader.getSurface()).thenReturn(mockSurface);
    when(texture.createImageReader()).thenReturn(mockReader);

    final org.mockito.ArgumentCaptor<ImageReader.OnImageAvailableListener> listenerCaptor =
        org.mockito.ArgumentCaptor.forClass(ImageReader.OnImageAvailableListener.class);
    assertNull(texture.acquireLatestHardwareBuffer());
    assertEquals(mockSurface, texture.getSurface());
    verify(mockReader).setOnImageAvailableListener(listenerCaptor.capture(), any());

    Image[] imgs = new Image[5];
    android.hardware.HardwareBuffer[] hbs = new android.hardware.HardwareBuffer[5];
    for (int i = 0; i < 5; i++) {
      imgs[i] = mock(Image.class);
      hbs[i] = mock(android.hardware.HardwareBuffer.class);
      when(imgs[i].getHardwareBuffer()).thenReturn(hbs[i]);
    }
    when(mockReader.acquireLatestImage()).thenReturn(imgs[0], imgs[1], imgs[2], imgs[3], imgs[4]);

    // Dequeue 4 frames via listener so imgs[0] is moved into asyncImagesToClose (pending the 32ms
    // idle timer, which has NOT fired yet).
    for (int i = 0; i < 4; i++) {
      listenerCaptor.getValue().onImageAvailable(mockReader);
      assertEquals(hbs[i], texture.acquireLatestHardwareBuffer());
    }
    verify(imgs[0], never()).close();

    // Now the raster thread hits the direct fallback acquire path before the 32ms idle timer or
    // the background listener runs. It must drain asyncImagesToClose BEFORE calling
    // mockReader.acquireLatestImage() so MAX_IMAGES is not exhausted!
    assertEquals(hbs[4], texture.acquireLatestHardwareBuffer());
    verify(hbs[0], times(1)).close();
    verify(imgs[0], times(1)).close();

    texture.release();
  }
}
