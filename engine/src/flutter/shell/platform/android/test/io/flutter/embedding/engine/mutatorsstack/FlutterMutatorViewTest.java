// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine.mutatorsstack;

import static android.view.View.OnFocusChangeListener;
import static junit.framework.TestCase.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.flutter.embedding.android.AndroidTouchProcessor;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(AndroidJUnit4.class)
public class FlutterMutatorViewTest {
  private final Context ctx = ApplicationProvider.getApplicationContext();

  @Test
  public void canDragViews() {
    final AndroidTouchProcessor touchProcessor = mock(AndroidTouchProcessor.class);
    final FlutterMutatorView view = new FlutterMutatorView(ctx, 1.0f, touchProcessor);
    final FlutterMutatorsStack mutatorStack = mock(FlutterMutatorsStack.class);

    assertTrue(view.onInterceptTouchEvent(mock(MotionEvent.class)));

    {
      view.readyToDisplay(mutatorStack, /*left=*/ 1, /*top=*/ 2, /*width=*/ 0, /*height=*/ 0);
      view.layout(1, 2, 1, 2);
      view.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0.0f, 0.0f, 0));
      final ArgumentCaptor<Matrix> matrixCaptor = ArgumentCaptor.forClass(Matrix.class);
      verify(touchProcessor).onTouchEvent(any(), matrixCaptor.capture());

