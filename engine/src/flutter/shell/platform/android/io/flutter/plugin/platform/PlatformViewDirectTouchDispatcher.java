// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.platform;

import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * Delivers the remainder of a touch gesture synchronously to an embedded platform view once the
 * Flutter framework has resolved the gesture arena in the platform view's favour.
 *
 * <p>By default every {@link MotionEvent} that lands on a platform view's host view ({@code
 * PlatformViewWrapper} for texture layer composition, {@code FlutterMutatorView} for hybrid
 * composition) is intercepted and sent through the Flutter framework's gesture arena (JNI -> Dart
 * -> {@code PlatformViewsChannel#touch} -> JNI), and only then delivered to the embedded view. That
 * round trip is required while the arena is unresolved, but once the framework has started
 * forwarding the gesture to the platform view (i.e. the platform view won), every subsequent {@link
 * MotionEvent#ACTION_MOVE} still pays the asynchronous round trip before reaching e.g. Chromium's
 * input handler. Because that delivery happens outside of {@code ViewRootImpl}'s input stage, it
 * frequently lands after the current frame's traversal and the embedded view only scrolls one vsync
 * later, with frame-to-frame jitter.
 *
 * <p>The host view calls {@link #onTouchEvent} for every event it receives (before forwarding it to
 * the framework) and the platform views controller calls {@link #onPlatformViewWonGesture} when the
 * framework forwards the first event of a gesture. From then on, events of that gesture are
 * delivered to the embedded view synchronously (inside {@code ViewRootImpl}'s input stage, with
 * full historical batching and the verified-input flag intact). They are still forwarded to the
 * framework so Dart-side bookkeeping (pointer router, hit tests, arena, {@code Listener} widgets)
 * stays consistent, but the controller drops the framework's echo for events that were already
 * delivered directly (see {@link #isDirectlyDispatched}).
 *
 * <p>Direct dispatch is only used when the framework's PV-local coordinates agree with the host
 * view's local coordinates, i.e. when the platform view is merely translated. Scaled, rotated or
 * skewed platform views ({@code Transform}, {@code FittedBox}, {@code InteractiveViewer}) keep using
 * the round trip because only the framework knows the correct local coordinates.
 *
 * <p>A side effect of synchronous delivery is that an embedded view's own {@link
 * View#requestUnbufferedDispatch(MotionEvent)} now takes effect: {@code ViewRootImpl} only honours
 * that request when it is made during the dispatch of the event, which was never the case for
 * events that arrived through the asynchronous framework round trip.
 */
public final class PlatformViewDirectTouchDispatcher {
  /** Whether direct dispatch is enabled. Visible so that tests can exercise both paths. */
  @VisibleForTesting public static boolean enabled = true;

  /**
   * Maximum distance, in physical pixels, between the coordinates the framework computed for an
   * event and the coordinates the same event had in the host view for the two to be considered the
   * same mapping.
   */
  private static final float COORD_TOLERANCE = 2.0f;

  /** Capacity of the ring buffer of recent events of the active gesture. */
  private static final int RECENT_EVENTS = 64;

  private final long[] recentEventTimes = new long[RECENT_EVENTS];
  private final float[] recentEventX = new float[RECENT_EVENTS];
  private final float[] recentEventY = new float[RECENT_EVENTS];
  private int recentEventCount = 0;

  /** downTime of the gesture for which direct dispatch was activated, or -1. */
  private long directDownTime = -1;

  /** Whether events of {@link #directDownTime} are currently delivered directly. */
  private boolean active = false;

  /** eventTime of the newest event delivered directly to the embedded view. */
  private long lastDirectEventTime = -1;

  /**
   * Must be called by the host view for every touch event it receives, before the event is
   * forwarded to the framework. Records the event and, if its gesture is in direct-dispatch mode,
   * delivers it to {@code embeddedView} synchronously.
   *
   * @param event The event, in the host view's coordinate space.
   * @param embeddedView The platform view's view, or null.
   * @return true if the event was delivered directly to {@code embeddedView}.
   */
  public boolean onTouchEvent(@NonNull MotionEvent event, @Nullable View embeddedView) {
    final int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN) {
      recentEventCount = 0;
      directDownTime = -1;
      active = false;
      lastDirectEventTime = -1;
    }
    final int slot = recentEventCount % RECENT_EVENTS;
    recentEventTimes[slot] = event.getEventTime();
    recentEventX[slot] = event.getX();
    recentEventY[slot] = event.getY();
    recentEventCount++;

    if (action == MotionEvent.ACTION_DOWN
        || !active
        || event.getDownTime() != directDownTime) {
      return false;
    }
    if (event.getPointerCount() > 1
        || action == MotionEvent.ACTION_POINTER_DOWN
        || action == MotionEvent.ACTION_POINTER_UP) {
      // The framework may filter pointers of multi-touch gestures (e.g. a second pointer that
      // landed on a Flutter widget); hand the remainder of this gesture back to the round trip.
      // Echoes of events that were already delivered directly are still dropped via
      // isDirectlyDispatched because directDownTime/lastDirectEventTime are retained.
      active = false;
      return false;
    }
    boolean delivered = false;
    if (embeddedView != null) {
      lastDirectEventTime = event.getEventTime();
      embeddedView.dispatchTouchEvent(event);
      delivered = true;
    }
    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      active = false;
    }
    return delivered;
  }

  /**
   * Informs the dispatcher that the Flutter framework started forwarding the given gesture to the
   * platform view (the platform view won the gesture arena).
   *
   * @param gestureActive Whether the host view currently has an active gesture.
   * @param currentDownTime The downTime of the host view's active gesture.
   * @param forwardedEvent The first event of the gesture forwarded by the framework, with the
   *     framework's PV-local coordinates.
   */
  public void onPlatformViewWonGesture(
      boolean gestureActive, long currentDownTime, @NonNull MotionEvent forwardedEvent) {
    if (!enabled) {
      return;
    }
    final long downTime = forwardedEvent.getDownTime();
    if (gestureActive
        && currentDownTime == downTime
        && directDownTime != downTime
        && frameworkCoordsMatchOurs(
            forwardedEvent.getEventTime(), forwardedEvent.getX(), forwardedEvent.getY())) {
      directDownTime = downTime;
      active = true;
      lastDirectEventTime = -1;
    }
  }

  /**
   * Returns true if the given event (echoed back from the framework) belongs to a gesture that
   * used direct dispatch and a newer or equal event has already been delivered directly, in which
   * case the echo must be dropped to avoid out-of-order / duplicate delivery.
   */
  public boolean isDirectlyDispatched(@NonNull MotionEvent echoedEvent) {
    return echoedEvent.getDownTime() == directDownTime
        && lastDirectEventTime >= 0
        && echoedEvent.getEventTime() <= lastDirectEventTime;
  }

  /**
   * Verifies a framework echo of an event that was already delivered directly. If the framework's
   * coordinates no longer agree with ours (the platform view started being transformed during the
   * gesture), the remainder of the gesture falls back to the round trip.
   */
  public void verifyDirectlyDispatchedEcho(@NonNull MotionEvent echoedEvent) {
    if (active
        && echoedEvent.getDownTime() == directDownTime
        && !frameworkCoordsMatchOurs(
            echoedEvent.getEventTime(), echoedEvent.getX(), echoedEvent.getY())) {
      active = false;
    }
  }

  /** Whether the active gesture is currently delivered directly. */
  public boolean isActive() {
    return active;
  }

  private boolean frameworkCoordsMatchOurs(long eventTime, float frameworkX, float frameworkY) {
    final int n = Math.min(recentEventCount, RECENT_EVENTS);
    for (int i = 0; i < n; i++) {
      if (recentEventTimes[i] == eventTime) {
        return Math.abs(recentEventX[i] - frameworkX) <= COORD_TOLERANCE
            && Math.abs(recentEventY[i] - frameworkY) <= COORD_TOLERANCE;
      }
    }
    return false;
  }
}
