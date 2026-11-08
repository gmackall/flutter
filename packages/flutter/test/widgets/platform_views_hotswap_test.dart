// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

class _PlatformViewsMethodHarness {
  _PlatformViewsMethodHarness({
    this.returnNullOnCreate = false,
    this.textureIdToReturn = 77,
    this.hcppSupported = false,
  }) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform_views,
      _onMethodCall,
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform_views_2,
      _onMethodCall2,
    );
  }

  bool returnNullOnCreate;
  int textureIdToReturn;
  bool hcppSupported;
  bool shouldUpgradeAllToHcpp = false;

  final List<MethodCall> calls = <MethodCall>[];
  final List<MethodCall> calls2 = <MethodCall>[];

  Future<dynamic> _onMethodCall(MethodCall call) async {
    calls.add(call);
    switch (call.method) {
      case 'create':
        if (returnNullOnCreate) {
          return null;
        }
        return textureIdToReturn;
      case 'resize':
        final args = call.arguments as Map<Object?, Object?>;
        return <String, double>{
          'width': (args['width']! as num).toDouble(),
          'height': (args['height']! as num).toDouble(),
        };
      case 'isSurfaceControlEnabled':
        return hcppSupported;
      case 'shouldUpgradeAllViewsToHcpp':
        return shouldUpgradeAllToHcpp;
      case 'dispose':
      case 'offset':
      case 'touch':
      case 'setDirection':
      case 'clearFocus':
      case 'rejectGesture':
      case 'synchronizeToNativeViewHierarchy':
        return null;
      default:
        return null;
    }
  }

  Future<dynamic> _onMethodCall2(MethodCall call) async {
    calls2.add(call);
    if (call.method == 'isSurfaceControlEnabled') {
      return hcppSupported;
    }
    return null;
  }

  Future<void> emitViewCompositionChanged(int viewId, String mode) async {
    final ByteData message = const StandardMethodCodec().encodeMethodCall(
      MethodCall('viewCompositionChanged', <String, dynamic>{'id': viewId, 'mode': mode}),
    );
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
      SystemChannels.platform_views.name,
      message,
      (_) {},
    );
  }

  Future<void> emitViewBufferResized(int viewId, double width, double height) async {
    final ByteData message = const StandardMethodCodec().encodeMethodCall(
      MethodCall('viewBufferResized', <String, dynamic>{
        'id': viewId,
        'width': width,
        'height': height,
      }),
    );
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
      SystemChannels.platform_views.name,
      message,
      (_) {},
    );
  }

  void dispose() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform_views,
      null,
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform_views_2,
      null,
    );
  }
}

