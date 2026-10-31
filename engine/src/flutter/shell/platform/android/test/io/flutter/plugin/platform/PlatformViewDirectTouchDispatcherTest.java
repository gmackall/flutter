// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PlatformViewDirectTouchDispatcherTest {

  private static MotionEvent multiPointerEvent(
      long downTime, long eventTime, int action, int pointerCount) {
    final MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[pointerCount];
    final MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointerCount];
    for (int i = 0; i < pointerCount; i++) {
      props[i] = new MotionEvent.PointerProperties();
      props[i].id = i;
      coords[i] = new MotionEvent.PointerCoords();
      coords[i].x = 10f + i;
      coords[i].y = 20f + i;
    }
    return MotionEvent.obtain(
        downTime, eventTime, action, pointerCount, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0);
  }

  @Test
  public void coordinateTolerance_activatesAtOrBelowTwoPixelsAndRejectsAbove() {
    final PlatformViewDirectTouchDispatcher dispatcher = new PlatformViewDirectTouchDispatcher();
    final View view = mock(View.class);

    // 2.01 px difference in X -> rejected.
    final MotionEvent down1 = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 10f, 20f, 0);
    dispatcher.onTouchEvent(down1, view);
    final MotionEvent echoAbove =
        MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 12.01f, 20f, 0);
    assertFalse(dispatcher.onPlatformViewWonGesture(true, 100, echoAbove));
    assertFalse(dispatcher.isActive());

    // Exactly 2.0 px difference in both X and Y -> accepted, and only activates once per downTime.
    final MotionEvent down2 = MotionEvent.obtain(200, 200, MotionEvent.ACTION_DOWN, 10f, 20f, 0);
    dispatcher.onTouchEvent(down2, view);
    final MotionEvent echoAtBoundary =
        MotionEvent.obtain(200, 200, MotionEvent.ACTION_DOWN, 12.0f, 18.0f, 0);
    assertTrue(dispatcher.onPlatformViewWonGesture(true, 200, echoAtBoundary));
    assertTrue(dispatcher.isActive());
    assertFalse(dispatcher.onPlatformViewWonGesture(true, 200, echoAtBoundary));
  }

  @Test
  public void actionUpAndActionCancel_deliverDirectlyAndDeactivateWithoutListenerCallback() {
    final PlatformViewDirectTouchDispatcher dispatcher = new PlatformViewDirectTouchDispatcher();
    final View view = mock(View.class);
    final List<Long> deactivated = new ArrayList<>();
    dispatcher.setOnDirectTouchDeactivatedListener(deactivated::add);

    // Gesture 1 ends with ACTION_UP.
    final MotionEvent down1 = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 5f, 5f, 0);
    dispatcher.onTouchEvent(down1, view);
    assertTrue(dispatcher.onPlatformViewWonGesture(true, 100, down1));
    final MotionEvent up1 = MotionEvent.obtain(100, 105, MotionEvent.ACTION_UP, 6f, 6f, 0);
    assertTrue(dispatcher.onTouchEvent(up1, view));
    verify(view, times(1)).dispatchTouchEvent(up1);
    assertFalse(dispatcher.isActive());
    assertTrue(dispatcher.isDirectlyDispatched(up1));
    assertTrue(deactivated.isEmpty());

    // Gesture 2 ends with ACTION_CANCEL.
    final MotionEvent down2 = MotionEvent.obtain(200, 200, MotionEvent.ACTION_DOWN, 5f, 5f, 0);
    dispatcher.onTouchEvent(down2, view);
    assertTrue(dispatcher.onPlatformViewWonGesture(true, 200, down2));
    final MotionEvent cancel2 = MotionEvent.obtain(200, 205, MotionEvent.ACTION_CANCEL, 6f, 6f, 0);
    assertTrue(dispatcher.onTouchEvent(cancel2, view));
    verify(view, times(1)).dispatchTouchEvent(cancel2);
    assertFalse(dispatcher.isActive());
    assertTrue(dispatcher.isDirectlyDispatched(cancel2));
    assertTrue(deactivated.isEmpty());
  }

  @Test
  public void multiTouch_deactivatesAndNotifiesListener_andDoesNotDropSameMsMultiTouchEchoes() {
    final PlatformViewDirectTouchDispatcher dispatcher = new PlatformViewDirectTouchDispatcher();
    final View view = mock(View.class);
    final List<Long> deactivated = new ArrayList<>();
    dispatcher.setOnDirectTouchDeactivatedListener(deactivated::add);

    final MotionEvent down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 10f, 20f, 0);
    dispatcher.onTouchEvent(down, view);
    assertTrue(dispatcher.onPlatformViewWonGesture(true, 100, down));

    // Deliver a single-pointer move at t = 105 ms.
    final MotionEvent move1 = MotionEvent.obtain(100, 105, MotionEvent.ACTION_MOVE, 11f, 21f, 0);
    assertTrue(dispatcher.onTouchEvent(move1, view));
    assertEquals(0, deactivated.size());

    // Second finger goes down within the same millisecond (t = 105 ms).
    final int pointerDownAction =
        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    final MotionEvent pointerDown = multiPointerEvent(100, 105, pointerDownAction, 2);
    assertFalse(dispatcher.onTouchEvent(pointerDown, view));
    assertFalse(dispatcher.isActive());
    assertEquals(1, deactivated.size());
    assertEquals(100L, (long) deactivated.get(0));

    // The single-pointer move at t = 105 was delivered directly and its echo is dropped.
    assertTrue(dispatcher.isDirectlyDispatched(move1));
    // Multi-pointer events and ACTION_DOWN at t <= 105 were never delivered directly and must NOT
    // be dropped even though eventTime == lastDirectEventTime.
    assertFalse(dispatcher.isDirectlyDispatched(down));
    assertFalse(dispatcher.isDirectlyDispatched(pointerDown));
    assertFalse(
        dispatcher.isDirectlyDispatched(multiPointerEvent(100, 105, MotionEvent.ACTION_MOVE, 2)));
    final int pointerUpAction =
        MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    assertFalse(dispatcher.isDirectlyDispatched(multiPointerEvent(100, 105, pointerUpAction, 2)));
  }

  @Test
  public void verifyDirectlyDispatchedEcho_deactivatesAndNotifiesListenerOnCoordinateMismatch() {
    final PlatformViewDirectTouchDispatcher dispatcher = new PlatformViewDirectTouchDispatcher();
    final View view = mock(View.class);
    final List<Long> deactivated = new ArrayList<>();
    dispatcher.setOnDirectTouchDeactivatedListener(deactivated::add);

    final MotionEvent down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 10f, 20f, 0);
    dispatcher.onTouchEvent(down, view);
    assertTrue(dispatcher.onPlatformViewWonGesture(true, 100, down));

    final MotionEvent move1 = MotionEvent.obtain(100, 101, MotionEvent.ACTION_MOVE, 12f, 22f, 0);
    assertTrue(dispatcher.onTouchEvent(move1, view));

    // Matching echo within 2.0 px keeps direct dispatch active.
    assertFalse(
        dispatcher.verifyDirectlyDispatchedEcho(
            MotionEvent.obtain(100, 101, MotionEvent.ACTION_MOVE, 13.5f, 20.5f, 0)));
    assertTrue(dispatcher.isActive());
    assertTrue(deactivated.isEmpty());

    // Diverging echo (> 2.0 px) deactivates direct dispatch and notifies the listener once.
    assertTrue(
        dispatcher.verifyDirectlyDispatchedEcho(
            MotionEvent.obtain(100, 101, MotionEvent.ACTION_MOVE, 30f, 40f, 0)));
    assertFalse(dispatcher.isActive());
    assertEquals(1, deactivated.size());
    assertEquals(100L, (long) deactivated.get(0));
  }

  @Test
  public void recentEventsRingBuffer_wrapsAroundCapacity() {
    final PlatformViewDirectTouchDispatcher dispatcher = new PlatformViewDirectTouchDispatcher();
    final View view = mock(View.class);

    final MotionEvent down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 1f, 1f, 0);
    dispatcher.onTouchEvent(down, view);
    assertTrue(dispatcher.onPlatformViewWonGesture(true, 100, down));

    for (int i = 1; i <= 70; i++) {
      final MotionEvent move =
          MotionEvent.obtain(100, 100 + i, MotionEvent.ACTION_MOVE, 1f + i, 2f + i, 0);
      assertTrue(dispatcher.onTouchEvent(move, view));
    }
    // The 70th move (eventTime = 170) is in the ring buffer and matches its coordinates.
    assertFalse(
        dispatcher.verifyDirectlyDispatchedEcho(
            MotionEvent.obtain(100, 170, MotionEvent.ACTION_MOVE, 71f, 72f, 0)));
    assertTrue(dispatcher.isActive());
  }

  @Test
  public void
      scaledOrRotatedView_rejectedAtOriginAndDeactivatesImmediatelyWhenTransformChangesMidGesture() {
    final PlatformViewDirectTouchDispatcher dispatcher = new PlatformViewDirectTouchDispatcher();
    final List<Long> deactivated = new ArrayList<>();
    dispatcher.setOnDirectTouchDeactivatedListener(deactivated::add);

    // 1. A scaled view touched at the origin (0, 0) produces an echo at (0, 0) whose single-point
    // distance is 0.0 px, yet must be rejected because the view hierarchy has non-unit scale.
    final FrameLayout container = new FrameLayout(ApplicationProvider.getApplicationContext());
    final View childView = new View(ApplicationProvider.getApplicationContext());
    container.addView(childView);
    container.setScaleX(2.0f);

    final MotionEvent downAtOrigin =
        MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 0f, 0f, 0);
    dispatcher.onTouchEvent(downAtOrigin, childView);
    assertFalse(dispatcher.onPlatformViewWonGesture(true, 100, downAtOrigin, childView));
    assertFalse(dispatcher.isActive());
    assertFalse(PlatformViewDirectTouchDispatcher.isTranslationOnlyTransform(childView));

    // 2. Restore translation-only hierarchy, activate direct dispatch, then apply rotation
    // mid-gesture: the next native touch event must NOT be dispatched directly and must deactivate
    // direct dispatch and notify the listener.
    container.setScaleX(1.0f);
    assertTrue(PlatformViewDirectTouchDispatcher.isTranslationOnlyTransform(childView));

    final MotionEvent down = MotionEvent.obtain(200, 200, MotionEvent.ACTION_DOWN, 10f, 20f, 0);
    dispatcher.onTouchEvent(down, childView);
    assertTrue(dispatcher.onPlatformViewWonGesture(true, 200, down, childView));
    assertTrue(dispatcher.isActive());

    final MotionEvent move1 = MotionEvent.obtain(200, 205, MotionEvent.ACTION_MOVE, 12f, 22f, 0);
    assertTrue(dispatcher.onTouchEvent(move1, childView));
    assertTrue(dispatcher.isActive());
    assertTrue(deactivated.isEmpty());

    // Apply rotation to the ancestor container mid-gesture before move2 arrives.
    container.setRotation(15.0f);
    final MotionEvent move2 = MotionEvent.obtain(200, 210, MotionEvent.ACTION_MOVE, 14f, 24f, 0);
    assertFalse(dispatcher.onTouchEvent(move2, childView));
    assertFalse(dispatcher.isActive());
    assertEquals(1, deactivated.size());
    assertEquals(200L, (long) deactivated.get(0));
    // move1 was dispatched directly before deactivation; move2 was not.
    assertTrue(dispatcher.isDirectlyDispatched(move1));
    assertFalse(dispatcher.isDirectlyDispatched(move2));
  }

  @Test
  public void completedGestureEchoes_remainSuppressedAfterNextGestureActionDown() {
    final PlatformViewDirectTouchDispatcher dispatcher = new PlatformViewDirectTouchDispatcher();
    final View view = mock(View.class);

    // Gesture A (downTime = 100) wins the arena and dispatches moveA (t=105) and upA (t=110)
    // directly.
    final MotionEvent downA = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 5f, 5f, 0);
    dispatcher.onTouchEvent(downA, view);
    assertTrue(dispatcher.onPlatformViewWonGesture(true, 100, downA, view));

    final MotionEvent moveA = MotionEvent.obtain(100, 105, MotionEvent.ACTION_MOVE, 6f, 6f, 0);
    assertTrue(dispatcher.onTouchEvent(moveA, view));
    final MotionEvent upA = MotionEvent.obtain(100, 110, MotionEvent.ACTION_UP, 6f, 6f, 0);
    assertTrue(dispatcher.onTouchEvent(upA, view));
    assertFalse(dispatcher.isActive());

    // Gesture B (downTime = 200) starts natively BEFORE gesture A's delayed framework echoes
    // arrive on the platform channel.
    final MotionEvent downB = MotionEvent.obtain(200, 200, MotionEvent.ACTION_DOWN, 15f, 15f, 0);
    assertFalse(dispatcher.onTouchEvent(downB, view));

    // Stale echoes from gesture A must still be recognized as directly dispatched and suppressed.
    assertTrue(dispatcher.isDirectlyDispatched(moveA));
    assertTrue(dispatcher.isDirectlyDispatched(upA));
    assertFalse(dispatcher.isDirectlyDispatched(downA));
    assertFalse(dispatcher.isDirectlyDispatched(downB));
  }

  @Test
  public void
      invalidateDirectTouch_deactivatesWithoutCancelIncrementsGenerationAndBlocksStaleReactivation() {
    final PlatformViewDirectTouchDispatcher dispatcher = new PlatformViewDirectTouchDispatcher();
    final List<MotionEvent> received = new ArrayList<>();
    final View view =
        new View(ApplicationProvider.getApplicationContext()) {
          @Override
          public boolean dispatchTouchEvent(MotionEvent event) {
            received.add(MotionEvent.obtain(event));
            return true;
          }
        };
    final List<Long> deactivated = new ArrayList<>();
    dispatcher.setOnDirectTouchDeactivatedListener(deactivated::add);

    final long initialGeneration = dispatcher.getMappingGeneration();
    final MotionEvent down1 = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 10f, 20f, 0);
    dispatcher.onTouchEvent(down1, view);
    assertTrue(dispatcher.onPlatformViewWonGesture(true, 100, down1, view));
    assertTrue(dispatcher.isActive());

    final MotionEvent move1 = MotionEvent.obtain(100, 105, MotionEvent.ACTION_MOVE, 12f, 22f, 0);
    assertTrue(dispatcher.onTouchEvent(move1, view));
    assertEquals(1, received.size());
    assertEquals(MotionEvent.ACTION_MOVE, received.get(0).getActionMasked());

    // Framework detects ancestor Transform change mid-gesture and proactively invalidates.
    dispatcher.invalidateDirectTouch(100L);
    assertFalse(dispatcher.isActive());
    assertEquals(initialGeneration + 1, dispatcher.getMappingGeneration());
    // No synthetic ACTION_CANCEL is injected and no redundant listener echo is fired.
    assertEquals(1, received.size());
    assertTrue(deactivated.isEmpty());

    // Stale reactivation attempts for the invalidated gesture are blocked.
    assertFalse(dispatcher.onPlatformViewWonGesture(true, 100, down1, view));
    assertFalse(dispatcher.isActive());

    // Subsequent native MOVE is NOT dispatched directly; prior move1 remains deduplicated while
    // move2 is allowed through when its Dart-converted live-coordinate echo arrives.
    final MotionEvent move2 = MotionEvent.obtain(100, 110, MotionEvent.ACTION_MOVE, 24f, 44f, 0);
    assertFalse(dispatcher.onTouchEvent(move2, view));
    assertEquals(1, received.size());
    assertTrue(dispatcher.isDirectlyDispatched(move1));
    assertFalse(dispatcher.isDirectlyDispatched(move2));

    // A subsequent fresh ACTION_DOWN gesture clears the prior gesture's invalidation cleanly.
    final MotionEvent down2 = MotionEvent.obtain(200, 200, MotionEvent.ACTION_DOWN, 8f, 16f, 0);
    assertFalse(dispatcher.onTouchEvent(down2, view));
    assertTrue(dispatcher.onPlatformViewWonGesture(true, 200, down2, view));
    assertTrue(dispatcher.isActive());

    // A stale invalidateDirectTouch carrying an earlier gesture's downTime does not bump
    // mappingGeneration or disable direct touch for a newly started gesture.
    final MotionEvent down3 = MotionEvent.obtain(300, 300, MotionEvent.ACTION_DOWN, 5f, 10f, 0);
    assertFalse(dispatcher.onTouchEvent(down3, view));
    final long genAfterDown3 = dispatcher.getMappingGeneration();
    dispatcher.invalidateDirectTouch(100L);
    assertEquals(genAfterDown3, dispatcher.getMappingGeneration());
    assertTrue(dispatcher.onPlatformViewWonGesture(true, 300, down3, view));
    assertTrue(dispatcher.isActive());

    // Conversely, a FIFO invalidateDirectTouch carrying the CURRENT gesture's downTime arriving
    // right before the down touch echo prevents onPlatformViewWonGesture from activating even at
    // (0, 0) where coordinate distance is 0.0 px.
    final MotionEvent down4 = MotionEvent.obtain(400, 400, MotionEvent.ACTION_DOWN, 0f, 0f, 0);
    assertFalse(dispatcher.onTouchEvent(down4, view));
    dispatcher.invalidateDirectTouch(400L);
    assertFalse(dispatcher.onPlatformViewWonGesture(true, 400, down4, view));
    assertFalse(dispatcher.isActive());
  }
}
