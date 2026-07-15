// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:async';
import 'dart:convert';

import 'package:android_driver_extensions/extension.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_driver/driver_extension.dart';

import '../src/allow_list_devices.dart';

void main() async {
  ensureAndroidDevice();
  enableFlutterDriverExtension(
    handler: (String? command) async {
      return json.encode(<String, Object?>{
        'supported': await HybridAndroidViewController.checkIfSupported(),
      });
    },
    commands: <CommandExtension>[nativeDriverCommands],
  );

  // Run on full screen.
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersive);
  runApp(const ScrollablePlatformViewApp());
}

/// A Flutter app hosting a red grid SurfaceView platform view and mimicking google_maps_flutter's
/// `onCameraMove` + `setState()` behavior to test frame invalidation and overlay buffer churn.
final class ScrollablePlatformViewApp extends StatefulWidget {
  const ScrollablePlatformViewApp({super.key});

  @override
  State<ScrollablePlatformViewApp> createState() => _ScrollablePlatformViewAppState();
}

class _ScrollablePlatformViewAppState extends State<ScrollablePlatformViewApp> {
  MethodChannel? _channel;
  Timer? _autoPanTimer;
  bool _isAutoPanning = false;
  String _cameraPositionText = 'Camera: (0.0, 0.0)';
  int _panDirection = 1;
  double _accumulatedPan = 0;

  void _onPlatformViewCreated(int id) {
    _channel = MethodChannel('scrollable_platform_view_$id');
    _channel?.setMethodCallHandler((MethodCall call) async {
      if (call.method == 'onCameraMove') {
        final args = call.arguments as Map<dynamic, dynamic>;
        final double posX = (args['posX'] as num).toDouble();
        final double posY = (args['posY'] as num).toDouble();
        // Mimic google_maps_flutter onCameraMove + setState on every camera movement frame!
        if (mounted) {
          setState(() {
            _cameraPositionText =
                'Camera: (${posX.toStringAsFixed(1)}, ${posY.toStringAsFixed(1)})';
          });
        }
      }
    });
  }

  void _panBy(double dx, double dy) {
    _channel?.invokeMethod<void>('panBy', <String, dynamic>{'dx': dx, 'dy': dy});
  }

  void _toggleAutoPan() {
    setState(() {
      _isAutoPanning = !_isAutoPanning;
      if (_isAutoPanning) {
        _autoPanTimer = Timer.periodic(const Duration(milliseconds: 16), (_) {
          const delta = 5.0;
          _panBy(delta * _panDirection, delta * _panDirection);
          _accumulatedPan += delta * _panDirection;
          if (_accumulatedPan > 500) {
            _panDirection = -1;
          } else if (_accumulatedPan < -500) {
            _panDirection = 1;
          }
        });
      } else {
        _autoPanTimer?.cancel();
        _autoPanTimer = null;
      }
    });
  }

  @override
  void dispose() {
    _autoPanTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        backgroundColor: const Color(0xFF121214),
        body: SafeArea(
          child: Stack(
            children: <Widget>[
              // 1. Flutter Background Content (Underneath the platform view layer).
              Positioned.fill(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                        decoration: BoxDecoration(
                          color: const Color(0xFF1E1E2C),
                          borderRadius: BorderRadius.circular(8),
                          border: Border.all(color: const Color(0xFF3F51B5), width: 1.5),
                        ),
                        child: const Text(
                          '[FLUTTER VIEW] Background Canvas',
                          style: TextStyle(
                            color: Colors.white,
                            fontWeight: FontWeight.bold,
                            fontSize: 14,
                          ),
                        ),
                      ),
                      const SizedBox(height: 12),
                      Expanded(
                        child: ListView.builder(
                          itemCount: 20,
                          itemBuilder: (BuildContext context, int index) {
                            return Container(
                              margin: const EdgeInsets.symmetric(vertical: 4),
                              padding: const EdgeInsets.all(12),
                              decoration: BoxDecoration(
                                color: const Color(0xFF252530),
                                borderRadius: BorderRadius.circular(8),
                              ),
                              child: Text(
                                'Flutter Card #$index (Background)',
                                style: const TextStyle(color: Colors.white70, fontSize: 13),
                              ),
                            );
                          },
                        ),
                      ),
                    ],
                  ),
                ),
              ),

