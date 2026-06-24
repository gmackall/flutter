// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

import 'platform_view_mode.dart';

/// Signature shared by [PlatformViewsService.initExpensiveAndroidView] (HC) and
/// [PlatformViewsService.initHybridAndroidView] (HCPP). Both return subtypes of
/// [AndroidViewController], so a single widget can construct either by swapping
/// the factory.
typedef _AndroidViewControllerFactory =
    AndroidViewController Function({
      required int id,
      required String viewType,
      required TextDirection layoutDirection,
      dynamic creationParams,
      MessageCodec<dynamic>? creationParamsCodec,
      VoidCallback? onFocus,
    });

/// Returns the native view-factory id to use for [content] under [mode].
///
/// SurfaceView-backed modes ([PvMode.hcpp], [PvMode.vd]) require a `SurfaceView`
/// factory; texture-backed modes ([PvMode.tlhc], [PvMode.hc]) use a plain `View`
/// factory. Only [PvContent.blueOrangeGradient] has both variants registered
/// (see `MainActivity.kt`).
String viewTypeFor(PvMode mode, PvContent content) {
  switch (content) {
    case PvContent.blueOrangeGradient:
      return switch (mode) {
        PvMode.hcpp || PvMode.vd => 'blue_orange_gradient_surface_view_platform_view',
        PvMode.tlhc || PvMode.hc => 'blue_orange_gradient_platform_view',
      };
    case PvContent.box:
      return 'box_platform_view';
    case PvContent.changingColorButton:
      return 'changing_color_button_platform_view';
  }
}

/// Builds the platform-view [Widget] for [content] using the composition path
/// selected by [mode].
///
/// This is the single place that knows how each [PvMode] is constructed, so a
/// functionality test can be written once and run under any supported mode by
/// varying only `--dart-define=PV_MODE`.
Widget platformViewForMode(
  PvMode mode, {
  required PvContent content,
  TextDirection layoutDirection = TextDirection.ltr,
}) {
  final String viewType = viewTypeFor(mode, content);
  switch (mode) {
    // TLHC and VD are both expressed through the plain [AndroidView] widget.
    // The engine picks TLHC when supported and falls back to VD otherwise; VD
    // cannot be forced (see [PvMode.vd]).
    case PvMode.tlhc:
    case PvMode.vd:
      return AndroidView(viewType: viewType, layoutDirection: layoutDirection);
    case PvMode.hc:
      return _ControllerPlatformView(
        viewType: viewType,
        layoutDirection: layoutDirection,
        factory: PlatformViewsService.initExpensiveAndroidView,
      );
    case PvMode.hcpp:
      return _ControllerPlatformView(
        viewType: viewType,
        layoutDirection: layoutDirection,
        factory: PlatformViewsService.initHybridAndroidView,
      );
  }
}

/// A platform view constructed via [PlatformViewLink] and an explicit
/// [AndroidViewController] factory (used for the HC and HCPP modes).
final class _ControllerPlatformView extends StatelessWidget {
  const _ControllerPlatformView({
    required this.viewType,
    required this.layoutDirection,
    required this.factory,
  });

  final String viewType;
  final TextDirection layoutDirection;
  final _AndroidViewControllerFactory factory;

  @override
  Widget build(BuildContext context) {
    return PlatformViewLink(
      viewType: viewType,
      surfaceFactory: (BuildContext context, PlatformViewController controller) {
        return AndroidViewSurface(
          controller: controller as AndroidViewController,
          gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
          hitTestBehavior: PlatformViewHitTestBehavior.opaque,
        );
      },
      onCreatePlatformView: (PlatformViewCreationParams params) {
        return factory(
            id: params.id,
            viewType: viewType,
            layoutDirection: layoutDirection,
            creationParamsCodec: const StandardMessageCodec(),
          )
          ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
          ..create();
      },
    );
  }
}
