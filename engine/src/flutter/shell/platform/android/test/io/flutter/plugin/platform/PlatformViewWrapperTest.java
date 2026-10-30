// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.platform;

import static io.flutter.Build.API_LEVELS;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.spy;
import static org.robolectric.Shadows.shadowOf;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.flutter.embedding.android.AndroidTouchProcessor;
import io.flutter.embedding.engine.renderer.FlutterRenderer;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@TargetApi(API_LEVELS.API_31)
@RunWith(AndroidJUnit4.class)
public class PlatformViewWrapperTest {
  private final Context ctx = ApplicationProvider.getApplicationContext();

  @Before
  public void setUp() {
    // Most tests exercise the Surface#lockHardwareCanvas() path with a mocked Surface.
    PlatformViewWrapper.useDedicatedHardwareRenderer = false;
  }

  @After
  public void tearDown() {
    PlatformViewWrapper.useDedicatedHardwareRenderer = android.os.Build.VERSION.SDK_INT >= 29;
  }

  @Test
  public void draw_withDedicatedHardwareRenderer_doesNotLockSurfaceCanvas() {
    PlatformViewWrapper.useDedicatedHardwareRenderer = true;
    FlutterRenderer.debugDisableSurfaceClear = true;
    final Surface surface = mock(Surface.class);
    when(surface.isValid()).thenReturn(true);
    final PlatformViewRenderTarget renderTarget = mock(PlatformViewRenderTarget.class);
    when(renderTarget.getSurface()).thenReturn(surface);
    when(renderTarget.getWidth()).thenReturn(10);
    when(renderTarget.getHeight()).thenReturn(10);
    final PlatformViewWrapper wrapper = new PlatformViewWrapper(ctx, renderTarget);

    wrapper.draw(mock(Canvas.class));

    // The subtree is recorded into the wrapper's own RenderNode, never into a Surface canvas.
    verify(surface, never()).lockHardwareCanvas();
    verify(renderTarget, never()).scheduleFrame();

    shadowOf(Looper.getMainLooper()).idle();

    verify(surface, never()).unlockCanvasAndPost(any());
    verify(renderTarget, times(1)).scheduleFrame();
    wrapper.release();
  }

  @Test
  public void getFrameTimeNanos_fallsBackToNowWhenDrawingTimeIsStale() {
    final PlatformViewWrapper wrapper = spy(new PlatformViewWrapper(ctx));
    final long nowMs = System.nanoTime() / 1_000_000L;

    when(wrapper.getDrawingTime()).thenReturn(nowMs - 4);
    assertEquals((nowMs - 4) * 1_000_000L, wrapper.getFrameTimeNanos());

    // Stale (not inside a traversal) or bogus drawing times fall back to the current time.
    when(wrapper.getDrawingTime()).thenReturn(nowMs - 10_000);
    long before = System.nanoTime();
    long frameTime = wrapper.getFrameTimeNanos();
    assertTrue(frameTime >= before && frameTime <= System.nanoTime());

    when(wrapper.getDrawingTime()).thenReturn(0L);
    before = System.nanoTime();
    frameTime = wrapper.getFrameTimeNanos();
    assertTrue(frameTime >= before && frameTime <= System.nanoTime());
  }

  @Test
  public void invalidateChildInParent_callsInvalidate() {
    final PlatformViewWrapper wrapper = spy(new PlatformViewWrapper(ctx));

    // Mock Android framework calls.
    wrapper.invalidateChildInParent(null, null);

    // Verify.
    verify(wrapper, times(1)).invalidate();
  }

  @Test
  public void draw_withoutSurface() {
    final PlatformViewWrapper wrapper =
        new PlatformViewWrapper(ctx) {
          @Override
          public void onDraw(Canvas canvas) {
            canvas.drawColor(Color.RED);
          }
        };
    // Test.
    final Canvas canvas = mock(Canvas.class);
    wrapper.draw(canvas);

    // Verify.
    verify(canvas, times(1)).drawColor(Color.RED);
  }

  @Test
  public void draw_withoutValidSurface() {
    FlutterRenderer.debugDisableSurfaceClear = true;
    final Surface surface = mock(Surface.class);
    when(surface.isValid()).thenReturn(false);
    final PlatformViewRenderTarget renderTarget = mock(PlatformViewRenderTarget.class);
    when(renderTarget.getSurface()).thenReturn(surface);

    final PlatformViewWrapper wrapper = new PlatformViewWrapper(ctx, renderTarget);
    final Canvas canvas = mock(Canvas.class);
    wrapper.draw(canvas);

    verify(canvas, times(0)).drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
  }