      final Matrix screenMatrix = new Matrix();
      screenMatrix.postTranslate(1, 2);
      assertEquals(matrixCaptor.getValue(), screenMatrix);
    }

    reset(touchProcessor);

    {
      view.readyToDisplay(mutatorStack, /*left=*/ 3, /*top=*/ 4, /*width=*/ 0, /*height=*/ 0);
      view.layout(3, 4, 3, 4);
      view.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0.0f, 0.0f, 0));
      final ArgumentCaptor<Matrix> matrixCaptor = ArgumentCaptor.forClass(Matrix.class);
      verify(touchProcessor).onTouchEvent(any(), matrixCaptor.capture());

      final Matrix screenMatrix = new Matrix();
      screenMatrix.postTranslate(3, 4);
      assertEquals(matrixCaptor.getValue(), screenMatrix);
    }

    reset(touchProcessor);

    {
      view.readyToDisplay(mutatorStack, /*left=*/ 5, /*top=*/ 6, /*width=*/ 0, /*height=*/ 0);
      view.layout(5, 6, 5, 6);
      view.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0.0f, 0.0f, 0));
      final ArgumentCaptor<Matrix> matrixCaptor = ArgumentCaptor.forClass(Matrix.class);
      verify(touchProcessor).onTouchEvent(any(), matrixCaptor.capture());

      final Matrix screenMatrix = new Matrix();
      screenMatrix.postTranslate(5, 6);
      assertEquals(matrixCaptor.getValue(), screenMatrix);
    }

    reset(touchProcessor);

    {
      view.readyToDisplay(mutatorStack, /*left=*/ 7, /*top=*/ 8, /*width=*/ 0, /*height=*/ 0);
      view.layout(7, 8, 7, 8);
      view.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0.0f, 0.0f, 0));
      final ArgumentCaptor<Matrix> matrixCaptor = ArgumentCaptor.forClass(Matrix.class);
      verify(touchProcessor).onTouchEvent(any(), matrixCaptor.capture());

      final Matrix screenMatrix = new Matrix();
      screenMatrix.postTranslate(7, 8);
      assertEquals(matrixCaptor.getValue(), screenMatrix);
    }
  }

  @Test
  public void focusChangeListener_hasFocus() {
    final ViewTreeObserver viewTreeObserver = mock(ViewTreeObserver.class);
    when(viewTreeObserver.isAlive()).thenReturn(true);

    final FlutterMutatorView view =
        new FlutterMutatorView(ctx) {
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

    final FlutterMutatorView view =
        new FlutterMutatorView(ctx) {
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
    final FlutterMutatorView view =
        new FlutterMutatorView(ctx) {
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

    final FlutterMutatorView view =
        new FlutterMutatorView(ctx) {
          @Override
          public ViewTreeObserver getViewTreeObserver() {
            return viewTreeObserver;
          }
        };

    assertNull(view.activeFocusListener);

    view.setOnDescendantFocusChangeListener(mock(OnFocusChangeListener.class));
    assertNotNull(view.activeFocusListener);

    final ViewTreeObserver.OnGlobalFocusChangeListener activeFocusListener =
        view.activeFocusListener;

    view.setOnDescendantFocusChangeListener(mock(OnFocusChangeListener.class));
    assertNotNull(view.activeFocusListener);

    verify(viewTreeObserver, times(1)).removeOnGlobalFocusChangeListener(activeFocusListener);
  }

  @Test
  public void unsetOnDescendantFocusChangeListener_removesActiveListener() {
    final ViewTreeObserver viewTreeObserver = mock(ViewTreeObserver.class);
    when(viewTreeObserver.isAlive()).thenReturn(true);

    final FlutterMutatorView view =
        new FlutterMutatorView(ctx) {
          @Override
          public ViewTreeObserver getViewTreeObserver() {
            return viewTreeObserver;
          }
        };

    assertNull(view.activeFocusListener);

    view.setOnDescendantFocusChangeListener(mock(OnFocusChangeListener.class));
    assertNotNull(view.activeFocusListener);

    final ViewTreeObserver.OnGlobalFocusChangeListener activeFocusListener =
        view.activeFocusListener;

    view.unsetOnDescendantFocusChangeListener();
    assertNull(view.activeFocusListener);

    view.unsetOnDescendantFocusChangeListener();
    verify(viewTreeObserver, times(1)).removeOnGlobalFocusChangeListener(activeFocusListener);
  }

  @Test
  @Config(
      shadows = {
        ShadowViewGroup.class,
      })
  public void ignoreAccessibilityEvents() {
    final FlutterMutatorView wrapperView = new FlutterMutatorView(ctx);

    final View embeddedView = mock(View.class);
    wrapperView.addView(embeddedView);

    when(embeddedView.getImportantForAccessibility())
        .thenReturn(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    final boolean eventSent =
        wrapperView.requestSendAccessibilityEvent(embeddedView, mock(AccessibilityEvent.class));
    assertFalse(eventSent);
  }

  @Test
  public void onTouchEvent_delegatesToGestureTrackerAndRequestsUnbufferedOnMove() {
    final AndroidTouchProcessor touchProcessor = mock(AndroidTouchProcessor.class);
    final MotionEvent[] lastRequestedEvent = new MotionEvent[1];
    final FlutterMutatorView view =
        new FlutterMutatorView(ctx, 1.0f, touchProcessor) {
          @Override
          void requestUnbuffered(MotionEvent event) {
            lastRequestedEvent[0] = event;
          }
        };

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

  @Test
  public void readyToDisplay_invalidatesOnMutatorOrPositionChangeAndSkipsWhenUnchanged() {
    final int[] layoutParamsCount = new int[1];
    final int[] invalidateCount = new int[1];
    final FlutterMutatorView view =
        new FlutterMutatorView(ctx) {
          @Override
          public void setLayoutParams(ViewGroup.LayoutParams params) {
            super.setLayoutParams(params);
            layoutParamsCount[0]++;
          }

          @Override
          public void invalidate() {
            super.invalidate();
            invalidateCount[0]++;
          }
        };

    final FlutterMutatorsStack stack1 = new FlutterMutatorsStack();
    stack1.pushTransform(new float[] {1, 0, 10.2f, 0, 1, 20.3f, 0, 0, 1});
    stack1.pushClipRect(10, 20, 110, 220);

    view.readyToDisplay(stack1, /*left=*/ 10, /*top=*/ 20, /*width=*/ 100, /*height=*/ 200);
    assertEquals(1, layoutParamsCount[0]);
    assertEquals(1, invalidateCount[0]);

    // Identical bounds and equal mutator stack: neither layout nor invalidate should run.
    final FlutterMutatorsStack stack1Copy = new FlutterMutatorsStack();
    stack1Copy.pushTransform(new float[] {1, 0, 10.2f, 0, 1, 20.3f, 0, 0, 1});
    stack1Copy.pushClipRect(10, 20, 110, 220);
    view.readyToDisplay(stack1Copy, /*left=*/ 10, /*top=*/ 20, /*width=*/ 100, /*height=*/ 200);
    assertEquals(1, layoutParamsCount[0]);
    assertEquals(1, invalidateCount[0]);

    // Sub-pixel scroll where rounded integer bounds stay the same: only invalidate should run.
    final FlutterMutatorsStack stack2 = new FlutterMutatorsStack();
    stack2.pushTransform(new float[] {1, 0, 10.2f, 0, 1, 20.4f, 0, 0, 1});
    stack2.pushClipRect(10, 20, 110, 220);
    view.readyToDisplay(stack2, /*left=*/ 10, /*top=*/ 20, /*width=*/ 100, /*height=*/ 200);
    assertEquals(1, layoutParamsCount[0]);
    assertEquals(2, invalidateCount[0]);

    // Scroll step where integer top changes while width/height stay constant: both must run
    // because View.setFrame with unchanged size does not invalidate the RenderNode display list.
    final FlutterMutatorsStack stack3 = new FlutterMutatorsStack();
    stack3.pushTransform(new float[] {1, 0, 10.2f, 0, 1, 21.1f, 0, 0, 1});
    stack3.pushClipRect(10, 21, 110, 221);
    view.readyToDisplay(stack3, /*left=*/ 10, /*top=*/ 21, /*width=*/ 100, /*height=*/ 200);
    assertEquals(2, layoutParamsCount[0]);
    assertEquals(3, invalidateCount[0]);
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void drawAndDispatchDraw_eliminateSubpixelTranslationResidualDuringScroll() {
    final FlutterMutatorView view = new FlutterMutatorView(ctx, /*screenDensity=*/ 2.625f, null);

    final float[] scrollOffsets = new float[] {100.375f, 100.625f};
    for (float yOffset : scrollOffsets) {
      final FlutterMutatorsStack stack = new FlutterMutatorsStack();
      // Scale by screenDensity (2.625) and translate to fractional physical coordinates.
      stack.pushTransform(new float[] {2.625f, 0, 45.9375f, 0, 2.625f, yOffset, 0, 0, 1});
      // Push a local clip matching the 107x100 logical size of the platform view.
      stack.pushClipRect(0, 0, 107.0f, 100.0f);

      final int left = Math.round(45.9375f);
      final int top = Math.round(yOffset);
      view.readyToDisplay(stack, left, top, /*width=*/ 281, /*height=*/ 263);

      final Canvas mockCanvas = mock(Canvas.class);
      view.draw(mockCanvas);

      final ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
      verify(mockCanvas).clipPath(pathCaptor.capture());
      final RectF clipBounds = new RectF();
      pathCaptor.getValue().computeBounds(clipBounds, true);
      assertEquals(0.0f, clipBounds.left, 1e-4f);
      assertEquals(0.0f, clipBounds.top, 1e-4f);
      assertEquals(107.0f * 2.625f, clipBounds.right, 1e-4f);
      assertEquals(100.0f * 2.625f, clipBounds.bottom, 1e-4f);

      final ArgumentCaptor<Matrix> matrixCaptor = ArgumentCaptor.forClass(Matrix.class);
      verify(mockCanvas).concat(matrixCaptor.capture());
      final Matrix expectedIdentity = new Matrix();
      assertEquals(expectedIdentity, matrixCaptor.getValue());
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void draw_offsetsStationaryAncestorClipByViewOriginWhileKeepingLocalClipAtZero() {
    final FlutterMutatorView view = new FlutterMutatorView(ctx, /*screenDensity=*/ 2.625f, null);

    // Simulate a stationary scroll-viewport clip pushed before the scroll offset transform,
    // followed by a card-local clip pushed after the scroll offset transform.
    final FlutterMutatorsStack stack = new FlutterMutatorsStack();
    // Root DPR scale + stationary header offset (y = 131.25px -> snaps to 131px).
    stack.pushTransform(new float[] {2.625f, 0, 0, 0, 2.625f, 131.25f, 0, 0, 1});
    stack.pushClipRect(0, 0, 400.0f, 800.0f);
    // Scroll translation moving the card to (x = 45.9375px -> 46px, y = 131.25 + 262.5 = 393.75px
    // -> 394px).
    stack.pushTransform(new float[] {1, 0, 17.5f, 0, 1, 100.0f, 0, 0, 1});
    stack.pushClipRect(0, 0, 107.0f, 100.0f);

    final int left = Math.round(45.9375f);
    final int top = Math.round(393.75f);
    view.readyToDisplay(stack, left, top, /*width=*/ 281, /*height=*/ 263);

    final List<Path> clippedPaths = new ArrayList<>();
    final Canvas mockCanvas = mock(Canvas.class);
    doAnswer(
            invocation -> {
              clippedPaths.add(new Path((Path) invocation.getArgument(0)));
              return true;
            })
        .when(mockCanvas)
        .clipPath(any(Path.class));
    view.draw(mockCanvas);

    assertEquals(2, clippedPaths.size());

    // First clip: stationary ancestor viewport clip shifted into view-local space by (-left, -top).
    final RectF viewportClipBounds = new RectF();
    clippedPaths.get(0).computeBounds(viewportClipBounds, true);
    assertEquals(-left, viewportClipBounds.left, 1e-4f);
    assertEquals(131.0f - top, viewportClipBounds.top, 1e-4f);
    assertEquals(400.0f * 2.625f - left, viewportClipBounds.right, 1e-4f);
    assertEquals(131.0f + 800.0f * 2.625f - top, viewportClipBounds.bottom, 1e-4f);

    // Second clip: moving card-local clip stays anchored at (0, 0) in view-local space.
    final RectF localClipBounds = new RectF();
    clippedPaths.get(1).computeBounds(localClipBounds, true);
    assertEquals(0.0f, localClipBounds.left, 1e-4f);
    assertEquals(0.0f, localClipBounds.top, 1e-4f);
    assertEquals(107.0f * 2.625f, localClipBounds.right, 1e-4f);
    assertEquals(100.0f * 2.625f, localClipBounds.bottom, 1e-4f);
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
