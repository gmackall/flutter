// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.view.AttachedSurfaceControl;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.flutter.embedding.android.FlutterActivityLaunchConfigs;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.mutatorsstack.FlutterMutatorView;
import io.flutter.embedding.engine.mutatorsstack.FlutterMutatorsStack;
import io.flutter.embedding.engine.renderer.FlutterRenderer;
import io.flutter.embedding.engine.systemchannels.PlatformViewCreationRequest;
import io.flutter.embedding.engine.systemchannels.PlatformViewsChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.StandardMessageCodec;
import io.flutter.plugin.common.StandardMethodCodec;
import io.flutter.view.TextureRegistry;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 34)
public class PlatformViewsHotswapTest {

  private static final class OutboundCall {
    final String channel;
    final MethodCall methodCall;

    OutboundCall(String channel, MethodCall methodCall) {
      this.channel = channel;
      this.methodCall = methodCall;
    }
  }

  private static final class FakeAttachedSurfaceControl implements AttachedSurfaceControl {
    @Nullable
    @Override
    public SurfaceControl.Transaction buildReparentTransaction(@NonNull SurfaceControl child) {
      return new SurfaceControl.Transaction();
    }

    @Override
    public boolean applyTransactionOnDraw(@NonNull SurfaceControl.Transaction t) {
      t.close();
      return true;
    }
  }

  private static final class FakeSurfaceHolder implements SurfaceHolder {
    final List<Callback> callbacks = new ArrayList<>();

    @Override
    public void addCallback(Callback callback) {
      callbacks.add(callback);
    }

    @Override
    public void removeCallback(Callback callback) {
      callbacks.remove(callback);
    }

    @Override
    public boolean isCreating() {
      return false;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void setType(int type) {}

    @Override
    public void setFixedSize(int width, int height) {}

    @Override
    public void setSizeFromLayout() {}

    @Override
    public void setFormat(int format) {}

    @Override
    public void setKeepScreenOn(boolean screenOn) {}

    @Nullable
    @Override
    public Canvas lockCanvas() {
      return null;
    }

    @Nullable
    @Override
    public Canvas lockCanvas(Rect dirty) {
      return null;
    }

    @Override
    public void unlockCanvasAndPost(Canvas canvas) {}

    @Override
    public Rect getSurfaceFrame() {
      return new Rect();
    }

    @Nullable
    @Override
    public Surface getSurface() {
      return null;
    }
  }

  private static final class TestFlutterView extends FlutterView {
    final AttachedSurfaceControl rootSurfaceControl = new FakeAttachedSurfaceControl();
    boolean imageViewFrameAcquired = true;

    TestFlutterView(Context context) {
      super(context);
    }

    @Nullable
    @Override
    public AttachedSurfaceControl getRootSurfaceControl() {
      return rootSurfaceControl;
    }

    @Override
    public void convertToImageView() {}

    @Override
    public boolean acquireLatestImageViewFrame() {
      return imageViewFrameAcquired;
    }
  }

  private static final class FakeSurfaceProducer implements TextureRegistry.SurfaceProducer {
    private final long id;
    private int width = 1;
    private int height = 1;
    private int maxImages = 7;
    int releaseCount = 0;

    FakeSurfaceProducer(long id) {
      this.id = id;
    }

    @Override
    public long id() {
      return id;
    }

    @Override
    public void release() {
      releaseCount++;
    }

    @Override
    public void setSize(int width, int height) {
      this.width = width;
      this.height = height;
    }

    @Override
    public void setMaxImages(int maxImages) {
      this.maxImages = maxImages;
    }

    int getMaxImages() {
      return maxImages;
    }

    @Override
    public int getWidth() {
      return width;
    }

    @Override
    public int getHeight() {
      return height;
    }

    @Nullable
    @Override
    public Surface getSurface() {
      return null;
    }

    @Nullable
    @Override
    public Surface getForcedNewSurface() {
      return null;
    }

    @Override
    public boolean handlesCropAndRotation() {
      return false;
    }

    @Override
    public void scheduleFrame() {}

    @Override
    public void setCallback(Callback callback) {}
  }

  private static final class SimplePlatformView implements PlatformView {
    private final View view;
    private final boolean nativeHint;
    int disposeCount = 0;

    SimplePlatformView(View view, boolean nativeHint) {
      this.view = view;
      this.nativeHint = nativeHint;
    }

    @Nullable
    @Override
    public View getView() {
      return view;
    }

    @Override
    public boolean requiresNativeComposition() {
      return nativeHint;
    }

    @Override
    public void dispose() {
      disposeCount++;
    }
  }

  private static final class Harness {
    final Context context;
    final FlutterJNI jni;
    final List<OutboundCall> outboundCalls = new ArrayList<>();
    final DartExecutor executor;
    final FlutterRenderer renderer;
    final List<FakeSurfaceProducer> producers = new ArrayList<>();
    final TextureRegistry textureRegistry;
    final TestFlutterView flutterView;
    final PlatformViewsController pvc1;
    final PlatformViewsController2 pvc2;
    final PlatformViewsControllerDelegator delegator;
    boolean deferTraversal = false;
    boolean holdTransactionCommits = false;
    final List<Runnable> pendingCommitCallbacks = new ArrayList<>();
    final List<Float> legacyAppliedAlphas = new ArrayList<>();
    final List<Rect> legacyAppliedCrops = new ArrayList<>();

    Harness(Context context, boolean hcppEnabled, @Nullable TestFlutterView customFlutterView) {
      this.context = context;
      this.jni = mock(FlutterJNI.class);
      when(this.jni.isAttached()).thenReturn(true);
      when(this.jni.IsSurfaceControlEnabled()).thenReturn(hcppEnabled);
      when(this.jni.getIsSoftwareRenderingEnabled()).thenReturn(false);
      doAnswer(
              inv -> {
                String channel = inv.getArgument(0);
                ByteBuffer message = inv.getArgument(1);
                int position = inv.getArgument(2);
                if (message != null) {
                  ByteBuffer copy = ByteBuffer.allocate(position);
                  int oldPos = message.position();
                  int oldLim = message.limit();
                  message.position(0);
                  message.limit(position);
                  copy.put(message);
                  message.limit(oldLim);
                  message.position(oldPos);
                  copy.flip();
                  try {
                    MethodCall decoded = StandardMethodCodec.INSTANCE.decodeMethodCall(copy);
                    outboundCalls.add(new OutboundCall(channel, decoded));
                  } catch (RuntimeException ignored) {
                    // Non-StandardMethodCodec channel message.
                  }
                }
                return null;
              })
          .when(this.jni)
          .dispatchPlatformMessage(any(), any(), anyInt(), anyInt());
      this.executor = new DartExecutor(jni, context.getAssets());
      this.executor.onAttachedToJNI();
      this.renderer = new FlutterRenderer(jni);

      AtomicInteger nextTexId = new AtomicInteger(10);
      this.textureRegistry = mock(TextureRegistry.class);
      when(textureRegistry.createSurfaceProducer(any()))
          .thenAnswer(
              inv -> {
                FakeSurfaceProducer producer = new FakeSurfaceProducer(nextTexId.getAndIncrement());
                producers.add(producer);
                return producer;
              });

      this.flutterView =
          customFlutterView != null ? customFlutterView : new TestFlutterView(context);

      this.pvc1 =
          new PlatformViewsController() {
            @Override
            void applyLegacySurfaceTransaction(
                @NonNull SurfaceControl sc, float effectiveAlpha, @NonNull Rect localCrop) {
              legacyAppliedAlphas.add(effectiveAlpha);
              legacyAppliedCrops.add(new Rect(localCrop));
              super.applyLegacySurfaceTransaction(sc, effectiveAlpha, localCrop);
            }
          };
      this.pvc1.setFlutterJNI(jni);
      this.pvc1.attachToFlutterRenderer(renderer);
      this.pvc1.attachToView(this.flutterView);

      this.pvc2 =
          new PlatformViewsController2() {
            @Override
            boolean shouldDeferUntilNextTraversal() {
              return deferTraversal;
            }

            @Override
            boolean registerTransactionCommittedCallback(
                @NonNull SurfaceControl.Transaction tx, @NonNull Runnable onCommitted) {
              if (holdTransactionCommits) {
                synchronized (pendingCommitCallbacks) {
                  pendingCommitCallbacks.add(onCommitted);
                }
              } else {
                new android.os.Handler(Looper.getMainLooper()).post(onCommitted);
              }
              return true;
            }
          };
      this.pvc2.setRegistry(pvc1.getRegistry());
      this.pvc2.setFlutterJNI(jni);
      this.pvc2.attachToFlutterRenderer(renderer);
      this.pvc2.attachToView(this.flutterView);

      this.delegator = new PlatformViewsControllerDelegator(pvc1, pvc2);
      this.delegator.attach(context, textureRegistry, executor);
    }

