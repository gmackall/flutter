// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.platform;

import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import io.flutter.embedding.engine.mutatorsstack.FlutterMutatorView;

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
 * <p>Direct dispatch is only used when the view-to-root hierarchy has a translation-only
 * transform (no scale, rotation, skew, or perspective on the embedded view, its wrapper/mutator
 * host, or ancestor views) AND the framework's PV-local coordinates agree with the host view's
 * local coordinates. Scaled, rotated, or skewed platform views ({@code Transform}, {@code
 * FittedBox}, {@code InteractiveViewer}) keep using the round trip because only the framework
 * knows the correct local coordinates. Transform eligibility is checked both when activating
 * direct dispatch ({@link #onPlatformViewWonGesture}) and before each directly-dispatched event
 * ({@link #prepareTouchEvent}), immediately deactivating and notifying the framework if a
 * non-translation transform is introduced mid-gesture.
 *
 * <p>A side effect of synchronous delivery is that an embedded view's own {@link
 * View#requestUnbufferedDispatch(MotionEvent)} now takes effect: {@code ViewRootImpl} only honours
 * that request when it is made during the dispatch of the event, which was never the case for
 * events that arrived through the asynchronous framework round trip.
 */
public final class PlatformViewDirectTouchDispatcher {
  /**
   * Listener notified when direct dispatch for a gesture is deactivated before the gesture ends
   * (for example, on multi-touch, mid-gesture non-translation transform, or when an in-flight
   * echo's coordinates diverge).
   */
  public interface OnDirectTouchDeactivatedListener {
    void onDirectTouchDeactivated(long downTime);
  }

  /** Whether direct dispatch is enabled. Visible so that tests can exercise both paths. */
  @VisibleForTesting public static boolean enabled = true;

  /**
   * Maximum distance, in physical pixels, between the coordinates the framework computed for an
   * event and the coordinates the same event had in the host view for the two to be considered the
   * same mapping.
   */
  private static final float COORD_TOLERANCE = 2.0f;

  /** Tolerance for floating-point matrix entries when checking for a translation-only matrix. */
  private static final float MATRIX_EPSILON = 1e-4f;

  /** Capacity of the ring buffer of recent events of the active gesture. */
  private static final int RECENT_EVENTS = 64;

  /**
   * Capacity of the ring buffer of recently completed direct-dispatched gestures, retained so
   * delayed framework echoes from an earlier gesture are still suppressed after a new native
   * {@link MotionEvent#ACTION_DOWN} arrives.
   */
  private static final int COMPLETED_DIRECT_GESTURES = 8;

  private final long[] recentEventTimes = new long[RECENT_EVENTS];
  private final float[] recentEventX = new float[RECENT_EVENTS];
  private final float[] recentEventY = new float[RECENT_EVENTS];
  private int recentEventCount = 0;

  private final long[] completedDirectDownTimes = new long[COMPLETED_DIRECT_GESTURES];
  private final long[] completedDirectLastEventTimes = new long[COMPLETED_DIRECT_GESTURES];
  private int completedDirectGestureCount = 0;

  /** Reusable scratch array for {@link Matrix#getValues(float[])} on the UI/platform thread. */
  private final float[] matrixValues = new float[9];

  /** downTime of the most recent native {@link MotionEvent#ACTION_DOWN}, or -1. */
  private long activeDownTime = -1;

  /** downTime of the gesture for which direct dispatch was activated, or -1. */
  private long directDownTime = -1;

  /** Whether events of {@link #directDownTime} are currently delivered directly. */
  private boolean active = false;

  /** eventTime of the newest event delivered directly to the embedded view. */
  private long lastDirectEventTime = -1;

  /**
   * Monotonically increasing generation counter incremented whenever the Flutter-side view-to-root
   * mapping is invalidated (for example, when an ancestor Flutter {@code Transform} changes scale,
   * rotation, skew, perspective, or translation mid-gesture, or when live transform eligibility
   * cannot be established).
   *
   * <p>When a fresh native {@link MotionEvent#ACTION_DOWN} begins, {@link
   * #currentGestureMappingGeneration} captures the current {@link #mappingGeneration}. If {@link
   * #invalidateDirectTouch} runs after that {@code ACTION_DOWN}, {@link #mappingGeneration}
   * advances past {@link #currentGestureMappingGeneration}, preventing any stale in-flight
   * framework echo from re-activating direct touch dispatch until the next fresh {@code
   * ACTION_DOWN} begins with a valid translation-only mapping.
   */
  private long mappingGeneration = 0;

  /** Snapshot of {@link #mappingGeneration} captured on the latest native {@code ACTION_DOWN}. */
  private long currentGestureMappingGeneration = 0;

  /**
   * downTime explicitly invalidated by the framework (or -1), preventing re-activation of direct
   * dispatch for that gesture even if additional echoes arrive before the gesture ends.
   */
  private long invalidatedDownTime = -1;

  @Nullable private OnDirectTouchDeactivatedListener onDeactivatedListener;

  /**
   * Returns true if {@code matrix} is null, identity, or a pure 2D translation (no scale,
   * rotation, skew, or perspective).
   */
  public static boolean isTranslationOnlyMatrix(@Nullable Matrix matrix) {
    return isTranslationOnlyMatrix(matrix, new float[9]);
  }

  private static boolean isTranslationOnlyMatrix(
      @Nullable Matrix matrix, @NonNull float[] values) {
    if (matrix == null || matrix.isIdentity()) {
      return true;
    }
    matrix.getValues(values);
    return Math.abs(values[Matrix.MSCALE_X] - 1.0f) <= MATRIX_EPSILON
        && Math.abs(values[Matrix.MSCALE_Y] - 1.0f) <= MATRIX_EPSILON
        && Math.abs(values[Matrix.MSKEW_X]) <= MATRIX_EPSILON
        && Math.abs(values[Matrix.MSKEW_Y]) <= MATRIX_EPSILON
        && Math.abs(values[Matrix.MPERSP_0]) <= MATRIX_EPSILON
        && Math.abs(values[Matrix.MPERSP_1]) <= MATRIX_EPSILON
        && Math.abs(values[Matrix.MPERSP_2] - 1.0f) <= MATRIX_EPSILON;
  }

  /**
   * Returns true if {@code embeddedView} and its immediate host container ({@link
   * PlatformViewWrapper} or {@link FlutterMutatorView}) have only translation transforms
   * (translation-only {@link View#getMatrix()} and translation-only {@link FlutterMutatorView}
   * mutator matrix).
   */
  public static boolean isTranslationOnlyTransform(@Nullable View embeddedView) {
    return isTranslationOnlyTransform(embeddedView, new float[9]);
  }

  private static boolean isTranslationOnlyTransform(
      @Nullable View embeddedView, @NonNull float[] scratchValues) {
    if (embeddedView == null) {
      return true;
    }
    if (!isSingleViewTranslationOnly(embeddedView, scratchValues)) {
      return false;
    }
    final ViewParent parent = embeddedView.getParent();
    if (parent instanceof View && !isSingleViewTranslationOnly((View) parent, scratchValues)) {
      return false;
    }
    return true;
  }

  private static boolean isSingleViewTranslationOnly(
      @NonNull View view, @NonNull float[] scratchValues) {
    if (!isTranslationOnlyMatrix(view.getMatrix(), scratchValues)) {
      return false;
    }
    if (view instanceof FlutterMutatorView
        && !((FlutterMutatorView) view).hasTranslationOnlyMutatorTransform()) {
      return false;
    }
    return true;
  }

  /**
   * Sets a listener invoked when direct dispatch transitions from active to inactive mid-gesture
   * (for reasons other than {@link MotionEvent#ACTION_UP} or {@link MotionEvent#ACTION_CANCEL}), so
   * the framework can resume echoing single-pointer moves for the remainder of the gesture.
   */
  public void setOnDirectTouchDeactivatedListener(
      @Nullable OnDirectTouchDeactivatedListener listener) {
    this.onDeactivatedListener = listener;
  }

  /**
   * Records {@code event} (using its unmutated coordinates) and determines whether it should be
   * dispatched directly to {@code embeddedView}.
   *
   * <p>Host views that also forward {@code event} to {@code AndroidTouchProcessor} should call
   * {@link #prepareTouchEvent} first, then forward {@code event} to {@code AndroidTouchProcessor}
   * before calling {@code embeddedView.dispatchTouchEvent(event)}, so a child view that mutates its
   * {@link MotionEvent} in place cannot corrupt the coordinates sent to Flutter.
   *
   * @param event The event, in the host view's coordinate space.
   * @param embeddedView The platform view's view, or null.
   * @return true if the caller should now dispatch {@code event} directly to {@code embeddedView}.
   */
  public boolean prepareTouchEvent(@NonNull MotionEvent event, @Nullable View embeddedView) {
    final int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN) {
      if (directDownTime != -1 && lastDirectEventTime >= 0) {
        final int completedSlot = completedDirectGestureCount % COMPLETED_DIRECT_GESTURES;
        completedDirectDownTimes[completedSlot] = directDownTime;
        completedDirectLastEventTimes[completedSlot] = lastDirectEventTime;
        completedDirectGestureCount++;
      }
      recentEventCount = 0;
      activeDownTime = event.getDownTime();
      directDownTime = -1;
      active = false;
      lastDirectEventTime = -1;
      currentGestureMappingGeneration = mappingGeneration;
      invalidatedDownTime = -1;
    }
    final int slot = recentEventCount % RECENT_EVENTS;
    recentEventTimes[slot] = event.getEventTime();
    recentEventX[slot] = event.getX();
    recentEventY[slot] = event.getY();
    recentEventCount++;

    if (action == MotionEvent.ACTION_DOWN
        || !active
        || event.getDownTime() != directDownTime
        || currentGestureMappingGeneration != mappingGeneration
        || invalidatedDownTime == event.getDownTime()) {
      return false;
    }
    if (event.getPointerCount() > 1
        || action == MotionEvent.ACTION_POINTER_DOWN
        || action == MotionEvent.ACTION_POINTER_UP
        || !isTranslationOnlyTransform(embeddedView, matrixValues)) {
      // The framework may filter pointers of multi-touch gestures (e.g. a second pointer that
      // landed on a Flutter widget) or apply a non-translation transform; hand the remainder of
      // this gesture back to the round trip. Echoes of single-pointer events that were already
      // delivered directly are still dropped via isDirectlyDispatched because
      // directDownTime/lastDirectEventTime are retained.
      active = false;
      if (onDeactivatedListener != null) {
        onDeactivatedListener.onDirectTouchDeactivated(directDownTime);
      }
      return false;
    }
    final boolean deliver = embeddedView != null;
    if (deliver) {
      lastDirectEventTime = event.getEventTime();
    }
    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      active = false;
    }
    return deliver;
  }

  /**
   * Must be called by the host view for every touch event it receives. Records the event and, if
   * its gesture is in direct-dispatch mode, delivers it to {@code embeddedView} synchronously.
   *
   * @param event The event, in the host view's coordinate space.
   * @param embeddedView The platform view's view, or null.
   * @return true if the event was delivered directly to {@code embeddedView}.
   */
  public boolean onTouchEvent(@NonNull MotionEvent event, @Nullable View embeddedView) {
    if (prepareTouchEvent(event, embeddedView)) {
      embeddedView.dispatchTouchEvent(event);
      return true;
    }
    return false;
  }

  /**
   * Informs the dispatcher that the Flutter framework started forwarding the given gesture to the
   * platform view (the platform view won the gesture arena).
   *
   * @param gestureActive Whether the host view currently has an active gesture.
   * @param currentDownTime The downTime of the host view's active gesture.
   * @param forwardedEvent The first event of the gesture forwarded by the framework, with the
   *     framework's PV-local coordinates.
   * @return true if this call activated direct dispatch for {@code forwardedEvent}'s gesture.
   */
  public boolean onPlatformViewWonGesture(
      boolean gestureActive, long currentDownTime, @NonNull MotionEvent forwardedEvent) {
    return onPlatformViewWonGesture(gestureActive, currentDownTime, forwardedEvent, null);
  }

  /**
   * Informs the dispatcher that the Flutter framework started forwarding the given gesture to the
   * platform view (the platform view won the gesture arena), checking both transform eligibility
   * on {@code embeddedView}'s hierarchy and coordinate agreement.
   *
   * @param gestureActive Whether the host view currently has an active gesture.
   * @param currentDownTime The downTime of the host view's active gesture.
   * @param forwardedEvent The first event of the gesture forwarded by the framework, with the
   *     framework's PV-local coordinates.
   * @param embeddedView The embedded view (or host view) to inspect for transform eligibility.
   * @return true if this call activated direct dispatch for {@code forwardedEvent}'s gesture.
   */
  public boolean onPlatformViewWonGesture(
      boolean gestureActive,
      long currentDownTime,
      @NonNull MotionEvent forwardedEvent,
      @Nullable View embeddedView) {
    if (!enabled) {
      return false;
    }
    final long downTime = forwardedEvent.getDownTime();
    if (gestureActive
        && currentDownTime == downTime
        && directDownTime != downTime
        && invalidatedDownTime != downTime
        && currentGestureMappingGeneration == mappingGeneration
        && isTranslationOnlyTransform(embeddedView, matrixValues)
        && frameworkCoordsMatchOurs(
            forwardedEvent.getEventTime(), forwardedEvent.getX(), forwardedEvent.getY())) {
      directDownTime = downTime;
      active = true;
      lastDirectEventTime = -1;
      return true;
    }
    return false;
  }

  /**
   * Returns true if the given event (echoed back from the framework) is a single-pointer move, up,
   * or cancel belonging to a gesture that used direct dispatch and a newer or equal event has
   * already been delivered directly, in which case the echo must be dropped to avoid out-of-order /
   * duplicate delivery.
   *
   * <p>Also checks recently completed direct-dispatched gestures so delayed framework echoes
   * arriving after a subsequent gesture's native {@link MotionEvent#ACTION_DOWN} are still
   * suppressed.
   *
   * <p>Multi-pointer events ({@code pointerCount > 1}, {@link MotionEvent#ACTION_POINTER_DOWN},
   * {@link MotionEvent#ACTION_POINTER_UP}) and {@link MotionEvent#ACTION_DOWN} are never delivered
   * directly by this dispatcher, so their echoes are never dropped even if they share the same
   * millisecond {@code eventTime} as the last directly-dispatched event.
   */
  public boolean isDirectlyDispatched(@NonNull MotionEvent echoedEvent) {
    final int action = echoedEvent.getActionMasked();
    final boolean canBeDeliveredDirectly =
        echoedEvent.getPointerCount() == 1
            && (action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL);
    if (!canBeDeliveredDirectly) {
      return false;
    }
    final long downTime = echoedEvent.getDownTime();
    final long eventTime = echoedEvent.getEventTime();
    if (downTime == directDownTime && lastDirectEventTime >= 0 && eventTime <= lastDirectEventTime) {
      return true;
    }
    final int n = Math.min(completedDirectGestureCount, COMPLETED_DIRECT_GESTURES);
    for (int i = 0; i < n; i++) {
      if (completedDirectDownTimes[i] == downTime
          && eventTime <= completedDirectLastEventTimes[i]) {
        return true;
      }
    }
    return false;
  }

  /**
   * Invalidates native direct touch dispatch for the current gesture without injecting a synthetic
   * {@link MotionEvent#ACTION_CANCEL}, and increments the mapping generation counter so stale
   * in-flight framework echoes cannot re-enable direct dispatch until the next fresh {@link
   * MotionEvent#ACTION_DOWN} gesture begins with a valid translation-only mapping.
   */
  public void invalidateDirectTouch() {
    invalidateDirectTouch(directDownTime);
  }

  /**
   * Invalidates native direct touch dispatch for {@code downTime} (or the currently active gesture
   * when {@code downTime < 0}) without injecting a synthetic {@link MotionEvent#ACTION_CANCEL},
   * incrementing {@link #mappingGeneration}.
   */
  public void invalidateDirectTouch(long downTime) {
    final boolean matchesCurrentGesture =
        downTime < 0
            || downTime == directDownTime
            || (activeDownTime >= 0 && downTime == activeDownTime);
    if (matchesCurrentGesture) {
      mappingGeneration++;
      invalidatedDownTime =
          downTime >= 0 ? downTime : (directDownTime >= 0 ? directDownTime : activeDownTime);
    }
    if (active && (downTime < 0 || directDownTime == downTime)) {
      active = false;
    }
  }

  /** Returns the current mapping generation counter. */
  @VisibleForTesting
  public long getMappingGeneration() {
    return mappingGeneration;
  }

  /**
   * Verifies a framework echo of an event that was already delivered directly. If the framework's
   * coordinates no longer agree with ours (the platform view started being transformed during the
   * gesture), the remainder of the gesture falls back to the round trip.
   *
   * <p>Note that once the framework processes {@code directTouchDispatch} and suppresses
   * single-pointer move echoes, this check only runs on any in-flight echoes that were queued
   * before suppression took effect.
   *
   * @return true if direct dispatch was deactivated by this check.
   */
  public boolean verifyDirectlyDispatchedEcho(@NonNull MotionEvent echoedEvent) {
    if (active
        && echoedEvent.getDownTime() == directDownTime
        && !frameworkCoordsMatchOurs(
            echoedEvent.getEventTime(), echoedEvent.getX(), echoedEvent.getY())) {
      active = false;
      if (onDeactivatedListener != null) {
        onDeactivatedListener.onDirectTouchDeactivated(directDownTime);
      }
      return true;
    }
    return false;
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