  @Test
  public void draw_withValidSurface() {
    FlutterRenderer.debugDisableSurfaceClear = true;
    final Canvas canvas = mock(Canvas.class);
    final Surface surface = mock(Surface.class);
    when(surface.isValid()).thenReturn(true);
    final PlatformViewRenderTarget renderTarget = mock(PlatformViewRenderTarget.class);
    when(renderTarget.getSurface()).thenReturn(surface);
    when(surface.lockHardwareCanvas()).thenReturn(canvas);
    final PlatformViewWrapper wrapper = new PlatformViewWrapper(ctx, renderTarget);

    wrapper.draw(canvas);

    verify(canvas, times(1)).drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
  }

  @Test
  public void draw_withValidSurface_postsFrameAfterCurrentTraversal() {
    FlutterRenderer.debugDisableSurfaceClear = true;
    final Canvas canvas = mock(Canvas.class);
    final Surface surface = mock(Surface.class);
    when(surface.isValid()).thenReturn(true);
    final PlatformViewRenderTarget renderTarget = mock(PlatformViewRenderTarget.class);
    when(renderTarget.getSurface()).thenReturn(surface);
    when(surface.lockHardwareCanvas()).thenReturn(canvas);
    final PlatformViewWrapper wrapper = new PlatformViewWrapper(ctx, renderTarget);

    wrapper.draw(canvas);

    // The frame is recorded synchronously, but posted to the render target only after the
    // current traversal.
    verify(surface, times(1)).lockHardwareCanvas();
    verify(surface, never()).unlockCanvasAndPost(any());
    verify(renderTarget, never()).scheduleFrame();

    shadowOf(Looper.getMainLooper()).idle();

    verify(surface, times(1)).unlockCanvasAndPost(canvas);
    verify(renderTarget, times(1)).scheduleFrame();
  }

  @Test
  public void draw_twiceInOneTraversal_postsPreviousFrameBeforeLockingAgain() {
    FlutterRenderer.debugDisableSurfaceClear = true;
    final Canvas canvas = mock(Canvas.class);
    final Surface surface = mock(Surface.class);
    when(surface.isValid()).thenReturn(true);
    final PlatformViewRenderTarget renderTarget = mock(PlatformViewRenderTarget.class);
    when(renderTarget.getSurface()).thenReturn(surface);
    when(surface.lockHardwareCanvas()).thenReturn(canvas);
    final PlatformViewWrapper wrapper = new PlatformViewWrapper(ctx, renderTarget);

    wrapper.draw(canvas);
    wrapper.draw(canvas);

    verify(surface, times(2)).lockHardwareCanvas();
    verify(surface, times(1)).unlockCanvasAndPost(canvas);

    shadowOf(Looper.getMainLooper()).idle();

    verify(surface, times(2)).unlockCanvasAndPost(canvas);
    verify(renderTarget, times(2)).scheduleFrame();
  }

  @Test
  public void release_postsPendingFrame() {
    FlutterRenderer.debugDisableSurfaceClear = true;
    final Canvas canvas = mock(Canvas.class);
    final Surface surface = mock(Surface.class);
    when(surface.isValid()).thenReturn(true);
    final PlatformViewRenderTarget renderTarget = mock(PlatformViewRenderTarget.class);
    when(renderTarget.getSurface()).thenReturn(surface);
    when(surface.lockHardwareCanvas()).thenReturn(canvas);
    final PlatformViewWrapper wrapper = new PlatformViewWrapper(ctx, renderTarget);

    wrapper.draw(canvas);
    wrapper.release();

    verify(surface, times(1)).unlockCanvasAndPost(canvas);
    verify(renderTarget, times(1)).release();

    // Nothing is posted again after release.
    shadowOf(Looper.getMainLooper()).idle();
    verify(surface, times(1)).unlockCanvasAndPost(canvas);
  }

  @Test
  public void focusChangeListener_hasFocus() {
    final ViewTreeObserver viewTreeObserver = mock(ViewTreeObserver.class);
    when(viewTreeObserver.isAlive()).thenReturn(true);

    final PlatformViewWrapper view =
        new PlatformViewWrapper(ctx) {
          @Override
          public ViewTreeObserver getViewTreeObserver() {
            return viewTreeObserver;
          }

          @Override
          public boolean hasFocus() {
            return true;
          }
        };

    final OnFocusChangeListener focusListener = mock(OnFocusChangeListener.class);
    view.setOnDescendantFocusChangeListener(focusListener);

    final ArgumentCaptor<ViewTreeObserver.OnGlobalFocusChangeListener> focusListenerCaptor =
        ArgumentCaptor.forClass(ViewTreeObserver.OnGlobalFocusChangeListener.class);
    verify(viewTreeObserver).addOnGlobalFocusChangeListener(focusListenerCaptor.capture());

    focusListenerCaptor.getValue().onGlobalFocusChanged(null, null);
    verify(focusListener).onFocusChange(view, true);
  }