    void registerFactory(String viewType, PlatformViewFactory factory) {
      pvc1.getRegistry().registerViewFactory(viewType, factory);
    }
  }

  @Before
  public void setUp() {
    FlutterRenderer.debugDisableSurfaceClear = true;
  }

  @Test
  public void defaultCreationRoutesPlainViewToTlhcAndUpgradeAllDefaultsFalse() {
    Context context = ApplicationProvider.getApplicationContext();
    Harness h = new Harness(context, /* hcppEnabled= */ true, null);

    assertFalse(h.pvc2.shouldUpgradeAllViewsToHcpp());
    assertFalse(h.delegator.shouldUpgradeAllViewsToHcpp());

    FrameLayout plainView = new FrameLayout(context);
    h.registerFactory(
        "plain-view",
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @NonNull
          @Override
          public PlatformView create(Context ctx, int id, @Nullable Object args) {
            return new SimplePlatformView(plainView, /* nativeHint= */ false);
          }
        });

    PlatformViewCreationRequest request =
        PlatformViewCreationRequest.createTLHCWithFallbackRequest(
            1, "plain-view", 0, 0, 120, 80, View.LAYOUT_DIRECTION_LTR, true, false, null);
    long textureId = h.delegator.createForTextureLayer(request);

    assertTrue((textureId) >= 0L);
    assertFalse(h.delegator.usesVirtualDisplay(1));
    assertFalse(h.pvc1.usesVirtualDisplay(1));
    assertNull(h.pvc2.getPlatformViewById(1));
    assertSame(plainView, h.pvc1.getPlatformViewById(1));
    assertTrue((plainView.getParent()) instanceof PlatformViewWrapper);
    PlatformViewWrapper wrapper = (PlatformViewWrapper) plainView.getParent();
    assertTrue(wrapper.isCaptureToTextureMode());
    assertTrue(wrapper.hasActiveRenderTarget());
  }

  @Test
  public void creationWithSurfaceViewRoutesDirectlyToHcppAndRetiresVirtualDisplay() {
    Context context = ApplicationProvider.getApplicationContext();
    Harness h = new Harness(context, /* hcppEnabled= */ true, null);

    FrameLayout containerWithSv1 = new FrameLayout(context);
    containerWithSv1.addView(new SurfaceView(context));

    FrameLayout containerWithSv2 = new FrameLayout(context);
    containerWithSv2.addView(new SurfaceView(context));

    h.registerFactory(
        "sv-view",
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @NonNull
          @Override
          public PlatformView create(Context ctx, int id, @Nullable Object args) {
            return new SimplePlatformView(
                id == 1 ? containerWithSv1 : containerWithSv2, /* nativeHint= */ false);
          }
        });

    // Both hybridFallback=false (initAndroidView) and hybridFallback=true (initSurfaceAndroidView)
    // must retire Virtual Display and route directly to HCPP on API 34+.
    PlatformViewCreationRequest reqInitAndroidView =
        PlatformViewCreationRequest.createTLHCWithFallbackRequest(
            1,
            "sv-view",
            0,
            0,
            100,
            100,
            View.LAYOUT_DIRECTION_LTR,
            /* hybridFallback= */ false,
            false,
            null);
    long res1 = h.delegator.createForTextureLayer(reqInitAndroidView);
    assertEquals(PlatformViewsChannel.PlatformViewsHandler.NON_TEXTURE_FALLBACK, res1);
    assertTrue((h.pvc1.vdControllers).isEmpty());
    assertFalse(h.delegator.usesVirtualDisplay(1));
    assertSame(containerWithSv1, h.pvc2.getPlatformViewById(1));