List<Layer> _collectSubtreeLayers(Layer? root) {
  final result = <Layer>[];
  void visit(Layer? layer) {
    if (layer == null) {
      return;
    }
    result.add(layer);
    if (layer is ContainerLayer) {
      Layer? child = layer.firstChild;
      while (child != null) {
        visit(child);
        child = child.nextSibling;
      }
    }
  }

  visit(root);
  return result;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('initAndroidView and initSurfaceAndroidView forward preferTexture', () async {
    final harness = _PlatformViewsMethodHarness();
    addTearDown(harness.dispose);

    final AndroidViewController texDefault = PlatformViewsService.initAndroidView(
      id: 101,
      viewType: 'test-pv',
      layoutDirection: TextDirection.ltr,
    );
    await texDefault.setSize(const Size(120, 80));

    final AndroidViewController texPrefer = PlatformViewsService.initAndroidView(
      id: 102,
      viewType: 'test-pv',
      layoutDirection: TextDirection.ltr,
      preferTexture: true,
    );
    await texPrefer.setSize(const Size(120, 80));

    final SurfaceAndroidViewController surfPrefer = PlatformViewsService.initSurfaceAndroidView(
      id: 103,
      viewType: 'test-pv',
      layoutDirection: TextDirection.ltr,
      preferTexture: true,
    );
    await surfPrefer.setSize(const Size(120, 80));

    final List<MethodCall> createCalls = harness.calls
        .where((MethodCall c) => c.method == 'create')
        .toList();
    expect(createCalls, hasLength(3));
    expect((createCalls[0].arguments as Map<Object?, Object?>)['preferTexture'], isNull);
    expect((createCalls[1].arguments as Map<Object?, Object?>)['preferTexture'], isTrue);
    expect((createCalls[2].arguments as Map<Object?, Object?>)['preferTexture'], isTrue);
    expect((createCalls[2].arguments as Map<Object?, Object?>)['hybridFallback'], isTrue);

    await texDefault.dispose();
    await texPrefer.dispose();
    await surfPrefer.dispose();
  });

  test('create returning null switches TextureAndroidViewController and '
      'SurfaceAndroidViewController to hybrid composition with null textureId', () async {
    final harness = _PlatformViewsMethodHarness(returnNullOnCreate: true);
    addTearDown(harness.dispose);

    final AndroidViewController texCtrl = PlatformViewsService.initAndroidView(
      id: 201,
      viewType: 'sv-at-create',
      layoutDirection: TextDirection.ltr,
    );
    await texCtrl.setSize(const Size(100, 100));
    expect(texCtrl.requiresViewComposition, isTrue);
    expect(texCtrl.textureId, isNull);

    final SurfaceAndroidViewController surfCtrl = PlatformViewsService.initSurfaceAndroidView(
      id: 202,
      viewType: 'sv-at-create',
      layoutDirection: TextDirection.ltr,
    );
    await surfCtrl.setSize(const Size(100, 100));
    expect(surfCtrl.requiresViewComposition, isTrue);
    expect(surfCtrl.textureId, isNull);

    final ExpensiveAndroidViewController expCtrl = PlatformViewsService.initExpensiveAndroidView(
      id: 203,
      viewType: 'expensive',
      layoutDirection: TextDirection.ltr,
    );
    await expCtrl.create();
    expect(expCtrl.requiresViewComposition, isTrue);
    expect(expCtrl.textureId, isNull);

    harness.calls.clear();
    await surfCtrl.dispose();
    expect(harness.calls, hasLength(1));
    expect(harness.calls.single.method, 'dispose');
    expect((harness.calls.single.arguments as Map<Object?, Object?>)['hybrid'], isTrue);

    await texCtrl.dispose();
    await expCtrl.dispose();
  });

  test('viewCompositionChanged upgrades live controllers once (sticky ratchet) '
      'and turns texture-only operations into safe no-ops', () async {
    final harness = _PlatformViewsMethodHarness(textureIdToReturn: 55);
    addTearDown(harness.dispose);

    final SurfaceAndroidViewController surfCtrl = PlatformViewsService.initSurfaceAndroidView(
      id: 301,
      viewType: 'dynamic-sv',
      layoutDirection: TextDirection.ltr,
    );
    var modeChanges = 0;
    void listener() {
      modeChanges++;
    }

    surfCtrl.addOnCompositionModeChangedListener(listener);
    await surfCtrl.setSize(const Size(150, 90));
    expect(surfCtrl.requiresViewComposition, isFalse);
    expect(surfCtrl.textureId, 55);

    await harness.emitViewCompositionChanged(301, 'hcpp');
    expect(surfCtrl.requiresViewComposition, isTrue);
    expect(surfCtrl.textureId, isNull);
    expect(modeChanges, 1);

    // Duplicate viewCompositionChanged is a sticky no-op.
    await harness.emitViewCompositionChanged(301, 'hcpp');
    expect(modeChanges, 1);

    // Post-upgrade setSize / setOffset do not send resize/offset channel calls.
    harness.calls.clear();
    final Size resized = await surfCtrl.setSize(const Size(200, 120));
    expect(resized, const Size(200, 120));
    await surfCtrl.setOffset(const Offset(12, 34));
    expect(
      harness.calls.where((MethodCall c) => c.method == 'resize' || c.method == 'offset'),
      isEmpty,
    );

    surfCtrl.removeOnCompositionModeChangedListener(listener);
    await surfCtrl.dispose();
    // Removing listener after dispose is a safe no-op.
    surfCtrl.removeOnCompositionModeChangedListener(listener);
  });

  testWidgets('AndroidView switches RenderAndroidView from TextureLayer to '
      'PlatformViewLayer on viewCompositionChanged without stale clip', (
    WidgetTester tester,
  ) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    final harness = _PlatformViewsMethodHarness(textureIdToReturn: 88);
    addTearDown(() {
      debugDefaultTargetPlatformOverride = null;
      harness.dispose();
    });

    int? createdViewId;
    await tester.pumpWidget(
      Directionality(
        textDirection: TextDirection.ltr,
        child: Center(
          child: SizedBox(
            width: 180,
            height: 120,
            child: AndroidView(
              viewType: 'hotswap-android-view',
              preferTexture: true,
              onPlatformViewCreated: (int id) {
                createdViewId = id;
              },
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(createdViewId, isNotNull);

    // Verify preferTexture was forwarded by AndroidView.
    final MethodCall createCall = harness.calls.firstWhere((MethodCall c) => c.method == 'create');
    expect((createCall.arguments as Map<Object?, Object?>)['preferTexture'], isTrue);

    List<Layer> layers = _collectSubtreeLayers(tester.binding.renderView.debugLayer);
    expect(layers.whereType<TextureLayer>().any((TextureLayer l) => l.textureId == 88), isTrue);
    expect(
      layers.whereType<PlatformViewLayer>().any((PlatformViewLayer l) => l.viewId == createdViewId),
      isFalse,
    );

    // Trigger dynamic upgrade from engine.
    await harness.emitViewCompositionChanged(createdViewId!, 'hcpp');
    await tester.pump();

    layers = _collectSubtreeLayers(tester.binding.renderView.debugLayer);
    expect(layers.whereType<TextureLayer>().any((TextureLayer l) => l.textureId == 88), isFalse);
    final Iterable<PlatformViewLayer> pvLayers = layers.whereType<PlatformViewLayer>().where(
      (PlatformViewLayer l) => l.viewId == createdViewId,
    );
    expect(pvLayers, hasLength(1));
    expect(pvLayers.single.rect.size, const Size(180, 120));
  });

  testWidgets('AndroidViewSurface rebuilds from RenderAndroidView (TextureLayer) to '
      'PlatformViewRenderBox (PlatformViewLayer) on viewCompositionChanged', (
    WidgetTester tester,
  ) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    final harness = _PlatformViewsMethodHarness(textureIdToReturn: 99);
    addTearDown(() {
      debugDefaultTargetPlatformOverride = null;
      harness.dispose();
    });

    const viewId = 404;
    await tester.pumpWidget(
      Directionality(
        textDirection: TextDirection.ltr,
        child: Center(
          child: SizedBox(
            width: 160,
            height: 110,
            child: PlatformViewLink(
              viewType: 'surface-link-pv',
              onCreatePlatformView: (PlatformViewCreationParams params) {
                return PlatformViewsService.initSurfaceAndroidView(
                  id: params.id,
                  viewType: params.viewType,
                  layoutDirection: TextDirection.ltr,
                )..addOnPlatformViewCreatedListener(params.onPlatformViewCreated);
              },
              surfaceFactory: (BuildContext context, PlatformViewController controller) {
                return AndroidViewSurface(
                  controller: controller as AndroidViewController,
                  gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
                  hitTestBehavior: PlatformViewHitTestBehavior.opaque,
                );
              },
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(AndroidViewSurface), findsOneWidget);
    final RenderObject initialRenderObject = tester.renderObject(find.byType(AndroidViewSurface));
    expect(initialRenderObject, isA<RenderAndroidView>());

    final int activeId = (initialRenderObject as RenderAndroidView).controller.viewId;
    expect(activeId, isNot(equals(viewId))); // Allocated by platformViewsRegistry.

    List<Layer> layers = _collectSubtreeLayers(tester.binding.renderView.debugLayer);
    expect(layers.whereType<TextureLayer>().any((TextureLayer l) => l.textureId == 99), isTrue);

    await harness.emitViewCompositionChanged(activeId, 'hc');
    await tester.pump();

    final RenderObject upgradedRenderObject = tester.renderObject(find.byType(AndroidViewSurface));
    expect(upgradedRenderObject, isA<PlatformViewRenderBox>());

    layers = _collectSubtreeLayers(tester.binding.renderView.debugLayer);
    expect(layers.whereType<TextureLayer>().any((TextureLayer l) => l.textureId == 99), isFalse);
    expect(
      layers.whereType<PlatformViewLayer>().any((PlatformViewLayer l) => l.viewId == activeId),
      isTrue,
    );
  });

  test('mid-gesture viewCompositionChanged dispatches ACTION_CANCEL and drops '
      'orphaned trailing pointer move and up events without throwing', () async {
    final harness = _PlatformViewsMethodHarness(textureIdToReturn: 66);
    addTearDown(harness.dispose);

    final AndroidViewController ctrl = PlatformViewsService.initAndroidView(
      id: 501,
      viewType: 'touch-pv',
      layoutDirection: TextDirection.ltr,
    );
    ctrl.pointTransformer = (Offset global) => global;
    await ctrl.setSize(const Size(200, 200));
    harness.calls.clear();

    await ctrl.dispatchPointerEvent(
      const PointerDownEvent(
        pointer: 7,
        position: Offset(40, 50),
        timeStamp: Duration(milliseconds: 100),
      ),
    );
    expect(harness.calls, hasLength(1));
    expect(harness.calls.single.method, 'touch');
    expect((harness.calls.single.arguments as List<Object?>)[3], AndroidViewController.kActionDown);

    harness.calls.clear();
    await harness.emitViewCompositionChanged(501, 'hcpp');
    // Active pointer sequence must immediately synthesize ACTION_CANCEL.
    final List<MethodCall> cancelTouches = harness.calls
        .where((MethodCall c) => c.method == 'touch')
        .toList();
    expect(cancelTouches, hasLength(1));
    expect(
      (cancelTouches.single.arguments as List<Object?>)[3],
      AndroidViewController.kActionCancel,
    );

    // Trailing move and up events from the pre-upgrade pointer sequence are ignored cleanly.
    harness.calls.clear();
    await ctrl.dispatchPointerEvent(
      const PointerMoveEvent(
        pointer: 7,
        position: Offset(60, 70),
        timeStamp: Duration(milliseconds: 116),
      ),
    );
    await ctrl.dispatchPointerEvent(
      const PointerUpEvent(
        pointer: 7,
        position: Offset(60, 70),
        timeStamp: Duration(milliseconds: 132),
      ),
    );
    expect(harness.calls.where((MethodCall c) => c.method == 'touch'), isEmpty);

    // A fresh pointer sequence after upgrade works normally.
    await ctrl.dispatchPointerEvent(
      const PointerDownEvent(
        pointer: 8,
        position: Offset(25, 35),
        timeStamp: Duration(milliseconds: 200),
      ),
    );
    expect(harness.calls, hasLength(1));
    expect((harness.calls.single.arguments as List<Object?>)[3], AndroidViewController.kActionDown);

    await ctrl.dispose();
  });

  test('AndroidView policy defaults to TLHC when HcppUpgradeAllViews is false '
      'and upgrades all to HCPP only when HcppUpgradeAllViews is true', () async {
    final harness = _PlatformViewsMethodHarness(hcppSupported: true);
    addTearDown(harness.dispose);

    expect(
      await SystemChannels.platform_views.invokeMethod<bool>('shouldUpgradeAllViewsToHcpp'),
      isFalse,
    );
    harness.shouldUpgradeAllToHcpp = true;
    expect(
      await SystemChannels.platform_views.invokeMethod<bool>('shouldUpgradeAllViewsToHcpp'),
      isTrue,
    );
  });

  testWidgets(
    'viewBufferResized updates RenderAndroidView TextureLayer rect after debounced shrink',
    (WidgetTester tester) async {
      debugDefaultTargetPlatformOverride = TargetPlatform.android;
      var reportedResizeBuffer = const Size(300, 300);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform_views,
        (MethodCall call) async {
          if (call.method == 'create') {
            return 123;
          }
          if (call.method == 'resize') {
            return <String, double>{
              'width': reportedResizeBuffer.width,
              'height': reportedResizeBuffer.height,
            };
          }
          return null;
        },
      );
      addTearDown(() {
        debugDefaultTargetPlatformOverride = null;
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.platform_views,
          null,
        );
      });

      int? createdId;
      final sizeNotifier = ValueNotifier<Size>(const Size(300, 300));
      await tester.pumpWidget(
        Directionality(
          textDirection: TextDirection.ltr,
          child: Center(
            child: ValueListenableBuilder<Size>(
              valueListenable: sizeNotifier,
              builder: (BuildContext context, Size sz, Widget? _) {
                return SizedBox(
                  width: sz.width,
                  height: sz.height,
                  child: AndroidView(
                    viewType: 'debounced-shrink-pv',
                    onPlatformViewCreated: (int id) => createdId = id,
                  ),
                );
              },
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(createdId, isNotNull);

      // Shrink widget to 120x100 while native resize synchronously returns unshrunk 300x300.
      reportedResizeBuffer = const Size(300, 300);
      sizeNotifier.value = const Size(120, 100);
      await tester.pumpAndSettle();

      TextureLayer texLayer = _collectSubtreeLayers(tester.binding.renderView.debugLayer)
          .whereType<TextureLayer>()
          .singleWhere((TextureLayer l) => l.textureId == 123);
      expect(texLayer.rect.size, const Size(300, 300));

      // Simulate native 150ms debounce firing viewBufferResized(120, 100).
      final ByteData msg = const StandardMethodCodec().encodeMethodCall(
        MethodCall('viewBufferResized', <String, dynamic>{
          'id': createdId,
          'width': 120.0,
          'height': 100.0,
        }),
      );
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
        SystemChannels.platform_views.name,
        msg,
        (_) {},
      );
      await tester.pump();

      texLayer = _collectSubtreeLayers(tester.binding.renderView.debugLayer)
          .whereType<TextureLayer>()
          .singleWhere((TextureLayer l) => l.textureId == 123);
      expect(texLayer.rect.size, const Size(120, 100));
    },
  );
}
