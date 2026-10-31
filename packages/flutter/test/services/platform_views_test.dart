// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

import 'fake_platform_views.dart';

void main() {
  final TestWidgetsFlutterBinding binding = TestWidgetsFlutterBinding.ensureInitialized();

  group('Android', () {
    late FakeAndroidPlatformViewsController viewsController;
    setUp(() {
      viewsController = FakeAndroidPlatformViewsController();
    });

    test('create Android view of unregistered type', () async {
      await expectLater(
        () => PlatformViewsService.initAndroidView(
          id: 0,
          viewType: 'web',
          layoutDirection: TextDirection.ltr,
        ).create(size: const Size(100.0, 100.0)),
        throwsA(isA<PlatformException>()),
      );
      viewsController.registerViewType('web');

      try {
        await PlatformViewsService.initSurfaceAndroidView(
          id: 0,
          viewType: 'web',
          layoutDirection: TextDirection.ltr,
        ).create(size: const Size(1.0, 1.0));
      } catch (e) {
        expect(false, isTrue, reason: 'did not expected any exception, but instead got `$e`');
      }

      try {
        await PlatformViewsService.initAndroidView(
          id: 1,
          viewType: 'web',
          layoutDirection: TextDirection.ltr,
        ).create(size: const Size(1.0, 1.0));
      } catch (e) {
        expect(false, isTrue, reason: 'did not expected any exception, but instead got `$e`');
      }
    });

    test('create VD-fallback Android views', () async {
      viewsController.registerViewType('webview');
      await PlatformViewsService.initAndroidView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      ).create(size: const Size(100.0, 100.0));
      await PlatformViewsService.initAndroidView(
        id: 1,
        viewType: 'webview',
        layoutDirection: TextDirection.rtl,
      ).create(size: const Size(200.0, 300.0));
      expect(
        viewsController.views,
        unorderedEquals(<FakeAndroidPlatformView>[
          const FakeAndroidPlatformView(
            0,
            'webview',
            Size(100.0, 100.0),
            AndroidViewController.kAndroidLayoutDirectionLtr,
          ),
          const FakeAndroidPlatformView(
            1,
            'webview',
            Size(200.0, 300.0),
            AndroidViewController.kAndroidLayoutDirectionRtl,
          ),
        ]),
      );
    });

    test('create HC-fallback Android views', () async {
      viewsController.registerViewType('webview');
      await PlatformViewsService.initSurfaceAndroidView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      ).create(size: const Size(100.0, 100.0));
      await PlatformViewsService.initSurfaceAndroidView(
        id: 1,
        viewType: 'webview',
        layoutDirection: TextDirection.rtl,
      ).create(size: const Size(200.0, 300.0));
      expect(
        viewsController.views,
        unorderedEquals(<FakeAndroidPlatformView>[
          const FakeAndroidPlatformView(
            0,
            'webview',
            Size(100.0, 100.0),
            AndroidViewController.kAndroidLayoutDirectionLtr,
            hybridFallback: true,
          ),
          const FakeAndroidPlatformView(
            1,
            'webview',
            Size(200.0, 300.0),
            AndroidViewController.kAndroidLayoutDirectionRtl,
            hybridFallback: true,
          ),
        ]),
      );
    });

    test('create HC-only Android views', () async {
      viewsController.registerViewType('webview');
      await PlatformViewsService.initExpensiveAndroidView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      ).create(size: const Size(100.0, 100.0));
      await PlatformViewsService.initExpensiveAndroidView(
        id: 1,
        viewType: 'webview',
        layoutDirection: TextDirection.rtl,
      ).create(size: const Size(200.0, 300.0));
      expect(
        viewsController.views,
        unorderedEquals(<FakeAndroidPlatformView>[
          const FakeAndroidPlatformView(
            0,
            'webview',
            null,
            AndroidViewController.kAndroidLayoutDirectionLtr,
            hybrid: true,
          ),
          const FakeAndroidPlatformView(
            1,
            'webview',
            null,
            AndroidViewController.kAndroidLayoutDirectionRtl,
            hybrid: true,
          ),
        ]),
      );
    });

    test('default view does not use view composition by default', () async {
      viewsController.registerViewType('webview');
      final AndroidViewController controller = PlatformViewsService.initAndroidView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      await controller.create(size: const Size(100.0, 100.0));
      expect(controller.requiresViewComposition, false);
    });

    test('default view does not use view composition in fallback mode', () async {
      viewsController.registerViewType('webview');
      viewsController.allowTextureLayerMode = false;
      final AndroidViewController controller = PlatformViewsService.initAndroidView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      await controller.create(size: const Size(100.0, 100.0));
      viewsController.allowTextureLayerMode = true;
      expect(controller.requiresViewComposition, false);
    });

    test('surface view does not use view composition by default', () async {
      viewsController.registerViewType('webview');
      final AndroidViewController controller = PlatformViewsService.initSurfaceAndroidView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      await controller.create(size: const Size(100.0, 100.0));
      expect(controller.requiresViewComposition, false);
    });

    test('surface view does uses view composition in fallback mode', () async {
      viewsController.registerViewType('webview');
      viewsController.allowTextureLayerMode = false;
      final AndroidViewController controller = PlatformViewsService.initSurfaceAndroidView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      await controller.create(size: const Size(100.0, 100.0));
      viewsController.allowTextureLayerMode = true;
      expect(controller.requiresViewComposition, true);
    });

    test('expensive view uses view composition', () async {
      viewsController.registerViewType('webview');
      final AndroidViewController controller = PlatformViewsService.initExpensiveAndroidView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      await controller.create(size: const Size(100.0, 100.0));
      expect(controller.requiresViewComposition, true);
    });

    test('reuse Android view id', () async {
      viewsController.registerViewType('webview');
      final AndroidViewController controller = PlatformViewsService.initAndroidView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      await controller.create(size: const Size(100.0, 100.0));
      await expectLater(() {
        final AndroidViewController controller = PlatformViewsService.initAndroidView(
          id: 0,
          viewType: 'web',
          layoutDirection: TextDirection.ltr,
        );
        return controller.create(size: const Size(100.0, 100.0));
      }, throwsA(isA<PlatformException>()));
    });

    test('dispose Android view', () async {
      viewsController.registerViewType('webview');
      await PlatformViewsService.initAndroidView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      ).create(size: const Size(100.0, 100.0));

      final AndroidViewController viewController = PlatformViewsService.initAndroidView(
        id: 1,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      await viewController.create(size: const Size(200.0, 300.0));
      await viewController.dispose();

      final AndroidViewController surfaceViewController =
          PlatformViewsService.initSurfaceAndroidView(
            id: 1,
            viewType: 'webview',
            layoutDirection: TextDirection.ltr,
          );
      await surfaceViewController.create(size: const Size(200.0, 300.0));
      await surfaceViewController.dispose();

      expect(
        viewsController.views,
        unorderedEquals(<FakeAndroidPlatformView>[
          const FakeAndroidPlatformView(
            0,
            'webview',
            Size(100.0, 100.0),
            AndroidViewController.kAndroidLayoutDirectionLtr,
          ),
        ]),
      );
    });

    test('dispose Android view twice', () async {
      viewsController.registerViewType('webview');
      final AndroidViewController viewController = PlatformViewsService.initAndroidView(
        id: 1,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      await viewController.create(size: const Size(200.0, 300.0));
      await viewController.dispose();
      await viewController.dispose();
    });

    test('dispose clears focusCallbacks', () async {
      var didFocus = false;
      viewsController.registerViewType('webview');
      final AndroidViewController viewController = PlatformViewsService.initAndroidView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
        onFocus: () {
          didFocus = true;
        },
      );
      await viewController.create(size: const Size(100.0, 100.0));
      await viewController.dispose();
      final ByteData message = SystemChannels.platform_views.codec.encodeMethodCall(
        const MethodCall('viewFocused', 0),
      );
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        SystemChannels.platform_views.name,
        message,
        (_) {},
      );
      expect(didFocus, isFalse);
    });

    test('resize Android view', () async {
      viewsController.registerViewType('webview');
      await PlatformViewsService.initAndroidView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      ).create(size: const Size(100.0, 100.0));

      final AndroidViewController androidView = PlatformViewsService.initAndroidView(
        id: 1,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      await androidView.create(size: const Size(200.0, 300.0));
      await androidView.setSize(const Size(500.0, 500.0));

      expect(
        viewsController.views,
        unorderedEquals(<FakeAndroidPlatformView>[
          const FakeAndroidPlatformView(
            0,
            'webview',
            Size(100.0, 100.0),
            AndroidViewController.kAndroidLayoutDirectionLtr,
          ),
          const FakeAndroidPlatformView(
            1,
            'webview',
            Size(500.0, 500.0),
            AndroidViewController.kAndroidLayoutDirectionLtr,
          ),
        ]),
      );
    });

    test('OnPlatformViewCreated callback', () async {
      viewsController.registerViewType('webview');
      final createdViews = <int>[];
      void callback(int id) {
        createdViews.add(id);
      }

      final AndroidViewController controller1 = PlatformViewsService.initAndroidView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      )..addOnPlatformViewCreatedListener(callback);
      expect(createdViews, isEmpty);

      await controller1.create(size: const Size(100.0, 100.0));
      expect(createdViews, orderedEquals(<int>[0]));

      final AndroidViewController controller2 = PlatformViewsService.initAndroidView(
        id: 5,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      )..addOnPlatformViewCreatedListener(callback);
      expect(createdViews, orderedEquals(<int>[0]));

      await controller2.create(size: const Size(100.0, 200.0));
      expect(createdViews, orderedEquals(<int>[0, 5]));

      final AndroidViewController controller3 = PlatformViewsService.initAndroidView(
        id: 10,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      )..addOnPlatformViewCreatedListener(callback);
      expect(createdViews, orderedEquals(<int>[0, 5]));

      await Future.wait(<Future<void>>[
        controller3.create(size: const Size(100.0, 200.0)),
        controller3.dispose(),
      ]);

      expect(createdViews, orderedEquals(<int>[0, 5]));
    });

    test("change Android view's directionality before creation", () async {
      viewsController.registerViewType('webview');
      final AndroidViewController viewController = PlatformViewsService.initAndroidView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.rtl,
      );
      await viewController.setLayoutDirection(TextDirection.ltr);
      await viewController.create(size: const Size(100.0, 100.0));
      expect(
        viewsController.views,
        unorderedEquals(<FakeAndroidPlatformView>[
          const FakeAndroidPlatformView(
            0,
            'webview',
            Size(100.0, 100.0),
            AndroidViewController.kAndroidLayoutDirectionLtr,
          ),
        ]),
      );
    });

    test("change Android view's directionality after creation", () async {
      viewsController.registerViewType('webview');
      final AndroidViewController viewController = PlatformViewsService.initAndroidView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      await viewController.setLayoutDirection(TextDirection.rtl);
      await viewController.create(size: const Size(100.0, 100.0));
      expect(
        viewsController.views,
        unorderedEquals(<FakeAndroidPlatformView>[
          const FakeAndroidPlatformView(
            0,
            'webview',
            Size(100.0, 100.0),
            AndroidViewController.kAndroidLayoutDirectionRtl,
          ),
        ]),
      );
    });

    test("set Android view's offset if view is created", () async {
      viewsController.registerViewType('webview');
      final AndroidViewController viewController = PlatformViewsService.initAndroidView(
        id: 7,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      await viewController.create(size: const Size(100.0, 100.0));
      await viewController.setOffset(const Offset(10, 20));
      expect(viewsController.offsets, equals(<int, Offset>{7: const Offset(10, 20)}));
    });

    test("doesn't set Android view's offset if view isn't created", () async {
      viewsController.registerViewType('webview');
      final AndroidViewController viewController = PlatformViewsService.initAndroidView(
        id: 7,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      await viewController.setOffset(const Offset(10, 20));
      expect(viewsController.offsets, equals(<int, Offset>{}));
    });

    testWidgets('motion event converter does not duplicate move events', (
      WidgetTester tester,
    ) async {
      final log = <MethodCall>[];
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform_views,
        (MethodCall methodCall) async {
          log.add(methodCall);
          return null;
        },
      );

      final AndroidViewController viewController = PlatformViewsService.initSurfaceAndroidView(
        id: 7,
        viewType: 'web',
        layoutDirection: TextDirection.ltr,
      );
      viewController.pointTransformer = (Offset offset) => offset;
      viewController.liveTransformProvider = () => Matrix4.identity();

      const pointerCount = 10;
      for (var i = 0; i < pointerCount; i++) {
        final PointerEvent event = PointerDownEvent(
          timeStamp: const Duration(milliseconds: 1),
          pointer: i,
        );
        await viewController.dispatchPointerEvent(event);
      }

      // Pointer event platform data constant from _AndroidMotionEventConverter
      const kPointerDataFlagMultiple = 2;

      for (var i = 0; i < pointerCount; i++) {
        final PointerEvent event = PointerMoveEvent(
          timeStamp: const Duration(milliseconds: 2),
          pointer: i,
          platformData: kPointerDataFlagMultiple | (pointerCount << 8),
        );
        await viewController.dispatchPointerEvent(event);
      }

      // Indexes in the list returned by AndroidMotionEvent._asList
      const kAndroidMotionEventListIndexAction = 3;
      const kAndroidMotionEventListIndexPointerCount = 4;

      final List<MethodCall> moveCalls = log.where((MethodCall call) {
        final args = call.arguments as List<dynamic>;
        return call.method == 'touch' &&
            args[kAndroidMotionEventListIndexAction] == AndroidViewController.kActionMove;
      }).toList();

      // The _AndroidMotionEventConverter should yield one touch event containing all of the pointers.
      expect(moveCalls.length, equals(1));
      final moveArgs = moveCalls.single.arguments as List<dynamic>;
      expect(moveArgs[kAndroidMotionEventListIndexPointerCount], equals(pointerCount));
    });

    testWidgets(
      'directTouchDispatch suppresses single-pointer move echoes until the gesture ends',
      (WidgetTester tester) async {
        final log = <MethodCall>[];
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.platform_views,
          (MethodCall methodCall) async {
            log.add(methodCall);
            return null;
          },
        );
        final AndroidViewController viewController = PlatformViewsService.initSurfaceAndroidView(
          id: 9,
          viewType: 'web',
          layoutDirection: TextDirection.ltr,
        );
        viewController.pointTransformer = (Offset offset) => offset;
        viewController.liveTransformProvider = () => Matrix4.identity();
        const kIndexAction = 3;
        List<int> touchActions() => log
            .where((MethodCall call) => call.method == 'touch')
            .map<int>((MethodCall call) => (call.arguments as List<dynamic>)[kIndexAction] as int)
            .toList();

        Future<void> notifyDirectDispatch(int id, int downTime, {bool? active}) async {
          final args = <String, dynamic>{'id': id, 'downTime': downTime};
          if (active != null) {
            args['active'] = active;
          }
          final ByteData message = SystemChannels.platform_views.codec.encodeMethodCall(
            MethodCall('directTouchDispatch', args),
          );
          await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
            SystemChannels.platform_views.name,
            message,
            (_) {},
          );
        }

        // Gesture 1: the embedding takes over after the down event.
        const down1 = Duration(milliseconds: 1000);
        await viewController.dispatchPointerEvent(
          const PointerDownEvent(pointer: 1, position: Offset(10, 10), timeStamp: down1),
        );
        await viewController.dispatchPointerEvent(
          const PointerMoveEvent(
            pointer: 1,
            position: Offset(11, 11),
            timeStamp: Duration(milliseconds: 1004),
          ),
        );
        // A notification for another view or another gesture is ignored.
        await notifyDirectDispatch(8, 1000);
        await notifyDirectDispatch(9, 999);
        await viewController.dispatchPointerEvent(
          const PointerMoveEvent(
            pointer: 1,
            position: Offset(12, 12),
            timeStamp: Duration(milliseconds: 1008),
          ),
        );
        await notifyDirectDispatch(9, 1000);
        await viewController.dispatchPointerEvent(
          const PointerMoveEvent(
            pointer: 1,
            position: Offset(13, 13),
            timeStamp: Duration(milliseconds: 1012),
          ),
        );
        await viewController.dispatchPointerEvent(
          const PointerMoveEvent(
            pointer: 1,
            position: Offset(14, 14),
            timeStamp: Duration(milliseconds: 1016),
          ),
        );
        await viewController.dispatchPointerEvent(
          const PointerUpEvent(
            pointer: 1,
            position: Offset(14, 14),
            timeStamp: Duration(milliseconds: 1020),
          ),
        );
        expect(touchActions(), <int>[
          AndroidViewController.kActionDown,
          AndroidViewController.kActionMove,
          AndroidViewController.kActionMove,
          // Moves after the notification are delivered by the embedding and not echoed.
          AndroidViewController.kActionUp,
        ]);

        // A late directTouchDispatch(active: true) arriving after PointerUpEvent is ignored.
        await notifyDirectDispatch(9, 1000, active: true);

        // Gesture 2: a second pointer hands the gesture back to the framework, and the deferred
        // single-pointer move is flushed before ACTION_POINTER_DOWN is constructed.
        log.clear();
        const down2 = Duration(milliseconds: 2000);
        await viewController.dispatchPointerEvent(
          const PointerDownEvent(pointer: 2, position: Offset(10, 10), timeStamp: down2),
        );
        await notifyDirectDispatch(9, 2000);
        await viewController.dispatchPointerEvent(
          const PointerMoveEvent(
            pointer: 2,
            position: Offset(25, 35),
            timeStamp: Duration(milliseconds: 2004),
          ),
        );
        await viewController.dispatchPointerEvent(
          const PointerDownEvent(
            pointer: 3,
            position: Offset(50, 60),
            timeStamp: Duration(milliseconds: 2008),
          ),
        );
        // Verify the deferred move at (25, 35) was flushed into pointer 2's coordinates on the
        // ACTION_POINTER_DOWN event.
        const kIndexPointerCoords = 6;
        const kCoordsIndexX = 7;
        const kCoordsIndexY = 8;
        final pointerDownArgs = log.last.arguments as List<dynamic>;
        final pointerDownCoords = pointerDownArgs[kIndexPointerCoords] as List<dynamic>;
        expect((pointerDownCoords[0] as List<dynamic>)[kCoordsIndexX], 25.0);
        expect((pointerDownCoords[0] as List<dynamic>)[kCoordsIndexY], 35.0);

        const kPointerDataFlagMultiple = 2;
        const int platformData = kPointerDataFlagMultiple | (2 << 8);
        await viewController.dispatchPointerEvent(
          const PointerMoveEvent(
            pointer: 2,
            position: Offset(12, 12),
            timeStamp: Duration(milliseconds: 2012),
            platformData: platformData,
          ),
        );
        await viewController.dispatchPointerEvent(
          const PointerMoveEvent(
            pointer: 3,
            position: Offset(51, 51),
            timeStamp: Duration(milliseconds: 2012),
            platformData: platformData,
          ),
        );
        expect(touchActions(), <int>[
          AndroidViewController.kActionDown,
          // Single-pointer move during direct dispatch: not echoed.
          AndroidViewController.pointerAction(1, AndroidViewController.kActionPointerDown),
          // Multi-pointer moves are echoed again.
          AndroidViewController.kActionMove,
        ]);
        await viewController.dispatchPointerEvent(
          const PointerUpEvent(
            pointer: 3,
            position: Offset(51, 51),
            timeStamp: Duration(milliseconds: 2016),
          ),
        );
        await viewController.dispatchPointerEvent(
          const PointerUpEvent(
            pointer: 2,
            position: Offset(12, 12),
            timeStamp: Duration(milliseconds: 2020),
          ),
        );

        // Gesture 3: when the embedding exits direct dispatch mid-gesture (active: false, e.g. a
        // second finger was rejected by the PV in Dart or an in-flight echo mismatched transform),
        // single-pointer move echoes resume immediately.
        log.clear();
        const down3 = Duration(milliseconds: 2500);
        await viewController.dispatchPointerEvent(
          const PointerDownEvent(pointer: 4, position: Offset(10, 10), timeStamp: down3),
        );
        await notifyDirectDispatch(9, 2500, active: true);
        await viewController.dispatchPointerEvent(
          const PointerMoveEvent(
            pointer: 4,
            position: Offset(15, 15),
            timeStamp: Duration(milliseconds: 2504),
          ),
        );
        // Embedding deactivates direct dispatch for downTime 2500.
        await notifyDirectDispatch(9, 2500, active: false);
        await viewController.dispatchPointerEvent(
          const PointerMoveEvent(
            pointer: 4,
            position: Offset(20, 20),
            timeStamp: Duration(milliseconds: 2508),
          ),
        );
        await viewController.dispatchPointerEvent(
          const PointerUpEvent(
            pointer: 4,
            position: Offset(20, 20),
            timeStamp: Duration(milliseconds: 2512),
          ),
        );
        expect(touchActions(), <int>[
          AndroidViewController.kActionDown,
          // Move at 2504 was suppressed while active, move at 2508 is echoed after active: false.
          AndroidViewController.kActionMove,
          AndroidViewController.kActionUp,
        ]);

        // Gesture 4: when a non-translation transform (e.g. scale) appears on PointerMoveEvent
        // mid-gesture while direct dispatch is active, Dart exits direct dispatch, flushes any
        // deferred move, and resumes echoing moves via `touch`.
        log.clear();
        const down4 = Duration(milliseconds: 2800);
        await viewController.dispatchPointerEvent(
          const PointerDownEvent(pointer: 5, position: Offset(10, 10), timeStamp: down4),
        );
        await notifyDirectDispatch(9, 2800, active: true);
        // Translation-only transform keeps direct dispatch active (suppressed).
        await viewController.dispatchPointerEvent(
          const PointerMoveEvent(
            pointer: 5,
            position: Offset(12, 12),
            timeStamp: Duration(milliseconds: 2804),
          ).transformed(Matrix4.translationValues(5.0, 6.0, 0.0)),
        );
        expect(touchActions(), <int>[AndroidViewController.kActionDown]);

        // Non-translation transform (2x scale) on PointerMoveEvent exits direct dispatch and echoes
        // both that move and subsequent moves through `touch`.
        await viewController.dispatchPointerEvent(
          const PointerMoveEvent(
            pointer: 5,
            position: Offset(16, 18),
            timeStamp: Duration(milliseconds: 2808),
          ).transformed(Matrix4.diagonal3Values(2.0, 2.0, 1.0)),
        );
        await viewController.dispatchPointerEvent(
          const PointerMoveEvent(
            pointer: 5,
            position: Offset(20, 22),
            timeStamp: Duration(milliseconds: 2812),
          ),
        );
        await viewController.dispatchPointerEvent(
          const PointerUpEvent(
            pointer: 5,
            position: Offset(20, 22),
            timeStamp: Duration(milliseconds: 2816),
          ),
        );
        expect(touchActions(), <int>[
          AndroidViewController.kActionDown,
          // Move at 2804 was suppressed (translation-only); moves at 2808 (scaled) and 2812 are echoed.
          AndroidViewController.kActionMove,
          AndroidViewController.kActionMove,
          AndroidViewController.kActionUp,
        ]);

        // Gesture 5: after dispose the notification is ignored (no callback registered).
        await viewController.dispose();
        await notifyDirectDispatch(9, 3000);
      },
    );

    testWidgets(
      'directTouchDispatch conservatively refuses activation without liveTransformProvider and invalidates on translation shift',
      (WidgetTester tester) async {
        final log = <MethodCall>[];
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.platform_views,
          (MethodCall methodCall) async {
            log.add(methodCall);
            return null;
          },
        );
        final AndroidViewController bareController = PlatformViewsService.initSurfaceAndroidView(
          id: 91,
          viewType: 'web',
          layoutDirection: TextDirection.ltr,
        );
        bareController.pointTransformer = (Offset offset) => offset;
        // Intentionally do NOT install liveTransformProvider: direct touch must be conservatively
        // rejected and an explicit invalidateDirectTouch message must be sent.
        await bareController.dispatchPointerEvent(
          const PointerDownEvent(
            pointer: 1,
            position: Offset(10, 10),
            timeStamp: Duration(milliseconds: 5000),
          ),
        );
        final ByteData activateMsg = SystemChannels.platform_views.codec.encodeMethodCall(
          const MethodCall('directTouchDispatch', <String, dynamic>{
            'id': 91,
            'downTime': 5000,
            'active': true,
          }),
        );
        await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
          SystemChannels.platform_views.name,
          activateMsg,
          (_) {},
        );
        expect(bareController.isDirectTouchDispatchActive, isFalse);
        expect(
          log.any(
            (MethodCall call) =>
                call.method == 'invalidateDirectTouch' &&
                (call.arguments as Map<dynamic, dynamic>)['id'] == 91 &&
                (call.arguments as Map<dynamic, dynamic>)['downTime'] == 5000,
          ),
          isTrue,
        );
        await bareController.dispatchPointerEvent(
          const PointerMoveEvent(
            pointer: 1,
            position: Offset(15, 20),
            timeStamp: Duration(milliseconds: 5008),
          ),
        );
        const kIndexAction = 3;
        final List<int> actions = log
            .where((MethodCall call) => call.method == 'touch')
            .map<int>((MethodCall call) => (call.arguments as List<dynamic>)[kIndexAction] as int)
            .toList();
        expect(actions, <int>[
          AndroidViewController.kActionDown,
          AndroidViewController.kActionMove,
        ]);
        await bareController.dispatchPointerEvent(
          const PointerUpEvent(
            pointer: 1,
            position: Offset(15, 20),
            timeStamp: Duration(milliseconds: 5016),
          ),
        );
        await bareController.dispose();
      },
    );

    testWidgets(
      'AndroidView under StatefulWidget Transform rebuilds mid-gesture and proactively invalidates direct touch before resuming live globalToLocal echoes',
      (WidgetTester tester) async {
        final log = <MethodCall>[];
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.platform_views,
          (MethodCall methodCall) async {
            log.add(methodCall);
            if (methodCall.method == 'create') {
              return 1;
            }
            if (methodCall.method == 'resize') {
              final args = methodCall.arguments as Map<dynamic, dynamic>;
              return <String, dynamic>{'width': args['width'], 'height': args['height']};
            }
            return null;
          },
        );

        late StateSetter updateTransform;
        var currentTransform = Matrix4.identity();
        AndroidViewController? createdController;

        await tester.pumpWidget(
          Directionality(
            textDirection: TextDirection.ltr,
            child: StatefulBuilder(
              builder: (BuildContext context, StateSetter setState) {
                updateTransform = setState;
                return Align(
                  alignment: Alignment.topLeft,
                  child: Transform(
                    transform: currentTransform,
                    child: SizedBox(
                      width: 200.0,
                      height: 200.0,
                      child: PlatformViewLink(
                        viewType: 'test-tlhc-view',
                        surfaceFactory: (BuildContext context, PlatformViewController controller) {
                          return AndroidViewSurface(
                            controller: controller as AndroidViewController,
                            gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
                            hitTestBehavior: PlatformViewHitTestBehavior.opaque,
                          );
                        },
                        onCreatePlatformView: (PlatformViewCreationParams params) {
                          final SurfaceAndroidViewController controller =
                              PlatformViewsService.initSurfaceAndroidView(
                                id: params.id,
                                viewType: params.viewType,
                                layoutDirection: TextDirection.ltr,
                              );
                          createdController = controller;
                          controller
                            ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
                            ..create();
                          return controller;
                        },
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        );
        await tester.pumpAndSettle();

        const kIndexDownTime = 1;
        const kIndexAction = 3;
        const kIndexPointerCoords = 6;
        const kCoordsIndexX = 7;
        const kCoordsIndexY = 8;

        Future<void> activateDirectDispatch(int viewId, int downTime) async {
          final ByteData message = SystemChannels.platform_views.codec.encodeMethodCall(
            MethodCall('directTouchDispatch', <String, dynamic>{
              'id': viewId,
              'downTime': downTime,
              'active': true,
            }),
          );
          await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
            SystemChannels.platform_views.name,
            message,
            (_) {},
          );
        }

        List<MethodCall> touchCalls() =>
            log.where((MethodCall call) => call.method == 'touch').toList();

        List<MethodCall> invalidateCalls() =>
            log.where((MethodCall call) => call.method == 'invalidateDirectTouch').toList();

        // --- Part 1: Mid-gesture ancestor scale transform change via tester.startGesture ---
        log.clear();
        final TestGesture gesture1 = await tester.startGesture(
          const Offset(40.0, 50.0),
          pointer: 21,
        );
        await tester.pump();
        GestureBinding.instance.gestureArena.close(21);
        GestureBinding.instance.gestureArena.sweep(21);
        await tester.pump();

        expect(touchCalls(), hasLength(1));
        final int viewId = createdController!.viewId;
        final downTime1 = (touchCalls().single.arguments as List<dynamic>)[kIndexDownTime] as int;

        await activateDirectDispatch(viewId, downTime1);
        expect(createdController!.isDirectTouchDispatchActive, isTrue);

        log.clear();
        // While stationary & translation-only, single-pointer move echoes are suppressed.
        await gesture1.moveBy(const Offset(10.0, 10.0));
        await tester.pump();
        expect(touchCalls(), isEmpty);
        expect(invalidateCalls(), isEmpty);

        // Rebuild ancestor Transform with a 2x scale while the pointer remains down, and pump!
        updateTransform(() {
          currentTransform = Matrix4.diagonal3Values(2.0, 2.0, 1.0);
        });
        await tester.pump();

        // Proactive invalidation was sent BEFORE the next move arrives!
        expect(createdController!.isDirectTouchDispatchActive, isFalse);
        expect(invalidateCalls(), hasLength(1));
        expect((invalidateCalls().single.arguments as Map<dynamic, dynamic>)['id'], viewId);
        expect(
          (invalidateCalls().single.arguments as Map<dynamic, dynamic>)['downTime'],
          downTime1,
        );

        log.clear();
        // Move pointer to global (80.0, 100.0): under 2x scale, local coordinate is (40.0, 50.0).
        await gesture1.moveTo(const Offset(80.0, 100.0));
        await tester.pump();
        expect(touchCalls(), hasLength(1));
        final moveArgs1 = touchCalls().single.arguments as List<dynamic>;
        expect(moveArgs1[kIndexAction], AndroidViewController.kActionMove);
        final moveCoords1 =
            (moveArgs1[kIndexPointerCoords] as List<dynamic>).single as List<dynamic>;
        expect(moveCoords1[kCoordsIndexX] as double, closeTo(40.0, 1e-3));
        expect(moveCoords1[kCoordsIndexY] as double, closeTo(50.0, 1e-3));

        await gesture1.up();
        await tester.pump();

        // --- Part 2: Mid-gesture ancestor translation shift via tester.startGesture ---
        updateTransform(() {
          currentTransform = Matrix4.translationValues(10.0, 20.0, 0.0);
        });
        await tester.pump();

        log.clear();
        final TestGesture gesture2 = await tester.startGesture(
          const Offset(60.0, 70.0),
          pointer: 22,
        );
        await tester.pump();
        GestureBinding.instance.gestureArena.close(22);
        GestureBinding.instance.gestureArena.sweep(22);
        await tester.pump();

        expect(touchCalls(), hasLength(1));
        final downTime2 = (touchCalls().single.arguments as List<dynamic>)[kIndexDownTime] as int;

        await activateDirectDispatch(viewId, downTime2);
        expect(createdController!.isDirectTouchDispatchActive, isTrue);

        log.clear();
        await gesture2.moveBy(const Offset(5.0, 5.0));
        await tester.pump();
        expect(touchCalls(), isEmpty);
        expect(invalidateCalls(), isEmpty);

        // Rebuild ancestor Transform with shifted translation (30.0, 50.0) mid-gesture, and pump!
        updateTransform(() {
          currentTransform = Matrix4.translationValues(30.0, 50.0, 0.0);
        });
        await tester.pump();

        expect(createdController!.isDirectTouchDispatchActive, isFalse);
        expect(invalidateCalls(), hasLength(1));
        expect((invalidateCalls().single.arguments as Map<dynamic, dynamic>)['id'], viewId);
        expect(
          (invalidateCalls().single.arguments as Map<dynamic, dynamic>)['downTime'],
          downTime2,
        );

        log.clear();
        await gesture2.moveTo(const Offset(90.0, 110.0));
        await tester.pump();
        expect(touchCalls(), hasLength(1));
        final moveArgs2 = touchCalls().single.arguments as List<dynamic>;
        expect(moveArgs2[kIndexAction], AndroidViewController.kActionMove);
        final moveCoords2 =
            (moveArgs2[kIndexPointerCoords] as List<dynamic>).single as List<dynamic>;
        expect(moveCoords2[kCoordsIndexX] as double, closeTo(60.0, 1e-3));
        expect(moveCoords2[kCoordsIndexY] as double, closeTo(60.0, 1e-3));

        await gesture2.up();
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
        viewsController = FakeAndroidPlatformViewsController();
      },
    );

    testWidgets('platform_views_2 handles directTouchDispatch and viewFocused for HCPP views', (
      WidgetTester tester,
    ) async {
      final log = <MethodCall>[];
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform_views_2,
        (MethodCall methodCall) async {
          log.add(methodCall);
          return null;
        },
      );
      var focusCalled = false;
      final HybridAndroidViewController viewController = PlatformViewsService.initHybridAndroidView(
        id: 19,
        viewType: 'web',
        layoutDirection: TextDirection.ltr,
        onFocus: () {
          focusCalled = true;
        },
      );
      viewController.pointTransformer = (Offset offset) => offset;
      viewController.liveTransformProvider = () => Matrix4.identity();

      final ByteData focusMessage = SystemChannels.platform_views_2.codec.encodeMethodCall(
        const MethodCall('viewFocused', 19),
      );
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        SystemChannels.platform_views_2.name,
        focusMessage,
        (_) {},
      );
      expect(focusCalled, isTrue);

      const kIndexAction = 3;
      List<int> touchActions() => log
          .where((MethodCall call) => call.method == 'touch')
          .map<int>((MethodCall call) => (call.arguments as List<dynamic>)[kIndexAction] as int)
          .toList();

      await viewController.dispatchPointerEvent(
        const PointerDownEvent(
          pointer: 1,
          position: Offset(10, 10),
          timeStamp: Duration(milliseconds: 4000),
        ),
      );
      final ByteData activateMessage = SystemChannels.platform_views_2.codec.encodeMethodCall(
        const MethodCall('directTouchDispatch', <String, dynamic>{
          'id': 19,
          'downTime': 4000,
          'active': true,
        }),
      );
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        SystemChannels.platform_views_2.name,
        activateMessage,
        (_) {},
      );
      await viewController.dispatchPointerEvent(
        const PointerMoveEvent(
          pointer: 1,
          position: Offset(12, 12),
          timeStamp: Duration(milliseconds: 4004),
        ),
      );
      final ByteData deactivateMessage = SystemChannels.platform_views_2.codec.encodeMethodCall(
        const MethodCall('directTouchDispatch', <String, dynamic>{
          'id': 19,
          'downTime': 4000,
          'active': false,
        }),
      );
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        SystemChannels.platform_views_2.name,
        deactivateMessage,
        (_) {},
      );
      await viewController.dispatchPointerEvent(
        const PointerMoveEvent(
          pointer: 1,
          position: Offset(14, 14),
          timeStamp: Duration(milliseconds: 4008),
        ),
      );
      await viewController.dispatchPointerEvent(
        const PointerUpEvent(
          pointer: 1,
          position: Offset(14, 14),
          timeStamp: Duration(milliseconds: 4012),
        ),
      );
      expect(touchActions(), <int>[
        AndroidViewController.kActionDown,
        AndroidViewController.kActionMove,
        AndroidViewController.kActionUp,
      ]);
      await viewController.dispose();
    });

    // Regression test for https://github.com/flutter/flutter/issues/191105.
    testWidgets('motion event converter orders pointers by Android pointer id', (
      WidgetTester tester,
    ) async {
      final log = <MethodCall>[];
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform_views,
        (MethodCall methodCall) async {
          log.add(methodCall);
          return null;
        },
      );

      final AndroidViewController viewController = PlatformViewsService.initSurfaceAndroidView(
        id: 7,
        viewType: 'web',
        layoutDirection: TextDirection.ltr,
      );
      viewController.pointTransformer = (Offset offset) => offset;
      viewController.liveTransformProvider = () => Matrix4.identity();

      // Indexes in the list returned by AndroidMotionEvent._asList.
      const kIndexAction = 3;
      const kIndexPointerCount = 4;
      const kIndexPointerProperties = 5;
      const kIndexPointerCoords = 6;
      // Index of x within the list returned by AndroidPointerCoords._asList.
      const kCoordsIndexX = 7;

      List<double> xOf(MethodCall call) {
        final args = call.arguments as List<dynamic>;
        return (args[kIndexPointerCoords] as List<dynamic>)
            .map<double>((dynamic coords) => (coords as List<dynamic>)[kCoordsIndexX] as double)
            .toList();
      }

      // Flutter pointer 1 takes Android pointer id 0, Flutter pointer 2 takes id 1.
      await viewController.dispatchPointerEvent(
        const PointerDownEvent(pointer: 1, position: Offset(10, 10)),
      );
      await viewController.dispatchPointerEvent(
        const PointerDownEvent(pointer: 2, position: Offset(20, 20)),
      );
      // Lifting Flutter pointer 1 releases Android pointer id 0 for reuse.
      await viewController.dispatchPointerEvent(
        const PointerUpEvent(pointer: 1, position: Offset(10, 10)),
      );
      log.clear();
      // Flutter pointer 3 is assigned the recycled Android pointer id 0, so it is
      // last in insertion order but first in Android pointer id order.
      await viewController.dispatchPointerEvent(
        const PointerDownEvent(pointer: 3, position: Offset(30, 30)),
      );

      final MethodCall downCall = log.singleWhere((MethodCall call) => call.method == 'touch');
      final downArgs = downCall.arguments as List<dynamic>;
      expect(downArgs[kIndexPointerCount], 2);
      // The action index is the position of the pressed pointer in Android pointer id order.
      expect(
        downArgs[kIndexAction],
        AndroidViewController.pointerAction(0, AndroidViewController.kActionPointerDown),
      );
      expect(downArgs[kIndexPointerProperties], <List<int>>[
        <int>[0, AndroidPointerProperties.kToolTypeFinger],
        <int>[1, AndroidPointerProperties.kToolTypeFinger],
      ]);
      // Android pointer id 0 is Flutter pointer 3, id 1 is Flutter pointer 2.
      expect(xOf(downCall), <double>[30, 20]);

      // The engine splits one Android move across its pointers in Android pointer
      // id order, flagging each with the original pointer count. The converter must
      // send a single MotionEvent once the last of them has arrived.
      const kPointerDataFlagMultiple = 2;
      const int platformData = kPointerDataFlagMultiple | (2 << 8);
      log.clear();
      await viewController.dispatchPointerEvent(
        const PointerMoveEvent(pointer: 3, position: Offset(31, 31), platformData: platformData),
      );
      await viewController.dispatchPointerEvent(
        const PointerMoveEvent(pointer: 2, position: Offset(21, 21), platformData: platformData),
      );

      final MethodCall moveCall = log.singleWhere((MethodCall call) => call.method == 'touch');
      final moveArgs = moveCall.arguments as List<dynamic>;
      expect(moveArgs[kIndexAction], AndroidViewController.kActionMove);
      expect(xOf(moveCall), <double>[31, 21]);
    });

    testWidgets(
      'HybridAndroidViewController via AndroidViewSurface proactively invalidates direct touch on platform_views_2 when ancestor Transform changes mid-gesture',
      (WidgetTester tester) async {
        final log = <MethodCall>[];
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.platform_views_2,
          (MethodCall methodCall) async {
            log.add(methodCall);
            if (methodCall.method == 'create') {
              return null;
            }
            return null;
          },
        );
        addTearDown(() {
          tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
            SystemChannels.platform_views_2,
            null,
          );
        });

        final HybridAndroidViewController viewController =
            PlatformViewsService.initHybridAndroidView(
              id: 77,
              viewType: 'hybrid_test_view',
              layoutDirection: TextDirection.ltr,
            );
        await viewController.create();

        late StateSetter updateTransform;
        var scale = 1.0;

        await tester.pumpWidget(
          StatefulBuilder(
            builder: (BuildContext context, StateSetter setState) {
              updateTransform = setState;
              return Directionality(
                textDirection: TextDirection.ltr,
                child: Align(
                  alignment: Alignment.topLeft,
                  child: Transform.scale(
                    scale: scale,
                    alignment: Alignment.topLeft,
                    child: SizedBox(
                      width: 200,
                      height: 200,
                      child: AndroidViewSurface(
                        controller: viewController,
                        gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{
                          Factory<EagerGestureRecognizer>(EagerGestureRecognizer.new),
                        },
                        hitTestBehavior: PlatformViewHitTestBehavior.opaque,
                      ),
                    ),
                  ),
                ),
              );
            },
          ),
        );
        await tester.pumpAndSettle();

        final RenderBox renderBox = tester.renderObject<RenderBox>(find.byType(AndroidViewSurface));
        viewController.pointTransformer = (Offset offset) => renderBox.globalToLocal(offset);

        // Start a real gesture inside the HCPP platform view and win the arena.
        final TestGesture gesture = await tester.startGesture(const Offset(50, 60));
        await tester.pump();

        // Simulate native HCPP direct-touch activation after winning the arena.
        final ByteData message = SystemChannels.platform_views_2.codec.encodeMethodCall(
          const MethodCall('directTouchDispatch', <String, Object?>{
            'id': 77,
            'downTime': 0,
            'active': true,
          }),
        );
        await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
          SystemChannels.platform_views_2.name,
          message,
          (_) {},
        );
        expect(viewController.isDirectTouchDispatchActive, isTrue);

        log.clear();

        // While the live ancestor transform is strictly translation-only, single-pointer
        // move echoes are suppressed because native direct dispatch is active.
        await gesture.moveTo(const Offset(70, 80));
        await tester.pump();
        expect(log.where((MethodCall c) => c.method == 'touch'), isEmpty);
        expect(log.where((MethodCall c) => c.method == 'invalidateDirectTouch'), isEmpty);

        // Rebuild ancestor Transform with non-identity scale mid-gesture.
        // PlatformViewRenderBox's post-frame callback must proactively invalidate direct touch
        // on SystemChannels.platform_views_2 BEFORE the next pointer move arrives.
        updateTransform(() {
          scale = 2.0;
        });
        await tester.pump();

        expect(viewController.isDirectTouchDispatchActive, isFalse);
        final Iterable<MethodCall> invalidations = log.where(
          (MethodCall c) => c.method == 'invalidateDirectTouch',
        );
        expect(invalidations, hasLength(1));
        expect((invalidations.first.arguments as Map<Object?, Object?>)['id'], 77);

        log.clear();

        // Next move immediately resumes sending converted MotionEvent echoes on platform_views_2
        // at exact live local coordinates: (80 / 2.0, 100 / 2.0) = (40, 50).
        await gesture.moveTo(const Offset(80, 100));
        await tester.pump();

        final Iterable<MethodCall> touchCalls = log.where((MethodCall c) => c.method == 'touch');
        expect(touchCalls, hasLength(1));
        final touchArgs = touchCalls.first.arguments as List<dynamic>;
        final coords = (touchArgs[6] as List<dynamic>).first as List<dynamic>;
        expect(coords[7] as double, closeTo(40.0, 1e-3));
        expect(coords[8] as double, closeTo(50.0, 1e-3));

        await gesture.up();
        await tester.pump();
      },
    );
  });

  group('iOS', () {
    late FakeIosPlatformViewsController viewsController;
    setUp(() {
      viewsController = FakeIosPlatformViewsController();
    });

    test('create iOS view of unregistered type', () async {
      expect(() {
        return PlatformViewsService.initUiKitView(
          id: 0,
          viewType: 'web',
          layoutDirection: TextDirection.ltr,
        );
      }, throwsA(isA<PlatformException>()));
    });

    test('create iOS views', () async {
      viewsController.registerViewType('webview');
      await PlatformViewsService.initUiKitView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      await PlatformViewsService.initUiKitView(
        id: 1,
        viewType: 'webview',
        layoutDirection: TextDirection.rtl,
      );
      expect(
        viewsController.views,
        unorderedEquals(<FakeUiKitView>[
          const FakeUiKitView(0, 'webview'),
          const FakeUiKitView(1, 'webview'),
        ]),
      );
    });

    test('reuse iOS view id', () async {
      viewsController.registerViewType('webview');
      await PlatformViewsService.initUiKitView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      expect(
        () => PlatformViewsService.initUiKitView(
          id: 0,
          viewType: 'web',
          layoutDirection: TextDirection.ltr,
        ),
        throwsA(isA<PlatformException>()),
      );
    });

    test('dispose iOS view', () async {
      viewsController.registerViewType('webview');
      await PlatformViewsService.initUiKitView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      final UiKitViewController viewController = await PlatformViewsService.initUiKitView(
        id: 1,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );

      await viewController.dispose();
      expect(
        viewsController.views,
        unorderedEquals(<FakeUiKitView>[const FakeUiKitView(0, 'webview')]),
      );
    });

    test('dispose inexisting iOS view', () async {
      viewsController.registerViewType('webview');
      await PlatformViewsService.initUiKitView(
        id: 0,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      final UiKitViewController viewController = await PlatformViewsService.initUiKitView(
        id: 1,
        viewType: 'webview',
        layoutDirection: TextDirection.ltr,
      );
      await viewController.dispose();
      expect(() async {
        await viewController.dispose();
      }, throwsA(isA<PlatformException>()));
    });
  });

  test('toString works as intended', () async {
    const androidPointerProperties = AndroidPointerProperties(id: 0, toolType: 0);
    expect(androidPointerProperties.toString(), 'AndroidPointerProperties(id: 0, toolType: 0)');

    const zero = 0.0;
    const androidPointerCoords = AndroidPointerCoords(
      orientation: zero,
      pressure: zero,
      size: zero,
      toolMajor: zero,
      toolMinor: zero,
      touchMajor: zero,
      touchMinor: zero,
      x: zero,
      y: zero,
    );
    expect(
      androidPointerCoords.toString(),
      'AndroidPointerCoords(orientation: $zero, '
      'pressure: $zero, '
      'size: $zero, '
      'toolMajor: $zero, '
      'toolMinor: $zero, '
      'touchMajor: $zero, '
      'touchMinor: $zero, '
      'x: $zero, '
      'y: $zero)',
    );

    final androidMotionEvent = AndroidMotionEvent(
      downTime: 0,
      eventTime: 0,
      action: 0,
      pointerCount: 0,
      pointerProperties: <AndroidPointerProperties>[],
      pointerCoords: <AndroidPointerCoords>[],
      metaState: 0,
      buttonState: 0,
      xPrecision: zero,
      yPrecision: zero,
      deviceId: 0,
      edgeFlags: 0,
      source: 0,
      flags: 0,
      motionEventId: 0,
    );
    expect(
      androidMotionEvent.toString(),
      'AndroidPointerEvent(downTime: 0, '
      'eventTime: 0, '
      'action: 0, '
      'pointerCount: 0, '
      'pointerProperties: [], '
      'pointerCoords: [], '
      'metaState: 0, '
      'buttonState: 0, '
      'xPrecision: $zero, '
      'yPrecision: $zero, '
      'deviceId: 0, '
      'edgeFlags: 0, '
      'source: 0, '
      'flags: 0, '
      'motionEventId: 0)',
    );
  });

  test(
    'HybridAndroidViewController.rejectGesture invokes rejectGesture on platform_views_2',
    () async {
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform_views_2,
        (MethodCall call) async {
          calls.add(call);
          return null;
        },
      );

      final HybridAndroidViewController controller = PlatformViewsService.initHybridAndroidView(
        id: 42,
        viewType: 'test_view',
        layoutDirection: TextDirection.ltr,
      );

      await controller.rejectGesture();

      expect(calls, hasLength(1));
      expect(calls.first.method, 'rejectGesture');
      expect(calls.first.arguments, <String, dynamic>{'id': 42});

      calls.clear();
      await controller.rejectGesture(gestureId: 999);
      expect(calls, hasLength(1));
      expect(calls.first.method, 'rejectGesture');
      expect(calls.first.arguments, <String, dynamic>{'id': 42, 'gestureId': 999});
    },
  );

  test(
    'ExpensiveAndroidViewController.rejectGesture invokes rejectGesture on platform_views',
    () async {
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform_views,
        (MethodCall call) async {
          calls.add(call);
          return null;
        },
      );

      final ExpensiveAndroidViewController controller =
          PlatformViewsService.initExpensiveAndroidView(
            id: 43,
            viewType: 'test_view',
            layoutDirection: TextDirection.ltr,
          );

      await controller.rejectGesture();

      expect(calls, hasLength(1));
      expect(calls.first.method, 'rejectGesture');
      expect(calls.first.arguments, <String, dynamic>{'id': 43});
    },
  );

  test(
    'TextureAndroidViewController.rejectGesture invokes rejectGesture on platform_views',
    () async {
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform_views,
        (MethodCall call) async {
          calls.add(call);
          return null;
        },
      );

      final AndroidViewController controller = PlatformViewsService.initAndroidView(
        id: 44,
        viewType: 'test_view',
        layoutDirection: TextDirection.ltr,
      );

      await controller.rejectGesture();

      expect(calls, hasLength(1));
      expect(calls.first.method, 'rejectGesture');
      expect(calls.first.arguments, <String, dynamic>{'id': 44});
    },
  );

  test(
    'SurfaceAndroidViewController.rejectGesture invokes rejectGesture on platform_views',
    () async {
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform_views,
        (MethodCall call) async {
          calls.add(call);
          return null;
        },
      );

      final SurfaceAndroidViewController controller = PlatformViewsService.initSurfaceAndroidView(
        id: 45,
        viewType: 'test_view',
        layoutDirection: TextDirection.ltr,
      );

      await controller.rejectGesture();

      expect(calls, hasLength(1));
      expect(calls.first.method, 'rejectGesture');
      expect(calls.first.arguments, <String, dynamic>{'id': 45});
    },
  );
}