    PlatformViewCreationRequest reqSurfaceAndroidView =
        PlatformViewCreationRequest.createTLHCWithFallbackRequest(
            2,
            "sv-view",
            0,
            0,
            100,
            100,
            View.LAYOUT_DIRECTION_LTR,
            /* hybridFallback= */ true,
            false,
            null);
    long res2 = h.delegator.createForTextureLayer(reqSurfaceAndroidView);
    assertEquals(PlatformViewsChannel.PlatformViewsHandler.NON_TEXTURE_FALLBACK, res2);
    assertTrue((h.pvc1.vdControllers).isEmpty());
    assertSame(containerWithSv2, h.pvc2.getPlatformViewById(2));
  }

  @Test
  @Config(sdk = 33)
  public void creationWithSurfaceViewOnApi33RoutesDirectlyToLegacyHcNeverVirtualDisplay() {
    Context context = ApplicationProvider.getApplicationContext();
    Harness h = new Harness(context, /* hcppEnabled= */ false, null);

    FrameLayout containerWithSv = new FrameLayout(context);
    containerWithSv.addView(new SurfaceView(context));
    h.registerFactory(
        "sv-api33",
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @NonNull
          @Override
          public PlatformView create(Context ctx, int id, @Nullable Object args) {
            return new SimplePlatformView(containerWithSv, /* nativeHint= */ false);
          }
        });

    PlatformViewCreationRequest req =
        PlatformViewCreationRequest.createTLHCWithFallbackRequest(
            5,
            "sv-api33",
            0,
            0,
            100,
            100,
            View.LAYOUT_DIRECTION_LTR,
            /* hybridFallback= */ false,
            false,
            null);
    long result = h.delegator.createForTextureLayer(req);
    assertEquals(PlatformViewsChannel.PlatformViewsHandler.NON_TEXTURE_FALLBACK, result);
    assertTrue((h.pvc1.vdControllers).isEmpty());
    assertFalse(h.delegator.usesVirtualDisplay(5));
    assertSame(containerWithSv, h.pvc1.getPlatformViewById(5));
  }

  @Test
  public void requiresNativeCompositionPluginHintAndPreferTextureEscapeHatch() {
    Context context = ApplicationProvider.getApplicationContext();
    Harness h = new Harness(context, /* hcppEnabled= */ true, null);

    h.registerFactory(
        "hinted-plain",
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @NonNull
          @Override
          public PlatformView create(Context ctx, int id, @Nullable Object args) {
            return new SimplePlatformView(new FrameLayout(ctx), /* nativeHint= */ true);
          }
        });
    h.registerFactory(
        "hinted-with-sv",
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @NonNull
          @Override
          public PlatformView create(Context ctx, int id, @Nullable Object args) {
            FrameLayout root = new FrameLayout(ctx);
            root.addView(new SurfaceView(ctx));
            return new SimplePlatformView(root, /* nativeHint= */ true);
          }
        });

    // 1. requiresNativeComposition() == true without preferTexture -> HCPP from frame 0.
    long r1 =
        h.delegator.createForTextureLayer(
            PlatformViewCreationRequest.createTLHCWithFallbackRequest(
                10, "hinted-plain", 0, 0, 100, 100, View.LAYOUT_DIRECTION_LTR, true, false, null));
    assertEquals(PlatformViewsChannel.PlatformViewsHandler.NON_TEXTURE_FALLBACK, r1);
    assertNotNull(h.pvc2.getPlatformViewById(10));

    // 2. preferTexture == true without SurfaceView overrides requiresNativeComposition() -> TLHC!
    long r2 =
        h.delegator.createForTextureLayer(
            PlatformViewCreationRequest.createTLHCWithFallbackRequest(
                11, "hinted-plain", 0, 0, 100, 100, View.LAYOUT_DIRECTION_LTR, true, true, null));
    assertTrue((r2) >= 0L);
    assertNull(h.pvc2.getPlatformViewById(11));
    assertNotNull(h.pvc1.getPlatformViewById(11));

    // 3. preferTexture == true WITH an actual SurfaceView still routes to HCPP because TLHC cannot
    // display SurfaceViews.
    long r3 =
        h.delegator.createForTextureLayer(
            PlatformViewCreationRequest.createTLHCWithFallbackRequest(
                12, "hinted-with-sv", 0, 0, 100, 100, View.LAYOUT_DIRECTION_LTR, true, true, null));
    assertEquals(PlatformViewsChannel.PlatformViewsHandler.NON_TEXTURE_FALLBACK, r3);
    assertNotNull(h.pvc2.getPlatformViewById(12));
  }

  @Test
  public void launchConfigsShouldUpgradeAllViewsToHcppPrecedence() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    assertFalse(FlutterActivityLaunchConfigs.shouldUpgradeAllViewsToHcpp(context, null));

    // Application meta-data = true.
    PackageInfo pkgInfo =
        context
            .getPackageManager()
            .getPackageInfo(context.getPackageName(), PackageManager.GET_META_DATA);
    pkgInfo.applicationInfo.metaData = new Bundle();
    pkgInfo.applicationInfo.metaData.putBoolean(
        FlutterActivityLaunchConfigs.HCPP_UPGRADE_ALL_VIEWS_META_DATA_KEY, true);
    shadowOf(context.getPackageManager()).installPackage(pkgInfo);
    assertTrue(FlutterActivityLaunchConfigs.shouldUpgradeAllViewsToHcpp(context, null));

    // Activity meta-data = false overrides Application meta-data = true.
    ActivityInfo actInfo = new ActivityInfo();
    actInfo.packageName = context.getPackageName();
    actInfo.name = Activity.class.getName();
    actInfo.metaData = new Bundle();
    actInfo.metaData.putBoolean(
        FlutterActivityLaunchConfigs.HCPP_UPGRADE_ALL_VIEWS_META_DATA_KEY, false);
    shadowOf(context.getPackageManager()).addOrUpdateActivity(actInfo);

    Activity activityNoExtra =
        Robolectric.buildActivity(Activity.class, new Intent()).create().get();
    assertFalse(FlutterActivityLaunchConfigs.shouldUpgradeAllViewsToHcpp(context, activityNoExtra));

    // Activity Intent extra = true overrides Activity meta-data = false.
    Intent intentOverride = new Intent();
    intentOverride.putExtra(FlutterActivityLaunchConfigs.EXTRA_HCPP_UPGRADE_ALL_VIEWS, true);
    Activity activityWithExtra =
        Robolectric.buildActivity(Activity.class, intentOverride).create().get();
    assertTrue(
        FlutterActivityLaunchConfigs.shouldUpgradeAllViewsToHcpp(context, activityWithExtra));

    // Clean up ApplicationInfo metadata.
    pkgInfo.applicationInfo.metaData = null;
    shadowOf(context.getPackageManager()).installPackage(pkgInfo);
  }

  @Test
  public void zeroDetachDynamicUpgradeTlhcToHcppPreservesAttachFocusAndDefersTextureRelease() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    TestFlutterView liveFlutterView = new TestFlutterView(activity);
    activity.setContentView(liveFlutterView);

    Harness h = new Harness(activity, /* hcppEnabled= */ true, liveFlutterView);

    FrameLayout embeddedContainer = new FrameLayout(activity);
    EditText editText = new EditText(activity);
    editText.setFocusable(true);
    editText.setFocusableInTouchMode(true);
    embeddedContainer.addView(editText);

    AtomicInteger attachCount = new AtomicInteger(0);
    AtomicInteger detachCount = new AtomicInteger(0);
    embeddedContainer.addOnAttachStateChangeListener(
        new View.OnAttachStateChangeListener() {
          @Override
          public void onViewAttachedToWindow(@NonNull View v) {
            attachCount.incrementAndGet();
          }

          @Override
          public void onViewDetachedFromWindow(@NonNull View v) {
            detachCount.incrementAndGet();
          }
        });

    h.registerFactory(
        "dynamic-hcpp",
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @NonNull
          @Override
          public PlatformView create(Context ctx, int id, @Nullable Object args) {
            return new SimplePlatformView(embeddedContainer, /* nativeHint= */ false);
          }
        });

    long texId =
        h.delegator.createForTextureLayer(
            PlatformViewCreationRequest.createTLHCWithFallbackRequest(
                21, "dynamic-hcpp", 0, 0, 150, 100, View.LAYOUT_DIRECTION_LTR, true, false, null));
    assertTrue((texId) >= 0L);
    assertEquals(1, (h.producers).size());
    FakeSurfaceProducer producer = h.producers.get(0);

    PlatformViewWrapper wrapperBefore = (PlatformViewWrapper) embeddedContainer.getParent();
    assertTrue(embeddedContainer.isAttachedToWindow());
    assertEquals(1, attachCount.get());
    assertEquals(0, detachCount.get());

    editText.requestFocus();
    assertTrue(editText.hasFocus());
    h.outboundCalls.clear();

    // Dynamically add a SurfaceView inside a nested FrameLayout.
    FrameLayout nestedChild = new FrameLayout(activity);
    embeddedContainer.addView(nestedChild);
    SurfaceView dynamicSv = new SurfaceView(activity);
    nestedChild.addView(dynamicSv);
    shadowOf(Looper.getMainLooper()).idle();

    // Zero-detach invariants: same wrapper parent, 1 attach, 0 detaches, focus preserved!
    assertSame(wrapperBefore, embeddedContainer.getParent());
    assertTrue(embeddedContainer.isAttachedToWindow());
    assertEquals(1, attachCount.get());
    assertEquals(0, detachCount.get());
    assertTrue(editText.hasFocus());
    assertSame(embeddedContainer, h.pvc2.getPlatformViewById(21));

    // Verify viewCompositionChanged('hcpp') was sent on SystemChannels.platform_views.
    OutboundCall modeChanged =
        h.outboundCalls.stream()
            .filter(c -> "viewCompositionChanged".equals(c.methodCall.method))
            .findFirst()
            .orElse(null);
    assertNotNull(modeChanged);
    assertEquals("flutter/platform_views", modeChanged.channel);
    @SuppressWarnings("unchecked")
    Map<String, Object> args = (Map<String, Object>) modeChanged.methodCall.arguments;
    assertEquals(21, args.get("id"));
    assertEquals("hcpp", args.get("mode"));

    // RenderTarget MUST remain alive until the first HCPP frame commits!
    assertTrue(wrapperBefore.hasActiveRenderTarget());
    assertEquals(0, producer.releaseCount);
    assertEquals(0L, h.pvc2.getUiThreadEndFrameCount());

    // Simulate the first HCPP frame displaying view 21 and committing onEndFrame.
    FlutterMutatorsStack stack = new FlutterMutatorsStack();
    h.pvc2.onDisplayPlatformView(21, 10, 20, 150, 100, 150, 100, stack);
    h.pvc2.swapTransactions();
    h.pvc2.onEndFrame();

    assertFalse(wrapperBefore.isCaptureToTextureMode());
    assertFalse(wrapperBefore.hasActiveRenderTarget());
    assertEquals(1, producer.releaseCount);
    assertEquals(1L, h.pvc2.getUiThreadEndFrameCount());
    assertEquals(0, detachCount.get());
  }

  @Test
  @Config(sdk = 33)
  public void zeroDetachDynamicUpgradeTlhcToLegacyHcKeepsWrapperVisibleUntilImageAcquired() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    TestFlutterView liveFlutterView = new TestFlutterView(activity);
    activity.setContentView(liveFlutterView);

    Harness h = new Harness(activity, /* hcppEnabled= */ false, liveFlutterView);
    FrameLayout embeddedContainer = new FrameLayout(activity);
    AtomicInteger detachCount = new AtomicInteger(0);
    embeddedContainer.addOnAttachStateChangeListener(
        new View.OnAttachStateChangeListener() {
          @Override
          public void onViewAttachedToWindow(@NonNull View v) {}

          @Override
          public void onViewDetachedFromWindow(@NonNull View v) {
            detachCount.incrementAndGet();
          }
        });

    h.registerFactory(
        "dynamic-hc",
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @NonNull
          @Override
          public PlatformView create(Context ctx, int id, @Nullable Object args) {
            return new SimplePlatformView(embeddedContainer, /* nativeHint= */ false);
          }
        });

    long texId =
        h.delegator.createForTextureLayer(
            PlatformViewCreationRequest.createTLHCWithFallbackRequest(
                31, "dynamic-hc", 0, 0, 120, 90, View.LAYOUT_DIRECTION_LTR, true, false, null));
    assertTrue((texId) >= 0L);
    FakeSurfaceProducer producer = h.producers.get(0);
    PlatformViewWrapper wrapper = (PlatformViewWrapper) embeddedContainer.getParent();

    h.outboundCalls.clear();
    embeddedContainer.addView(new SurfaceView(activity));
    shadowOf(Looper.getMainLooper()).idle();

    OutboundCall modeChanged =
        h.outboundCalls.stream()
            .filter(c -> "viewCompositionChanged".equals(c.methodCall.method))
            .findFirst()
            .orElse(null);
    assertNotNull(modeChanged);
    @SuppressWarnings("unchecked")
    Map<String, Object> args = (Map<String, Object>) modeChanged.methodCall.arguments;
    assertEquals(31, args.get("id"));
    assertEquals("hc", args.get("mode"));
    assertEquals(0, detachCount.get());

    // Frame A: HC frame begins, but FlutterImageView has NOT yet acquired an image.
    // Wrapper must stay VISIBLE (preventing SurfaceView surfaceDestroyed) and keep its texture!
    liveFlutterView.imageViewFrameAcquired = false;
    h.pvc1.onBeginFrame();
    h.pvc1.onDisplayPlatformView(31, 0, 0, 120, 90, 120, 90, new FlutterMutatorsStack());
    h.pvc1.onEndFrame();
    assertEquals(View.VISIBLE, wrapper.getVisibility());
    assertTrue(wrapper.hasActiveRenderTarget());
    assertEquals(0, producer.releaseCount);

    // Frame B: FlutterImageView acquires its synchronized image -> texture released cleanly!
    liveFlutterView.imageViewFrameAcquired = true;
    h.pvc1.onBeginFrame();
    h.pvc1.onDisplayPlatformView(31, 0, 0, 120, 90, 120, 90, new FlutterMutatorsStack());
    h.pvc1.onEndFrame();
    assertEquals(View.VISIBLE, wrapper.getVisibility());
    assertFalse(wrapper.hasActiveRenderTarget());
    assertEquals(1, producer.releaseCount);
    assertEquals(0, detachCount.get());
  }

  @Test
  public void rapidDisposeDuringPendingUpgradeReleasesRenderTargetImmediatelyWithoutLeak() {
    Context context = ApplicationProvider.getApplicationContext();
    Harness h = new Harness(context, /* hcppEnabled= */ true, null);

    FrameLayout container = new FrameLayout(context);
    h.registerFactory(
        "rapid-dispose",
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @NonNull
          @Override
          public PlatformView create(Context ctx, int id, @Nullable Object args) {
            return new SimplePlatformView(container, /* nativeHint= */ false);
          }
        });

    h.delegator.createForTextureLayer(
        PlatformViewCreationRequest.createTLHCWithFallbackRequest(
            41, "rapid-dispose", 0, 0, 100, 100, View.LAYOUT_DIRECTION_LTR, true, false, null));
    FakeSurfaceProducer producer = h.producers.get(0);

    container.addView(new SurfaceView(context));
    shadowOf(Looper.getMainLooper()).idle();

    // Dispose immediately BEFORE onDisplayPlatformView / onEndFrame ever runs!
    h.delegator.dispose(41);
    assertEquals(1, producer.releaseCount);
    assertNull(h.delegator.getPlatformViewById(41));

    // Subsequent onEndFrame must not double-release or throw.
    h.pvc2.swapTransactions();
    h.pvc2.onEndFrame();
    assertEquals(1, producer.releaseCount);
  }

  @Test
  public void surfaceViewDetectionPrimaryTransparentRegionBackupLayoutScanAndStickyOneWay() {
    Context context = ApplicationProvider.getApplicationContext();

    // 1. Primary hook: requestTransparentRegion propagates to parent AND triggers listener once.
    AtomicBoolean parentSawTransparentRegion = new AtomicBoolean(false);
    FrameLayout outerParent =
        new FrameLayout(context) {
          @Override
          public void requestTransparentRegion(View child) {
            parentSawTransparentRegion.set(true);
            super.requestTransparentRegion(child);
          }
        };
    PlatformViewWrapper wrapper1 = new PlatformViewWrapper(context);
    outerParent.addView(wrapper1);
    AtomicInteger detectCount1 = new AtomicInteger(0);
    wrapper1.setOnSurfaceViewDetectedListener(detectCount1::incrementAndGet);

    // Plain View child does NOT trigger upgrade.
    FrameLayout plainChild = new FrameLayout(context);
    wrapper1.addView(plainChild);
    wrapper1.layout(0, 0, 100, 100);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(0, detectCount1.get());

    SurfaceView sv1 = new SurfaceView(context);
    plainChild.addView(sv1);
    wrapper1.requestTransparentRegion(sv1);
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(parentSawTransparentRegion.get());
    assertEquals(1, detectCount1.get());

    // Sticky one-way: repeated requests / layouts never fire again.
    wrapper1.requestTransparentRegion(sv1);
    wrapper1.layout(0, 0, 120, 120);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, detectCount1.get());

    // 2. Backup hook: SurfaceView subclass that suppresses requestTransparentRegion is still caught
    // by the debounced onLayout hierarchy scan.
    PlatformViewWrapper wrapper2 = new PlatformViewWrapper(context);
    AtomicInteger detectCount2 = new AtomicInteger(0);
    wrapper2.setOnSurfaceViewDetectedListener(detectCount2::incrementAndGet);
    SurfaceView silentSv =
        new SurfaceView(context) {
          @Override
          public void onAttachedToWindow() {
            // Intentionally skip super to suppress requestTransparentRegion.
          }
        };
    FrameLayout nested = new FrameLayout(context);
    nested.addView(silentSv);
    wrapper2.addView(nested);
    wrapper2.layout(0, 0, 100, 100);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, detectCount2.get());
  }

  @Test
  public void flutterMutatorViewKeepsLayerTypeNoneWhenDescendantSurfaceViewPresentUnderOpacity() {
    Context context = ApplicationProvider.getApplicationContext();
    FlutterMutatorView mutatorView = new FlutterMutatorView(context, 1.0f, null);
    FlutterMutatorsStack stack = new FlutterMutatorsStack();
    stack.pushOpacity(0.85f);
    mutatorView.readyToDisplay(stack, 0, 0, 200, 200);

    FrameLayout container = new FrameLayout(context);
    mutatorView.addView(container);

    // Without a SurfaceView, opacity < 1.0 uses LAYER_TYPE_HARDWARE.
    mutatorView.draw(new Canvas());
    assertEquals(View.LAYER_TYPE_HARDWARE, mutatorView.getLayerType());

    // Once a descendant SurfaceView is present, draw() must reset to LAYER_TYPE_NONE so
    // SurfaceView's PorterDuff.Mode.CLEAR punches through to the root window buffer.
    container.addView(new SurfaceView(context));
    mutatorView.draw(new Canvas());
    assertEquals(View.LAYER_TYPE_NONE, mutatorView.getLayerType());
  }

  @Test
  public void computeLocalSurfaceViewCropHandlesNestedOffsetsTranslationsClipsAndEmptyAlphaZero() {
    Context context = ApplicationProvider.getApplicationContext();

    // Build nested hierarchy: LinearLayout (rootEmbeddedView) -> FrameLayout -> SurfaceView.
    LinearLayout root = new LinearLayout(context);
    root.layout(0, 0, 300, 300);

    FrameLayout mid = new FrameLayout(context);
    mid.layout(20, 30, 260, 270);
    mid.setTranslationX(5f);
    mid.setTranslationY(-10f);
    root.addView(mid);

    SurfaceView nestedSv = new SurfaceView(context);
    nestedSv.layout(15, 25, 135, 125); // 120x100 inside mid
    mid.addView(nestedSv);

    // PV placed at (100, 200) size 300x300.
    // SurfaceView screen rect = left: 100 + 20 + 5 + 15 = 140, top: 200 + 30 - 10 + 25 = 245,
    // right: 260, bottom: 345.
    FlutterMutatorsStack clippedStack = new FlutterMutatorsStack();
    clippedStack.pushTransform(new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1});
    clippedStack.pushClipRect(160, 260, 230, 320);

    Rect crop =
        PlatformViewsController2.computeLocalSurfaceViewCrop(
            nestedSv, root, 100, 200, 300, 300, clippedStack);
    assertEquals(new Rect(20, 15, 90, 75), crop);

    // Completely clipped out -> empty crop Rect!
    FlutterMutatorsStack disjointStack = new FlutterMutatorsStack();
    disjointStack.pushClipRect(0, 0, 50, 50);
    Rect emptyCrop =
        PlatformViewsController2.computeLocalSurfaceViewCrop(
            nestedSv, root, 100, 200, 300, 300, disjointStack);
    assertTrue(emptyCrop.isEmpty());

    // Verify PlatformViewsController2 applies setAlpha(sc, 0f) when crop is empty, and applies
    // independent crops/callbacks across MULTIPLE SurfaceViews in the same platform view!
    SurfaceControl sc1 = new SurfaceControl.Builder().setName("sc1").build();
    SurfaceControl sc2 = new SurfaceControl.Builder().setName("sc2").build();

    SurfaceView svVisible =
        new SurfaceView(context) {
          @Override
          public SurfaceControl getSurfaceControl() {
            return sc1;
          }
        };
    svVisible.layout(10, 10, 110, 110); // Screen (110, 210, 210, 310)

    SurfaceView svClippedOut =
        new SurfaceView(context) {
          @Override
          public SurfaceControl getSurfaceControl() {
            return sc2;
          }
        };
    svClippedOut.layout(220, 220, 290, 290); // Screen (320, 420, 390, 490)

    FrameLayout multiRoot = new FrameLayout(context);
    multiRoot.layout(0, 0, 300, 300);
    multiRoot.addView(svVisible);
    multiRoot.addView(svClippedOut);

    Map<SurfaceControl, Float> recordedAlphas = new HashMap<>();
    Map<SurfaceControl, Rect> recordedCrops = new HashMap<>();
    PlatformViewsController2 pvc2 =
        new PlatformViewsController2() {
          @Override
          void configureSurfaceViewTransaction(
              @NonNull SurfaceControl sc, float effectiveAlpha, @NonNull Rect localCrop) {
            super.configureSurfaceViewTransaction(sc, effectiveAlpha, localCrop);
            recordedAlphas.put(sc, effectiveAlpha);
            recordedCrops.put(sc, new Rect(localCrop));
          }

          @Override
          boolean shouldDeferUntilNextTraversal() {
            return false;
          }
        };
    PlatformViewRegistryImpl reg = new PlatformViewRegistryImpl();
    reg.registerViewFactory(
        "multi-sv",
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @NonNull
          @Override
          public PlatformView create(Context ctx, int id, @Nullable Object args) {
            return new SimplePlatformView(multiRoot, true);
          }
        });
    pvc2.setRegistry(reg);
    FlutterJNI jni = mock(FlutterJNI.class);
    DartExecutor exec = new DartExecutor(jni, context.getAssets());
    pvc2.attach(context, exec);
    TestFlutterView fv = new TestFlutterView(context);
    pvc2.attachToView(fv);

    pvc2.createFlutterPlatformView(
        PlatformViewCreationRequest.createHCPPRequest(
            77, "multi-sv", View.LAYOUT_DIRECTION_LTR, null));

    FlutterMutatorsStack halfClip = new FlutterMutatorsStack();
    halfClip.pushOpacity(0.75f);
    halfClip.pushClipRect(100, 200, 250, 350); // Includes svVisible, excludes svClippedOut

    pvc2.onDisplayPlatformView(77, 100, 200, 300, 300, 300, 300, halfClip);

    assertEquals(0.75f, recordedAlphas.get(sc1), 1e-6f);
    assertEquals(new Rect(0, 0, 100, 100), recordedCrops.get(sc1));
    assertEquals(0.0f, recordedAlphas.get(sc2), 1e-6f);
    assertTrue(recordedCrops.get(sc2).isEmpty());

    // Also verify two uncreated SurfaceViews (getSurfaceControl() == null) register independent
    // SurfaceHolder.Callbacks without overwriting each other!
    FakeSurfaceHolder holderA = new FakeSurfaceHolder();
    FakeSurfaceHolder holderB = new FakeSurfaceHolder();
    SurfaceView uncreatedA =
        new SurfaceView(context) {
          @Override
          public SurfaceControl getSurfaceControl() {
            return null;
          }

          @Override
          public SurfaceHolder getHolder() {
            return holderA;
          }
        };
    SurfaceView uncreatedB =
        new SurfaceView(context) {
          @Override
          public SurfaceControl getSurfaceControl() {
            return null;
          }

          @Override
          public SurfaceHolder getHolder() {
            return holderB;
          }
        };
    multiRoot.addView(uncreatedA);
    multiRoot.addView(uncreatedB);

    pvc2.onDisplayPlatformView(77, 100, 200, 300, 300, 300, 300, halfClip);
    assertEquals(1, (holderA.callbacks).size());
    assertEquals(1, (holderB.callbacks).size());
    // Re-displaying before surfaceCreated does NOT register duplicate callbacks.
    pvc2.onDisplayPlatformView(77, 100, 200, 300, 300, 300, 300, halfClip);
    assertEquals(1, (holderA.callbacks).size());
    assertEquals(1, (holderB.callbacks).size());

    sc1.release();
    sc2.release();
  }

  @Test
  public void bufferPoolMaxImagesTrimAndShrinkOnDownsize() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();

    // 1. Default FlutterRenderer SurfaceProducer uses 7 maxImages; PlatformViewRenderTarget trims
    // to MAX_IMAGES == 5.
    FlutterJNI jni = mock(FlutterJNI.class);
    FlutterRenderer renderer = new FlutterRenderer(jni);
    TextureRegistry.SurfaceProducer rawProducer =
        renderer.createSurfaceProducer(TextureRegistry.SurfaceLifecycle.manual);
    assertEquals("ImageReaderSurfaceProducer", rawProducer.getClass().getSimpleName());
    java.lang.reflect.Method getMaxImages =
        rawProducer.getClass().getDeclaredMethod("getMaxImages");
    getMaxImages.setAccessible(true);
    assertEquals(7, ((Integer) getMaxImages.invoke(rawProducer)).intValue());

    SurfaceProducerPlatformViewRenderTarget pvTarget =
        new SurfaceProducerPlatformViewRenderTarget(rawProducer);
    assertEquals(5, SurfaceProducerPlatformViewRenderTarget.MAX_IMAGES);
    assertEquals(5, ((Integer) getMaxImages.invoke(rawProducer)).intValue());
    pvTarget.release();

    // 2. PlatformViewsController.resize keeps renderTarget unshrunk synchronously during small or
    // in-flight shrinks, and shrinks + emits viewBufferResized after 150ms idle on <= 50% area!
    Harness h = new Harness(context, /* hcppEnabled= */ true, null);
    FrameLayout view = new FrameLayout(context);
    h.registerFactory(
        "shrink-view",
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @NonNull
          @Override
          public PlatformView create(Context ctx, int id, @Nullable Object args) {
            return new SimplePlatformView(view, false);
          }
        });

    h.delegator.createForTextureLayer(
        PlatformViewCreationRequest.createTLHCWithFallbackRequest(
            61, "shrink-view", 0, 0, 240, 180, View.LAYOUT_DIRECTION_LTR, true, false, null));
    FakeSurfaceProducer producer = h.producers.get(0);
    assertEquals(240, producer.getWidth());
    assertEquals(180, producer.getHeight());

    // Small shrink (> 50% area: 200x160 = 32,000 vs 240x180 = 43,200 -> 74% area) never shrinks.
    PlatformViewsChannel.PlatformViewBufferSize[] reportedSize =
        new PlatformViewsChannel.PlatformViewBufferSize[1];
    h.delegator.resize(
        new PlatformViewsChannel.PlatformViewResizeRequest(61, 200, 160),
        size -> reportedSize[0] = size);
    assertEquals(240.0, reportedSize[0].width, 1e-6);
    assertEquals(180.0, reportedSize[0].height, 1e-6);
    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
    assertEquals(240, producer.getWidth());
    assertEquals(180, producer.getHeight());

    // Large shrink (<= 50% area: 80x60 = 4,800 <= 21,600) reports 240x180 synchronously, then
    // shrinks to 80x60 and emits viewBufferResized after 150ms idle!
    h.outboundCalls.clear();
    AtomicBoolean resizeCompleted = new AtomicBoolean(false);
    h.delegator.resize(
        new PlatformViewsChannel.PlatformViewResizeRequest(61, 80, 60),
        size -> {
          reportedSize[0] = size;
          resizeCompleted.set(true);
        });
    assertTrue(resizeCompleted.get());
    assertEquals(240.0, reportedSize[0].width, 1e-6);
    assertEquals(180.0, reportedSize[0].height, 1e-6);
    assertEquals(240, producer.getWidth());
    assertEquals(180, producer.getHeight());

    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150));
    assertEquals(80, producer.getWidth());
    assertEquals(60, producer.getHeight());
    OutboundCall bufferResized =
        h.outboundCalls.stream()
            .filter(c -> "viewBufferResized".equals(c.methodCall.method))
            .findFirst()
            .orElse(null);
    assertNotNull(bufferResized);
    assertEquals("flutter/platform_views", bufferResized.channel);
    @SuppressWarnings("unchecked")
    Map<String, Object> resizeArgs = (Map<String, Object>) bufferResized.methodCall.arguments;
    assertEquals(61, resizeArgs.get("id"));
    assertEquals(80.0, (Double) resizeArgs.get("width"), 1e-6);
    assertEquals(60.0, (Double) resizeArgs.get("height"), 1e-6);

    // 3. ImageReaderPlatformViewRenderTarget clamps <= 0 dimensions to >= 1, sets MAX_IMAGES == 5,
    // and closes reader on release.
    TextureRegistry.ImageTextureEntry imgEntry = mock(TextureRegistry.ImageTextureEntry.class);
    ImageReaderPlatformViewRenderTarget imgTarget =
        new ImageReaderPlatformViewRenderTarget(imgEntry);
    assertEquals(5, ImageReaderPlatformViewRenderTarget.MAX_IMAGES);
    imgTarget.resize(0, 0);
    assertEquals(1, imgTarget.getWidth());
    assertEquals(1, imgTarget.getHeight());
    imgTarget.release();
    assertTrue(imgTarget.isReleased());
    verify(imgEntry, atLeastOnce()).pushImage(null);
  }

  @Test
  public void reviewRound2InvariantsSingleFocusChannelScrollCropSoftwareGuardAndLegacyHcScope() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    TestFlutterView liveFlutterView = new TestFlutterView(activity);
    activity.setContentView(liveFlutterView);

    // 1. After dynamic TLHC -> HCPP upgrade, focus events emit viewFocused ONLY once on
    // flutter/platform_views_2 (never duplicated on flutter/platform_views).
    Harness h = new Harness(activity, /* hcppEnabled= */ true, liveFlutterView);
    FrameLayout focusContainer = new FrameLayout(activity);
    EditText inputA = new EditText(activity);
    EditText inputB = new EditText(activity);
    inputA.setFocusable(true);
    inputA.setFocusableInTouchMode(true);
    inputB.setFocusable(true);
    inputB.setFocusableInTouchMode(true);
    focusContainer.addView(inputA);
    focusContainer.addView(inputB);

    h.registerFactory(
        "focus-pv",
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @NonNull
          @Override
          public PlatformView create(Context ctx, int id, @Nullable Object args) {
            return new SimplePlatformView(focusContainer, false);
          }
        });
    h.delegator.createForTextureLayer(
        PlatformViewCreationRequest.createTLHCWithFallbackRequest(
            81, "focus-pv", 0, 0, 120, 80, View.LAYOUT_DIRECTION_LTR, true, false, null));
    focusContainer.addView(new SurfaceView(activity));
    shadowOf(Looper.getMainLooper()).idle();

    h.outboundCalls.clear();
    inputB.requestFocus();
    shadowOf(Looper.getMainLooper()).idle();

    long focusCallsV1 =
        h.outboundCalls.stream()
            .filter(
                c ->
                    "flutter/platform_views".equals(c.channel)
                        && "viewFocused".equals(c.methodCall.method))
            .count();
    long focusCallsV2 =
        h.outboundCalls.stream()
            .filter(
                c ->
                    "flutter/platform_views_2".equals(c.channel)
                        && "viewFocused".equals(c.methodCall.method))
            .count();
    assertEquals(0L, focusCallsV1);
    assertEquals(1L, focusCallsV2);

    // 2. Software rendering mode suppresses dynamic SurfaceView native-composition upgrade.
    Harness swHarness = new Harness(activity, /* hcppEnabled= */ false, liveFlutterView);
    swHarness.pvc1.setSoftwareRendering(true);
    FrameLayout swContainer = new FrameLayout(activity);
    swHarness.registerFactory(
        "sw-pv",
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @NonNull
          @Override
          public PlatformView create(Context ctx, int id, @Nullable Object args) {
            return new SimplePlatformView(swContainer, false);
          }
        });
    swHarness.delegator.createForTextureLayer(
        PlatformViewCreationRequest.createTLHCWithFallbackRequest(
            82, "sw-pv", 0, 0, 100, 100, View.LAYOUT_DIRECTION_LTR, true, false, null));
    swHarness.outboundCalls.clear();
    swContainer.addView(new SurfaceView(activity));
    shadowOf(Looper.getMainLooper()).idle();
    assertFalse(
        swHarness.outboundCalls.stream()
            .anyMatch(c -> "viewCompositionChanged".equals(c.methodCall.method)));

    // 3. computeLocalSurfaceViewCrop accounts for ancestor getScrollX() / getScrollY().
    FrameLayout scrolledRoot = new FrameLayout(activity);
    scrolledRoot.layout(0, 0, 300, 300);
    scrolledRoot.scrollTo(30, 50);
    SurfaceView scrolledSv = new SurfaceView(activity);
    scrolledSv.layout(40, 70, 140, 170); // Visual in root: (10, 20)..(110, 120)
    scrolledRoot.addView(scrolledSv);
    FlutterMutatorsStack scrollClip = new FlutterMutatorsStack();
    scrollClip.pushClipRect(
        130, 240, 190, 300); // PV at (100, 200) -> SV screen (110, 220)..(210, 320)
    Rect scrollCrop =
        PlatformViewsController2.computeLocalSurfaceViewCrop(
            scrolledSv, scrolledRoot, 100, 200, 300, 300, scrollClip);
    assertEquals(new Rect(20, 20, 80, 80), scrollCrop);
  }

  @Test
  public void upstreamRegressionCoverageDelegatorFallbackPendingTxAndAttachedMutatorCache()
      throws Exception {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    TestFlutterView liveFlutterView = new TestFlutterView(activity);
    activity.setContentView(liveFlutterView);

    // 1. PlatformViewsControllerDelegator delegates shouldUpgradeAllViewsToHcpp, resize, and
    // dispose to PlatformViewsController2 when view lives in PVC2.
    Harness h = new Harness(activity, /* hcppEnabled= */ true, liveFlutterView);
    assertFalse(h.delegator.shouldUpgradeAllViewsToHcpp());
    h.pvc2.setUpgradeAllViewsToHcpp(true);
    assertTrue(h.delegator.shouldUpgradeAllViewsToHcpp());
    h.pvc2.setUpgradeAllViewsToHcpp(false);

    FrameLayout hcppView = new FrameLayout(activity);
    h.registerFactory(
        "direct-hcpp",
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @NonNull
          @Override
          public PlatformView create(Context ctx, int id, @Nullable Object args) {
            return new SimplePlatformView(hcppView, true);
          }
        });
    h.pvc2.createFlutterPlatformView(
        PlatformViewCreationRequest.createHCPPRequest(
            91, "direct-hcpp", View.LAYOUT_DIRECTION_LTR, null));
    PlatformViewsChannel.PlatformViewBufferSize[] hcppResized =
        new PlatformViewsChannel.PlatformViewBufferSize[1];
    h.delegator.resize(
        new PlatformViewsChannel.PlatformViewResizeRequest(91, 140, 95),
        size -> hcppResized[0] = size);
    assertNotNull(hcppResized[0]);
    assertEquals(140.0, hcppResized[0].width, 1e-6);
    assertEquals(95.0, hcppResized[0].height, 1e-6);
    h.delegator.dispose(91);
    assertNull(h.delegator.getPlatformViewById(91));

    // 2. PlatformViewsController.createForTextureLayer with HYBRID_FALLBACK falls back to Hybrid
    // Composition (NON_TEXTURE_FALLBACK == -2L) rather than VirtualDisplay when view contains a
    // SurfaceView at creation time and HCPP is unavailable.
    Harness hcHarness = new Harness(activity, /* hcppEnabled= */ false, liveFlutterView);
    SurfaceView eagerSv = new SurfaceView(activity);
    hcHarness.registerFactory(
        "eager-sv-hc",
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @NonNull
          @Override
          public PlatformView create(Context ctx, int id, @Nullable Object args) {
            return new SimplePlatformView(eagerSv, false);
          }
        });
    long fallbackResult =
        hcHarness.delegator.createForTextureLayer(
            PlatformViewCreationRequest.createTLHCWithFallbackRequest(
                92, "eager-sv-hc", 0, 0, 100, 100, View.LAYOUT_DIRECTION_LTR, true, false, null));
    assertEquals(PlatformViewsChannel.PlatformViewsHandler.NON_TEXTURE_FALLBACK, fallbackResult);

    // 3. PlatformViewsController2.hasPendingTransactions + waitForPendingTransactions survive
    // onTraversalComplete() until SurfaceControl.TransactionCommittedListener fires (B2').
    assertFalse(h.pvc2.hasPendingTransactions());
    assertTrue(h.pvc2.waitForPendingTransactions(10));
    h.deferTraversal = true;
    h.holdTransactionCommits = true;
    SurfaceControl.Transaction stagedTx = h.pvc2.createUnpublishedTransaction();
    assertNotNull(stagedTx);
    h.pvc2.publishTransaction(stagedTx);
    h.pvc2.sealFrameTransactions();
    assertTrue(h.pvc2.hasPendingTransactions());
    h.pvc2.swapTransactions();
    assertTrue(h.pvc2.hasPendingTransactions());
    h.pvc2.onEndFrame();
    assertTrue(h.pvc2.hasPendingTransactions());
    // Drain Choreographer traversal on UI thread (onTraversalComplete runs), while holding the
    // SurfaceFlinger commit callback: pending MUST stay true and bounded wait MUST time out!
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, (h.pendingCommitCallbacks).size());
    assertTrue(h.pvc2.hasPendingTransactions());
    assertFalse(h.pvc2.waitForPendingTransactions(15));
    assertTrue(h.pvc2.hasPendingTransactions());

    java.util.concurrent.CountDownLatch enteredWait = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean waitResult =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    Thread rasterWaiter =
        new Thread(
            () -> {
              enteredWait.countDown();
              waitResult.set(h.pvc2.waitForPendingTransactions(2000));
            });
    rasterWaiter.start();
    assertTrue(enteredWait.await(1, java.util.concurrent.TimeUnit.SECONDS));
    final Runnable commitCallback;
    synchronized (h.pendingCommitCallbacks) {
      commitCallback = h.pendingCommitCallbacks.remove(0);
    }
    commitCallback.run();
    rasterWaiter.join(2000);
    assertFalse(rasterWaiter.isAlive());
    assertTrue(waitResult.get());
    assertFalse(h.pvc2.hasPendingTransactions());
    h.holdTransactionCommits = false;
    h.deferTraversal = false;

    // 4. Window-attached FlutterMutatorView caches O(1) hasDescendantSurfaceView across draws
    // without rescanning on non-SV child invalidations (S9), detects nested SurfaceView additions
    // both before and after layout, and invalidates back to false + restores LAYER_TYPE_HARDWARE
    // when detached.
    FlutterMutatorView attachedMutator = new FlutterMutatorView(activity, 1.0f, null);
    FlutterMutatorsStack opacityStack = new FlutterMutatorsStack();
    opacityStack.pushOpacity(0.6f);
    attachedMutator.readyToDisplay(opacityStack, 0, 0, 150, 150);
    FrameLayout nestedHolder = new FrameLayout(activity);
    View nonSvChild = new View(activity);
    nestedHolder.addView(nonSvChild);
    attachedMutator.addView(nestedHolder);
    liveFlutterView.addView(attachedMutator);
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(attachedMutator.isAttachedToWindow());
    assertTrue(attachedMutator.isLaidOut());
    assertFalse(attachedMutator.hasDescendantSurfaceView());
    assertFalse(attachedMutator.isDescendantSurfaceViewCacheDirty());
    attachedMutator.draw(new Canvas());
    assertEquals(View.LAYER_TYPE_HARDWARE, attachedMutator.getLayerType());

    // Repeated non-SV invalidations (e.g. WebView redraws) MUST NOT mark the SV cache dirty!
    nonSvChild.invalidate();
    nestedHolder.invalidate();
    assertFalse(attachedMutator.isDescendantSurfaceViewCacheDirty());
    attachedMutator.draw(new Canvas());
    assertFalse(attachedMutator.isDescendantSurfaceViewCacheDirty());

    SurfaceView dynamicChildSv = new SurfaceView(activity);
    nestedHolder.addView(dynamicChildSv);
    // Detected immediately before layout (because isLayoutRequested() == true) AND cached after!
    assertTrue(attachedMutator.hasDescendantSurfaceView());
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(attachedMutator.hasDescendantSurfaceView());
    assertFalse(attachedMutator.isDescendantSurfaceViewCacheDirty());
    attachedMutator.draw(new Canvas());
    assertEquals(View.LAYER_TYPE_NONE, attachedMutator.getLayerType());

    nestedHolder.removeView(dynamicChildSv);
    shadowOf(Looper.getMainLooper()).idle();
    assertFalse(attachedMutator.hasDescendantSurfaceView());
    attachedMutator.draw(new Canvas());
    assertEquals(View.LAYER_TYPE_HARDWARE, attachedMutator.getLayerType());

    // 5. S7: SurfaceView's own getAlpha() / ancestor getAlpha() and getClipBounds() compose with
    // Flutter's mutatorsStack, and Legacy HC re-asserts its transaction when SurfaceView relayouts.
    FrameLayout alphaRoot = new FrameLayout(activity);
    alphaRoot.setAlpha(0.5f);
    alphaRoot.layout(0, 0, 100, 100);
    SurfaceView clippedSv = new SurfaceView(activity);
    clippedSv.setAlpha(0.5f);
    clippedSv.layout(0, 0, 100, 100);
    clippedSv.setClipBounds(new Rect(10, 15, 50, 55));
    alphaRoot.addView(clippedSv);
    FlutterMutatorsStack s7Stack = new FlutterMutatorsStack();
    s7Stack.pushOpacity(0.8f);
    s7Stack.pushClipRect(0, 0, 40, 60);
    Rect s7Crop =
        PlatformViewsController2.computeLocalSurfaceViewCrop(
            clippedSv, alphaRoot, 0, 0, 100, 100, s7Stack);
    assertEquals(new Rect(10, 15, 40, 55), s7Crop);
    float s7Alpha =
        PlatformViewsController2.computeEffectiveSurfaceViewAlpha(
            clippedSv, alphaRoot, s7Crop, s7Stack);
    assertEquals(0.2f, s7Alpha, 1e-5f);

    // Verify Legacy HC re-asserts SurfaceControl alpha/crop on SurfaceView relayout.
    SurfaceControl validSc = new SurfaceControl.Builder().setName("test-legacy-sc").build();
    SurfaceView relayoutSv =
        new SurfaceView(activity) {
          @Override
          public SurfaceControl getSurfaceControl() {
            return validSc;
          }
        };
    relayoutSv.setAlpha(0.5f);
    relayoutSv.layout(0, 0, 80, 80);
    hcHarness.registerFactory(
        "relayout-sv-hc",
        new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
          @NonNull
          @Override
          public PlatformView create(Context ctx, int id, @Nullable Object args) {
            return new SimplePlatformView(relayoutSv, false);
          }
        });
    hcHarness.delegator.createForTextureLayer(
        PlatformViewCreationRequest.createTLHCWithFallbackRequest(
            93, "relayout-sv-hc", 0, 0, 80, 80, View.LAYOUT_DIRECTION_LTR, true, false, null));
    FlutterMutatorsStack hcStack = new FlutterMutatorsStack();
    hcStack.pushOpacity(0.8f);
    hcHarness.pvc1.onBeginFrame();
    hcHarness.pvc1.onDisplayPlatformView(93, 0, 0, 80, 80, 80, 80, hcStack);
    assertEquals(1, (hcHarness.legacyAppliedAlphas).size());
    assertEquals(0.4f, hcHarness.legacyAppliedAlphas.get(0), 1e-5f);
    // Identical frame without relayout is deduplicated:
    hcHarness.pvc1.onDisplayPlatformView(93, 0, 0, 80, 80, 80, 80, hcStack);
    assertEquals(1, (hcHarness.legacyAppliedAlphas).size());
    // Relayout of SurfaceView invalidates dedup and immediately re-asserts combined alpha/crop!
    relayoutSv.measure(
        View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY));
    relayoutSv.layout(0, 0, 80, 80);
    assertEquals(2, (hcHarness.legacyAppliedAlphas).size());
    assertEquals(0.4f, hcHarness.legacyAppliedAlphas.get(1), 1e-5f);
    validSc.release();

    // 6. Offscreen Surface.HwuiContext isolation + subtree invalidation on hot-swap:
    // Once a descendant SurfaceView is attached, PlatformViewWrapper.draw() must NOT lock/draw into
    // the offscreen RenderTarget surface (preserving the pre-SV texture and avoiding binding
    // SurfaceView's PositionUpdateListener to Surface.HwuiContext), and switching/releasing must
    // recursively invalidate descendants even when wrapper dimensions are unchanged.
    AtomicInteger getSurfaceCalls = new AtomicInteger(0);
    AtomicInteger releaseCalls = new AtomicInteger(0);
    PlatformViewRenderTarget countingRenderTarget =
        new PlatformViewRenderTarget() {
          private int w = 100;
          private int h = 100;

          @Override
          public void resize(int width, int height) {
            this.w = width;
            this.h = height;
          }

          @Override
          public int getWidth() {
            return w;
          }

          @Override
          public int getHeight() {
            return h;
          }

          @Override
          public long getId() {
            return 777L;
          }

          @Override
          public void release() {
            releaseCalls.incrementAndGet();
          }

          @Override
          public boolean isReleased() {
            return releaseCalls.get() > 0;
          }

          @Nullable
          @Override
          public Surface getSurface() {
            getSurfaceCalls.incrementAndGet();
            return null;
          }

          @Override
          public void scheduleFrame() {}
        };
    FlutterRenderer.debugDisableSurfaceClear = true;
    try {
      PlatformViewWrapper isoWrapper = new PlatformViewWrapper(activity, countingRenderTarget);
      AtomicInteger childInvalidates = new AtomicInteger(0);
      FrameLayout childBox =
          new FrameLayout(activity) {
            @Override
            public void invalidate() {
              super.invalidate();
              childInvalidates.incrementAndGet();
            }
          };
      isoWrapper.addView(childBox, new FrameLayout.LayoutParams(100, 100));
      liveFlutterView.addView(isoWrapper, new FrameLayout.LayoutParams(100, 100));
      shadowOf(Looper.getMainLooper()).idle();
      assertTrue(isoWrapper.isAttachedToWindow());
      AtomicBoolean upgradeDetected = new AtomicBoolean(false);
      isoWrapper.setOnSurfaceViewDetectedListener(() -> upgradeDetected.set(true));

      // Before SurfaceView attachment, TLHC draw queries the offscreen render target surface:
      getSurfaceCalls.set(0);
      isoWrapper.draw(new Canvas());
      assertEquals(1, getSurfaceCalls.get());

      // Attach a SurfaceView inside childBox with unchanged 100x100 wrapper dimensions:
      getSurfaceCalls.set(0);
      SurfaceView hotSv = new SurfaceView(activity);
      childBox.addView(hotSv, new FrameLayout.LayoutParams(100, 100));

      // Both before and after the upgrade runnable fires (while captureToTextureMode is still
      // true), PlatformViewWrapper.draw() freezes the pre-SV texture without querying/locking the
      // offscreen render target surface:
      isoWrapper.draw(new Canvas());
      assertEquals(0, getSurfaceCalls.get());
      shadowOf(Looper.getMainLooper()).idle();
      assertTrue(upgradeDetected.get());
      assertEquals(0, getSurfaceCalls.get());
      isoWrapper.draw(new Canvas());
      assertEquals(0, getSurfaceCalls.get());
      assertFalse(hotSv.isLayoutRequested());

      // Switching to native composition recursively invalidates childBox and requests layout on
      // hotSv even though isoWrapper stays 100x100:
      childInvalidates.set(0);
      isoWrapper.switchToNativeViewComposition(1.0f);
      assertTrue((childInvalidates.get()) > 0);
      assertTrue(hotSv.isLayoutRequested());

      // Releasing the offscreen render target while attached re-invalidates the subtree cleanly:
      childInvalidates.set(0);
      isoWrapper.release();
      assertEquals(1, releaseCalls.get());
      assertTrue((childInvalidates.get()) > 0);
      liveFlutterView.removeView(isoWrapper);
    } finally {
      FlutterRenderer.debugDisableSurfaceClear = false;
    }
    shadowOf(Looper.getMainLooper()).idle();
  }
}