              // 2. Red Grid SurfaceView Platform View (Centered Card).
              Positioned(
                top: 70,
                left: 16,
                right: 16,
                height: 420,
                child: Container(
                  decoration: BoxDecoration(
                    color: Colors.black,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: const Color(0xFFFF5252), width: 3),
                    boxShadow: const <BoxShadow>[
                      BoxShadow(color: Color(0xCC000000), blurRadius: 10, offset: Offset(0, 4)),
                    ],
                  ),
                  clipBehavior: Clip.antiAlias,
                  child: Column(
                    children: <Widget>[
                      Container(
                        color: const Color(0xFFFF5252),
                        width: double.infinity,
                        padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 12),
                        child: const Text(
                          'RED GRID SURFACE VIEW (Google Maps Repro)',
                          style: TextStyle(
                            color: Colors.white,
                            fontWeight: FontWeight.bold,
                            fontSize: 12,
                          ),
                          textAlign: TextAlign.center,
                        ),
                      ),
                      Expanded(
                        child: _HybridCompositionScrollablePlatformView(
                          viewType: 'scrollable_platform_view',
                          onPlatformViewCreated: _onPlatformViewCreated,
                        ),
                      ),
                    ],
                  ),
                ),
              ),

              // 3. Flutter Overlay Layer (Rendered ON TOP of the Red Grid SurfaceView).
              // Receives onCameraMove calls and executes setState() on every camera frame update!
              Positioned(
                top: 240,
                left: 28,
                right: 28,
                child: Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: const Color(0xFFFFC107),
                    borderRadius: BorderRadius.circular(12),
                    boxShadow: const <BoxShadow>[
                      BoxShadow(color: Color(0xCC000000), blurRadius: 12, offset: Offset(0, 4)),
                    ],
                    border: Border.all(width: 2),
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: <Widget>[
                      const Text(
                        'FLUTTER OVERLAY LAYER (onCameraMove setState target)',
                        style: TextStyle(
                          color: Colors.black,
                          fontWeight: FontWeight.w900,
                          fontSize: 11,
                          letterSpacing: 0.5,
                        ),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 6),
                      Text(
                        _cameraPositionText,
                        style: const TextStyle(
                          color: Colors.black,
                          fontWeight: FontWeight.bold,
                          fontSize: 13,
                        ),
                      ),
                      const SizedBox(height: 10),
                      Wrap(
                        alignment: WrapAlignment.center,
                        spacing: 8,
                        runSpacing: 8,
                        children: <Widget>[
                          ElevatedButton(
                            onPressed: () => _panBy(50, 50),
                            style: ElevatedButton.styleFrom(
                              backgroundColor: Colors.black,
                              foregroundColor: Colors.white,
                              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                            ),
                            child: const Text('Pan Map', style: TextStyle(fontSize: 12)),
                          ),
                          ElevatedButton(
                            onPressed: _toggleAutoPan,
                            style: ElevatedButton.styleFrom(
                              backgroundColor: _isAutoPanning
                                  ? Colors.red.shade900
                                  : Colors.green.shade900,
                              foregroundColor: Colors.white,
                              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                            ),
                            child: Text(
                              _isAutoPanning ? 'Stop Auto-Pan' : 'Auto Pan Map',
                              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.bold),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

final class _HybridCompositionScrollablePlatformView extends StatelessWidget {
  const _HybridCompositionScrollablePlatformView({
    required this.viewType,
    required this.onPlatformViewCreated,
  });

  final String viewType;
  final ValueChanged<int> onPlatformViewCreated;

  @override
  Widget build(BuildContext context) {
    return PlatformViewLink(
      viewType: viewType,
      surfaceFactory: (BuildContext context, PlatformViewController controller) {
        return AndroidViewSurface(
          controller: controller as AndroidViewController,
          gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{
            Factory<OneSequenceGestureRecognizer>(EagerGestureRecognizer.new),
          },
          hitTestBehavior: PlatformViewHitTestBehavior.opaque,
        );
      },
      onCreatePlatformView: (PlatformViewCreationParams params) {
        return PlatformViewsService.initExpensiveAndroidView(
            id: params.id,
            viewType: viewType,
            layoutDirection: TextDirection.ltr,
            creationParamsCodec: const StandardMessageCodec(),
          )
          ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
          ..addOnPlatformViewCreatedListener(onPlatformViewCreated)
          ..create();
      },
    );
  }
}