  @Test
  public void focusChangeListener_doesNotHaveFocus() {
    final ViewTreeObserver viewTreeObserver = mock(ViewTreeObserver.class);
    when(viewTreeObserver.isAlive()).thenReturn(true);

    final PlatformViewWrapper view =
        new PlatformViewWrapper(ctx) {
          @Override
          public ViewTreeObserver getViewTreeObserver() {
            return viewTreeObserver;
          }

          @Override
          public boolean hasFocus() {
            return false;
          }
        };

    final OnFocusChangeListener focusListener = mock(OnFocusChangeListener.class);
    view.setOnDescendantFocusChangeListener(focusListener);

    final ArgumentCaptor<ViewTreeObserver.OnGlobalFocusChangeListener> focusListenerCaptor =
        ArgumentCaptor.forClass(ViewTreeObserver.OnGlobalFocusChangeListener.class);
    verify(viewTreeObserver).addOnGlobalFocusChangeListener(focusListenerCaptor.capture());

    focusListenerCaptor.getValue().onGlobalFocusChanged(null, null);
    verify(focusListener).onFocusChange(view, false);
  }

  @Test
  public void focusChangeListener_viewTreeObserverIsAliveFalseDoesNotThrow() {
    final PlatformViewWrapper view =
        new PlatformViewWrapper(ctx) {
          @Override
          public ViewTreeObserver getViewTreeObserver() {
            final ViewTreeObserver viewTreeObserver = mock(ViewTreeObserver.class);
            when(viewTreeObserver.isAlive()).thenReturn(false);
            return viewTreeObserver;
          }
        };
    view.setOnDescendantFocusChangeListener(mock(OnFocusChangeListener.class));
  }

  @Test
  public void setOnDescendantFocusChangeListener_keepsSingleListener() {
    final ViewTreeObserver viewTreeObserver = mock(ViewTreeObserver.class);
    when(viewTreeObserver.isAlive()).thenReturn(true);

    final PlatformViewWrapper view =
        new PlatformViewWrapper(ctx) {
          @Override
          public ViewTreeObserver getViewTreeObserver() {
            return viewTreeObserver;
          }
        };

    assertNull(view.getActiveFocusListener());

    view.setOnDescendantFocusChangeListener(mock(OnFocusChangeListener.class));
    assertNotNull(view.getActiveFocusListener());

    final ViewTreeObserver.OnGlobalFocusChangeListener activeFocusListener =
        view.getActiveFocusListener();

    view.setOnDescendantFocusChangeListener(mock(OnFocusChangeListener.class));
    assertNotNull(view.getActiveFocusListener());

    verify(viewTreeObserver, times(1)).removeOnGlobalFocusChangeListener(activeFocusListener);
  }

  @Test
  public void unsetOnDescendantFocusChangeListener_removesActiveListener() {
    final ViewTreeObserver viewTreeObserver = mock(ViewTreeObserver.class);
    when(viewTreeObserver.isAlive()).thenReturn(true);

    final PlatformViewWrapper view =
        new PlatformViewWrapper(ctx) {
          @Override
          public ViewTreeObserver getViewTreeObserver() {
            return viewTreeObserver;
          }
        };

    assertNull(view.getActiveFocusListener());

    view.setOnDescendantFocusChangeListener(mock(OnFocusChangeListener.class));
    assertNotNull(view.getActiveFocusListener());

    final ViewTreeObserver.OnGlobalFocusChangeListener activeFocusListener =
        view.getActiveFocusListener();

    view.unsetOnDescendantFocusChangeListener();
    assertNull(view.getActiveFocusListener());

    view.unsetOnDescendantFocusChangeListener();
    verify(viewTreeObserver, times(1)).removeOnGlobalFocusChangeListener(activeFocusListener);
  }

