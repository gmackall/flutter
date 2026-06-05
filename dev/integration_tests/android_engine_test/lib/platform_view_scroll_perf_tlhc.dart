// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Manual performance reproduction for TLHC (Texture Layer Hybrid Composition)
// platform views in a long, fast-scrolling list.
//
// This is intentionally NOT named `*_main.dart` so that the automated Android
// engine driver suite (which globs `lib/**_main.dart`) does not pick it up. It
// is meant to be run and observed by hand.
//
// Background: during a scroll, RenderAndroidView sends an 'offset' platform
// channel message each time the platform view moves, which lands in
// PlatformViewsController.offset(). Historically that called setLayoutParams() ->
// requestLayout() -> a full re-render of the wrapper's texture, every frame, for
// every visible TLHC platform view. With several on screen at once under the
// merged platform/UI thread model, that work can blow the frame budget and the
// list janks. The branch this test accompanies repositions the wrapper without
// re-rendering its texture.
//
// How to run (on a physical Android device, profile mode shows the jank best):
//
//   flutter run --profile \
//     dev/integration_tests/android_engine_test/lib/platform_view_scroll_perf_tlhc.dart
//
// What to look for:
//   * The performance overlay (enabled below) while flinging the list up and
//     down. The UI (top) and raster (bottom) graphs should stay under the 16.6ms
//     line. Before the engine change, frames spike while several platform views
//     are visible at once.
//   * Or capture a System Trace in Android Studio while scrolling and compare the
//     main-thread work inside the 'offset' / PlatformViewWrapper.draw() path.
//
// `AndroidView` uses TLHC by default (the view is not a SurfaceView), so this
// exercises exactly the offset()/texture path the change touches. The list
// interleaves several platform views among many cheap Flutter cards, mirroring
// the original bug report. `AutomaticKeepAliveClientMixin` keeps the platform
// views alive while off screen, matching the report.

import 'package:android_driver_extensions/extension.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

// AndroidView (non-SurfaceView) composes via Texture Layer Hybrid Composition.
// Registered in the test app's MainActivity.
const String _kTlhcViewType = 'blue_orange_gradient_platform_view';

// Total rows in the feed.
const int _kItemCount = 100;

// Every Nth row is a platform view; the rest are cheap Flutter cards. With a
// ~150px platform view card and a phone-sized viewport this keeps several
// platform views on screen simultaneously while scrolling.
const int _kPlatformViewEvery = 6;

void main() async {
  ensureAndroidDevice();

  // Run on full screen to match the other samples.
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersive);

  runApp(const _PlatformViewScrollPerfApp());
}

class _PlatformViewScrollPerfApp extends StatelessWidget {
  const _PlatformViewScrollPerfApp();

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      // The performance overlay makes per-frame jank visible without tooling.
      showPerformanceOverlay: true,
      home: Scaffold(
        appBar: AppBar(title: const Text('TLHC PlatformView Scroll Perf')),
        body: ListView.builder(
          itemCount: _kItemCount,
          itemBuilder: (BuildContext context, int index) {
            if (index % _kPlatformViewEvery == 0) {
              return _NativeAdCard(key: ValueKey<int>(index), index: index);
            }
            return _ProfileCard(key: ValueKey<int>(index), index: index);
          },
        ),
      ),
    );
  }
}

// A cheap, purely-Flutter list row. Stands in for ordinary feed content.
class _ProfileCard extends StatelessWidget {
  const _ProfileCard({super.key, required this.index});

  final int index;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.all(8.0),
      child: ListTile(
        leading: CircleAvatar(child: Text('$index')),
        title: Text('Profile #$index'),
        subtitle: const Text('Ordinary Flutter content row.'),
      ),
    );
  }
}

// A list row hosting a TLHC platform view, kept alive while off screen to mirror
// the original report.
class _NativeAdCard extends StatefulWidget {
  const _NativeAdCard({super.key, required this.index});

  final int index;

  @override
  State<_NativeAdCard> createState() => _NativeAdCardState();
}

class _NativeAdCardState extends State<_NativeAdCard>
    with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return SizedBox(
      height: 150,
      child: Card(
        margin: const EdgeInsets.all(8.0),
        clipBehavior: Clip.antiAlias,
        child: Stack(
          children: <Widget>[
            const Positioned.fill(
              child: AndroidView(viewType: _kTlhcViewType),
            ),
            Positioned(
              left: 8,
              bottom: 8,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: Colors.black54,
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  child: Text(
                    'Ad #${widget.index}',
                    style: const TextStyle(color: Colors.white),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