  @Test
  @Config(
      shadows = {
        ShadowFrameLayout.class,
        ShadowViewGroup.class,
      })
  public void ignoreAccessibilityEvents() {
    final PlatformViewWrapper wrapperView = new PlatformViewWrapper(ctx);

    final View embeddedView = mock(View.class);
    wrapperView.addView(embeddedView);

    when(embeddedView.getImportantForAccessibility())
        .thenReturn(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    final boolean eventSent =
        wrapperView.requestSendAccessibilityEvent(embeddedView, mock(AccessibilityEvent.class));
    assertFalse(eventSent);
  }

  @Test
  @Config(
      shadows = {
        ShadowFrameLayout.class,
        ShadowViewGroup.class,
      })
  public void sendAccessibilityEvents() {
    final PlatformViewWrapper wrapperView = new PlatformViewWrapper(ctx);

    final View embeddedView = mock(View.class);
    wrapperView.addView(embeddedView);

    when(embeddedView.getImportantForAccessibility())
        .thenReturn(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    boolean eventSent =
        wrapperView.requestSendAccessibilityEvent(embeddedView, mock(AccessibilityEvent.class));
    assertTrue(eventSent);

    when(embeddedView.getImportantForAccessibility())
        .thenReturn(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    eventSent =
        wrapperView.requestSendAccessibilityEvent(embeddedView, mock(AccessibilityEvent.class));
    assertTrue(eventSent);
  }

  @Test
  public void onTouchEvent_delegatesToGestureTrackerAndRequestsUnbufferedOnMove() {
    final AndroidTouchProcessor touchProcessor = mock(AndroidTouchProcessor.class);
    final MotionEvent[] lastRequestedEvent = new MotionEvent[1];
    final PlatformViewWrapper view =
        new PlatformViewWrapper(ctx) {
          @Override
          void requestUnbuffered(MotionEvent event) {
            lastRequestedEvent[0] = event;
          }
        };
    view.setTouchProcessor(touchProcessor);

    // Before Flutter wins the arena: touches are buffered.
    final MotionEvent downEvent =
        MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 0.0f, 0.0f, 0);
    view.onTouchEvent(downEvent);
    assertTrue(view.isGestureActive());
    assertEquals(100, view.getCurrentDownTime());
    assertNull(lastRequestedEvent[0]);

    // Matching gestureId sets flutterWonGesture.
    view.onFlutterWonGesture(100);
    assertTrue(view.getFlutterWonGesture());

    // Subsequent move event triggers unbuffered dispatch.
    final MotionEvent moveEvent =
        MotionEvent.obtain(100, 101, MotionEvent.ACTION_MOVE, 10.0f, 10.0f, 0);
    view.onTouchEvent(moveEvent);
    assertEquals(moveEvent, lastRequestedEvent[0]);
    assertFalse(view.getFlutterWonGesture());
  }

  /** Adds {@code wrapper} to a laid out parent at the given position. */
  private FrameLayout layOutInParent(PlatformViewWrapper wrapper, int left, int top) {
    final FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(10, 10, Gravity.LEFT | Gravity.TOP);
    params.leftMargin = left;
    params.topMargin = top;
    wrapper.setLayoutParams(params);
    final FrameLayout parent = new FrameLayout(ctx);
    parent.addView(wrapper);
    parent.measure(
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
    parent.layout(0, 0, 100, 100);
    return parent;
  }

  @Test
  public void setOffset_movesViewWithoutRequestingLayout() {
    final int[] scrollChangedCalls = new int[1];
    final PlatformViewWrapper wrapper =
        new PlatformViewWrapper(ctx) {
          @Override
          protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            scrollChangedCalls[0]++;
          }
        };
    final FrameLayout parent = layOutInParent(wrapper, 1, 2);
    assertEquals(1, wrapper.getLeft());
    assertEquals(2, wrapper.getTop());
    assertFalse(wrapper.isLayoutRequested());

    wrapper.setOffset(30, 40);

    // The view is moved immediately, without a layout pass, and scroll listeners are notified.
    assertFalse(wrapper.isLayoutRequested());
    assertFalse(parent.isLayoutRequested());
    assertEquals(30, wrapper.getLeft());
    assertEquals(40, wrapper.getTop());
    assertEquals(40, wrapper.getRight());
    assertEquals(50, wrapper.getBottom());
    assertEquals(1, scrollChangedCalls[0]);

    // Setting the same offset again is a no-op.
    wrapper.setOffset(30, 40);
    assertEquals(1, scrollChangedCalls[0]);

    // The layout params are kept in sync, so a later layout pass keeps the position.
    final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) wrapper.getLayoutParams();
    assertEquals(30, params.leftMargin);
    assertEquals(40, params.topMargin);
    parent.requestLayout();
    parent.measure(
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
    parent.layout(0, 0, 100, 100);
    assertEquals(30, wrapper.getLeft());
    assertEquals(40, wrapper.getTop());
  }

  @Test
  public void setOffset_beforeLayout_onlyUpdatesLayoutParams() {
    final PlatformViewWrapper wrapper = new PlatformViewWrapper(ctx);
    wrapper.setLayoutParams(new FrameLayout.LayoutParams(10, 10));

    wrapper.setOffset(30, 40);

    final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) wrapper.getLayoutParams();
    assertEquals(30, params.leftMargin);
    assertEquals(40, params.topMargin);
    assertEquals(Gravity.LEFT | Gravity.TOP, params.gravity);
    assertEquals(0, wrapper.getLeft());
    assertEquals(0, wrapper.getTop());
  }

  @Test
  public void setOffset_whileLayoutRequested_stillMovesImmediately() {
    final PlatformViewWrapper wrapper = new PlatformViewWrapper(ctx);
    layOutInParent(wrapper, 1, 2);
    wrapper.requestLayout();
    assertTrue(wrapper.isLayoutRequested());

    wrapper.setOffset(30, 40);

    assertEquals(30, wrapper.getLeft());
    assertEquals(40, wrapper.getTop());
  }

  @Test
  public void onTouchEvent_translatesByCurrentPosition() {
    final AndroidTouchProcessor touchProcessor = mock(AndroidTouchProcessor.class);
    final PlatformViewWrapper wrapper = new PlatformViewWrapper(ctx);
    wrapper.setTouchProcessor(touchProcessor);
    layOutInParent(wrapper, 5, 6);
    final ArgumentCaptor<Matrix> matrixCaptor = ArgumentCaptor.forClass(Matrix.class);

    final MotionEvent downEvent =
        MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 1.0f, 1.0f, 0);
    wrapper.onTouchEvent(downEvent);

    // The view moves (e.g. the list scrolls) in the middle of the gesture.
    wrapper.setOffset(15, 26);
    final MotionEvent moveEvent =
        MotionEvent.obtain(100, 101, MotionEvent.ACTION_MOVE, 2.0f, 2.0f, 0);
    wrapper.onTouchEvent(moveEvent);

    verify(touchProcessor, times(2))
        .onTouchEvent(any(MotionEvent.class), matrixCaptor.capture(), anyBoolean());
    final List<Matrix> matrices = matrixCaptor.getAllValues();
    final float[] values = new float[9];
    matrices.get(0).getValues(values);
    assertEquals(5f, values[Matrix.MTRANS_X], 0f);
    assertEquals(6f, values[Matrix.MTRANS_Y], 0f);
    matrices.get(1).getValues(values);
    assertEquals(15f, values[Matrix.MTRANS_X], 0f);
    assertEquals(26f, values[Matrix.MTRANS_Y], 0f);
  }

  /** Child view that records the MotionEvents it receives. */
  private static class RecordingView extends View {
    final java.util.ArrayList<MotionEvent> events = new java.util.ArrayList<>();

    RecordingView(Context context) {
      super(context);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
      events.add(MotionEvent.obtain(event));
      return true;
    }
  }

  private static MotionEvent move(long downTime, long eventTime) {
    return MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, 10.0f, 10.0f, 0);
  }

  @Test
  public void directDispatch_deliversRemainderOfGestureSynchronouslyAfterPlatformViewWins() {
    final AndroidTouchProcessor touchProcessor = mock(AndroidTouchProcessor.class);
    final PlatformViewWrapper wrapper = new PlatformViewWrapper(ctx);
    final RecordingView child = new RecordingView(ctx);
    wrapper.addView(child);
    wrapper.setTouchProcessor(touchProcessor);

    final MotionEvent down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 0f, 0f, 0);
    wrapper.onTouchEvent(down);
    // Arena unresolved: nothing is delivered directly, the event goes to the framework only.
    assertFalse(wrapper.isDirectDispatchActive());
    assertEquals(0, child.events.size());
    assertFalse(wrapper.isDirectlyDispatched(down));

    // A move that is still in flight through the framework.
    final MotionEvent move1 = move(100, 101);
    wrapper.onTouchEvent(move1);
    assertEquals(0, child.events.size());

    // The framework forwarded the DOWN: the platform view won the arena.
    wrapper.onPlatformViewWonGesture(down);
    assertTrue(wrapper.isDirectDispatchActive());
    // The in-flight move's echo has not been superseded yet, so it must still be delivered.
    assertFalse(wrapper.isDirectlyDispatched(move1));

    // Subsequent events are delivered directly, and still forwarded to the framework.
    final MotionEvent move2 = move(100, 102);
    wrapper.onTouchEvent(move2);
    assertEquals(1, child.events.size());
    assertEquals(102, child.events.get(0).getEventTime());
    // Events that went through the round trip are tracked; the directly delivered one is not.
    verify(touchProcessor, times(2))
        .onTouchEvent(any(MotionEvent.class), any(Matrix.class), eq(true));
    verify(touchProcessor, times(1)).onTouchEvent(eq(move2), any(Matrix.class), eq(false));

    // Echoes of events older than (or equal to) the newest direct delivery are dropped.
    assertTrue(wrapper.isDirectlyDispatched(move1));
    assertTrue(wrapper.isDirectlyDispatched(move2));
    assertFalse(wrapper.isDirectlyDispatched(move(100, 103)));
    // Echoes belonging to another gesture are never dropped.
    assertFalse(wrapper.isDirectlyDispatched(move(200, 102)));

    // UP ends direct dispatch, but is itself delivered directly.
    final MotionEvent up = MotionEvent.obtain(100, 110, MotionEvent.ACTION_UP, 10f, 10f, 0);
    wrapper.onTouchEvent(up);
    assertEquals(2, child.events.size());
    assertEquals(MotionEvent.ACTION_UP, child.events.get(1).getActionMasked());
    assertFalse(wrapper.isDirectDispatchActive());
    assertTrue(wrapper.isDirectlyDispatched(move(100, 105)));

    // A new gesture starts buffered again.
    final MotionEvent down2 = MotionEvent.obtain(200, 200, MotionEvent.ACTION_DOWN, 0f, 0f, 0);
    wrapper.onTouchEvent(down2);
    wrapper.onTouchEvent(move(200, 201));
    assertEquals(2, child.events.size());
    assertFalse(wrapper.isDirectlyDispatched(move(100, 105)));
  }

  @Test
  public void directDispatch_ignoresWinForOtherOrFinishedGesture() {
    final AndroidTouchProcessor touchProcessor = mock(AndroidTouchProcessor.class);
    final PlatformViewWrapper wrapper = new PlatformViewWrapper(ctx);
    final RecordingView child = new RecordingView(ctx);
    wrapper.addView(child);
    wrapper.setTouchProcessor(touchProcessor);

    final MotionEvent down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 0f, 0f, 0);
    wrapper.onTouchEvent(down);
    // Stale win for a previous gesture.
    wrapper.onPlatformViewWonGesture(
        MotionEvent.obtain(50, 50, MotionEvent.ACTION_DOWN, 0f, 0f, 0));
    assertFalse(wrapper.isDirectDispatchActive());
    wrapper.onTouchEvent(move(100, 101));
    assertEquals(0, child.events.size());

    // Win arriving after the gesture ended.
    wrapper.onTouchEvent(MotionEvent.obtain(100, 110, MotionEvent.ACTION_UP, 0f, 0f, 0));
    wrapper.onPlatformViewWonGesture(down);
    assertFalse(wrapper.isDirectDispatchActive());
  }

  @Test
  public void directDispatch_handsMultiTouchBackToTheFramework() {
    final AndroidTouchProcessor touchProcessor = mock(AndroidTouchProcessor.class);
    final PlatformViewWrapper wrapper = new PlatformViewWrapper(ctx);
    final RecordingView child = new RecordingView(ctx);
    wrapper.addView(child);
    wrapper.setTouchProcessor(touchProcessor);

    final MotionEvent down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 0f, 0f, 0);
    wrapper.onTouchEvent(down);
    wrapper.onPlatformViewWonGesture(down);
    wrapper.onTouchEvent(move(100, 101));
    assertEquals(1, child.events.size());

    final MotionEvent.PointerProperties[] props = {
      new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties()
    };
    props[0].id = 0;
    props[1].id = 1;
    final MotionEvent.PointerCoords[] coords = {
      new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords()
    };
    final MotionEvent pointerDown =
        MotionEvent.obtain(
            100,
            102,
            MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            0,
            0);
    wrapper.onTouchEvent(pointerDown);
    // Not delivered directly; the framework round trip owns the rest of the gesture.
    assertEquals(1, child.events.size());
    assertFalse(wrapper.isDirectDispatchActive());
    assertFalse(wrapper.isDirectlyDispatched(pointerDown));
    // ...but the echo of the move that was already delivered directly is still dropped.
    assertTrue(wrapper.isDirectlyDispatched(move(100, 101)));
    // A later win notification for the same gesture does not re-enable direct dispatch.
    wrapper.onPlatformViewWonGesture(down);
    assertFalse(wrapper.isDirectDispatchActive());
  }

  @Test
  public void directDispatch_staysOffWhenFrameworkCoordinatesDiffer() {
    // A Flutter ancestor scales the platform view: the framework's PV-local coordinates (what the
    // round trip delivers) are not the wrapper-local coordinates of the raw event.
    final AndroidTouchProcessor touchProcessor = mock(AndroidTouchProcessor.class);
    final PlatformViewWrapper wrapper = new PlatformViewWrapper(ctx);
    final RecordingView child = new RecordingView(ctx);
    wrapper.addView(child);
    wrapper.setTouchProcessor(touchProcessor);

    final MotionEvent down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 10f, 20f, 0);
    wrapper.onTouchEvent(down);
    final MotionEvent scaledEcho =
        MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 20f, 40f, 0);
    wrapper.onPlatformViewWonGesture(scaledEcho);
    assertFalse(wrapper.isDirectDispatchActive());
    wrapper.onTouchEvent(MotionEvent.obtain(100, 101, MotionEvent.ACTION_MOVE, 11f, 21f, 0));
    assertEquals(0, child.events.size());

    // Sub-pixel differences (density rounding) are tolerated.
    final MotionEvent down2 = MotionEvent.obtain(200, 200, MotionEvent.ACTION_DOWN, 10f, 20f, 0);
    wrapper.onTouchEvent(down2);
    wrapper.onPlatformViewWonGesture(
        MotionEvent.obtain(200, 200, MotionEvent.ACTION_DOWN, 10.6f, 19.5f, 0));
    assertTrue(wrapper.isDirectDispatchActive());
    final MotionEvent move = MotionEvent.obtain(200, 201, MotionEvent.ACTION_MOVE, 11f, 21f, 0);
    wrapper.onTouchEvent(move);
    assertEquals(1, child.events.size());

    // If the transform changes mid-gesture, the echo of a directly delivered event no longer
    // matches and the remainder of the gesture falls back to the round trip.
    wrapper.verifyDirectlyDispatchedEcho(
        MotionEvent.obtain(200, 201, MotionEvent.ACTION_MOVE, 22f, 42f, 0));
    assertFalse(wrapper.isDirectDispatchActive());
    wrapper.onTouchEvent(MotionEvent.obtain(200, 202, MotionEvent.ACTION_MOVE, 12f, 22f, 0));
    assertEquals(1, child.events.size());
    // ...while echoes of events that were already delivered directly are still dropped.
    assertTrue(wrapper.isDirectlyDispatched(move));
  }

  @Test
  public void directDispatch_canBeDisabled() {
    final AndroidTouchProcessor touchProcessor = mock(AndroidTouchProcessor.class);
    final PlatformViewWrapper wrapper = new PlatformViewWrapper(ctx);
    final RecordingView child = new RecordingView(ctx);
    wrapper.addView(child);
    wrapper.setTouchProcessor(touchProcessor);
    PlatformViewDirectTouchDispatcher.enabled = false;
    try {
      final MotionEvent down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 0f, 0f, 0);
      wrapper.onTouchEvent(down);
      wrapper.onPlatformViewWonGesture(down);
      wrapper.onTouchEvent(move(100, 101));
      assertFalse(wrapper.isDirectDispatchActive());
      assertEquals(0, child.events.size());
    } finally {
      PlatformViewDirectTouchDispatcher.enabled = true;
    }
  }

  @Test
  public void pipelineAdpfController_manages120HzTargetFourPipelineThreadsAndLifecycle() {
    final PlatformViewWrapper.PipelineAdpfController controller =
        new PlatformViewWrapper.PipelineAdpfController();
    final java.util.ArrayList<int[]> createdTids = new java.util.ArrayList<>();
    final java.util.ArrayList<Long> targets = new java.util.ArrayList<>();
    final java.util.ArrayList<int[]> updatedTids = new java.util.ArrayList<>();
    final java.util.ArrayList<Long> reportedDurations = new java.util.ArrayList<>();
    final int[] closedCount = {0};
    final int[] affinityCalls = {0};
    final float[] fps = {120.0f};
    final int[] readerTid = {3003};
    final int[] rasterTid = {4004};
    final boolean[] allowSetThreads = {true};

    controller.setBridgeForTest(
        new PlatformViewWrapper.AdpfHintBridge() {
          @Override
          public PlatformViewWrapper.AdpfSessionHandle createSession(
              Context context, int[] tids, long initialTargetWorkDurationNanos) {
            createdTids.add(tids.clone());
            targets.add(initialTargetWorkDurationNanos);
            return new PlatformViewWrapper.AdpfSessionHandle() {
              @Override
              public void updateTargetWorkDuration(long targetDurationNanos) {
                targets.add(targetDurationNanos);
              }

              @Override
              public boolean setThreads(int[] newTids) {
                if (!allowSetThreads[0]) {
                  return false;
                }
                updatedTids.add(newTids.clone());
                return true;
              }

              @Override
              public void reportActualWorkDuration(long actualDurationNanos) {
                reportedDurations.add(actualDurationNanos);
              }

              @Override
              public void close() {
                closedCount[0]++;
              }
            };
          }

          @Override
          public float getRefreshRateFPS() {
            return fps[0];
          }

          @Override
          public int getSharedImageReaderTid() {
            return readerTid[0];
          }

          @Override
          public int getLastRasterThreadTid() {
            return rasterTid[0];
          }

          @Override
          public boolean requestNotEfficiencyCpuAffinity() {
            affinityCalls[0]++;
            return true;
          }
        });

    controller.onRendererCreated();
    controller.prepareForFrameOnMainThread(ctx, API_LEVELS.API_34);

    // Initial session at 120 Hz targets 5.0 ms (60% of 8.33 ms) with [mainTid, readerTid,
    // rasterTid].
    assertEquals(1, createdTids.size());
    assertEquals(3, createdTids.get(0).length);
    assertEquals(3003, createdTids.get(0)[1]);
    assertEquals(4004, createdTids.get(0)[2]);
    assertEquals(Long.valueOf(5_000_000L), targets.get(0));

    // First RenderThread commit records renderThreadTid=2002, applies kNotEfficiency affinity once,
    // and reports Stage-1 work duration.
    final long vsync1 = 1_000_000_000L;
    controller.onFrameCommittedOnRenderThread(vsync1, vsync1 + 4_200_000L, 2002);
    assertEquals(1, affinityCalls[0]);
    assertEquals(1, reportedDurations.size());
    assertEquals(Long.valueOf(4_200_000L), reportedDurations.get(0));

    // Next main-thread prepare adds renderThreadTid=2002 via setThreads -> all 4 pipeline threads!
    controller.prepareForFrameOnMainThread(ctx, API_LEVELS.API_34);
    assertEquals(1, updatedTids.size());
    assertEquals(4, updatedTids.get(0).length);
    assertEquals(2002, updatedTids.get(0)[1]);
    assertEquals(3003, updatedTids.get(0)[2]);
    assertEquals(4004, updatedTids.get(0)[3]);

    // Multi-PV same-vsync: tiny +0.2 ms sub-target commit is coalesced, while a second PV pushing
    // batch work to 6.1 ms (> 5.0 ms target) is reported immediately; affinity is not re-requested.
    final long vsync2 = 1_008_333_333L;
    controller.onFrameCommittedOnRenderThread(vsync2, vsync2 + 3_500_000L, 2002);
    controller.onFrameCommittedOnRenderThread(vsync2, vsync2 + 3_700_000L, 2002);
    controller.onFrameCommittedOnRenderThread(vsync2, vsync2 + 6_100_000L, 2002);
    assertEquals(1, affinityCalls[0]);
    assertEquals(3, reportedDurations.size());
    assertEquals(Long.valueOf(3_500_000L), reportedDurations.get(1));
    assertEquals(Long.valueOf(6_100_000L), reportedDurations.get(2));

    // Switching display mode to 60 Hz updates target duration to 10.0 ms; API 31-33 fallback
    // recreates session cleanly if setThreads returns false.
    fps[0] = 60.0f;
    allowSetThreads[0] = false;
    rasterTid[0] = 5005;
    controller.prepareForFrameOnMainThread(ctx, API_LEVELS.API_31);
    assertEquals(1, closedCount[0]);
    assertEquals(2, createdTids.size());
    assertEquals(5005, createdTids.get(1)[3]);
    assertEquals(Long.valueOf(10_000_000L), targets.get(targets.size() - 1));

    // Destroying the last renderer closes the session cleanly.
    controller.onRendererDestroyed();
    assertEquals(2, closedCount[0]);
  }

  @Implements(ViewGroup.class)
  public static class ShadowViewGroup extends org.robolectric.shadows.ShadowViewGroup {
    @Implementation
    protected boolean requestSendAccessibilityEvent(View child, AccessibilityEvent event) {
      return true;
    }
  }

  @Implements(FrameLayout.class)
  public static class ShadowFrameLayout
      extends io.flutter.plugin.platform.PlatformViewWrapperTest.ShadowViewGroup {}
}
