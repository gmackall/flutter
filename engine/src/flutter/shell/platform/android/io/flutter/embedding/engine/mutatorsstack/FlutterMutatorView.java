// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine.mutatorsstack;

import static android.view.View.OnFocusChangeListener;
import static io.flutter.Build.API_LEVELS;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import io.flutter.embedding.android.AndroidTouchProcessor;
import io.flutter.util.ViewUtils;

/**
 * A view that applies the {@link io.flutter.embedding.engine.mutatorsstack.FlutterMutatorsStack} to
 * its children.
 */
public class FlutterMutatorView extends FrameLayout {
  private FlutterMutatorsStack mutatorsStack;
  private float screenDensity;
  private int left;
  private int top;

  private final AndroidTouchProcessor androidTouchProcessor;
  private Paint paint;

  // Lazily created RuntimeShader used to apply the Android overscroll stretch
  // effect to the platform view. Only used on API 33+.
  @Nullable private RuntimeShader stretchShader;

  // View types whose content bypasses the view drawing pipeline, so a
  // RenderEffect-based stretch cannot be applied correctly. See
  // applyStretchEffect.
  private static Class[] VIEW_TYPES_REQUIRE_NO_STRETCH = {SurfaceView.class};

  // An AGSL port of the fragment shader the Flutter framework uses to stretch
  // Flutter-rendered content
  // (packages/flutter/lib/src/widgets/shaders/stretch_effect.frag), itself a
  // port of the AOSP overscroll stretch shader:
  // https://cs.android.com/android/platform/superproject/main/+/512046e84bcc51cc241bc6599f83ab345e93ab12:frameworks/base/libs/hwui/effects/StretchEffect.cpp
  //
  // The math must stay in sync with that shader; any curve mismatch shows up
  // as a visible seam at the boundary between the platform view and the
  // surrounding (Flutter-rendered) content during overscroll.
  //
  // Unlike the framework shader, which is evaluated over the full stretched
  // container, this shader is evaluated over the platform view's own sub-span
  // of the container, so the container rect is passed in view-local
  // coordinates via u_container.
  private static final String STRETCH_EFFECT_AGSL =
      "uniform shader u_texture;\n"
          + "// Stretch container rect in view-local pixels (l, t, r, b).\n"
          + "uniform float4 u_container;\n"
          + "// Normalized overscroll amount in the horizontal direction.\n"
          + "uniform float u_overscroll_x;\n"
          + "// Normalized overscroll amount in the vertical direction.\n"
          + "uniform float u_overscroll_y;\n"
          + "// The intensity of the position-based interpolation.\n"
          + "uniform float u_interpolation_strength;\n"
          + "\n"
          + "float easeIn(float t, float d) {\n"
          + "  return t * d;\n"
          + "}\n"
          + "\n"
          + "float computeOverscrollStart(\n"
          + "    float inPos,\n"
          + "    float overscroll,\n"
          + "    float stretchAffectedDist,\n"
          + "    float inverseStretchAffectedDist,\n"
          + "    float distanceStretched,\n"
          + "    float interpolationStrength) {\n"
          + "  float offsetPos = stretchAffectedDist - inPos;\n"
          + "  float posBasedVariation = mix(\n"
          + "      1.0, easeIn(offsetPos, inverseStretchAffectedDist), interpolationStrength);\n"
          + "  float stretchIntensity = overscroll * posBasedVariation;\n"
          + "  return distanceStretched - (offsetPos / (1.0 + stretchIntensity));\n"
          + "}\n"
          + "\n"
          + "float computeOverscrollEnd(\n"
          + "    float inPos,\n"
          + "    float overscroll,\n"
          + "    float reverseStretchDist,\n"
          + "    float stretchAffectedDist,\n"
          + "    float inverseStretchAffectedDist,\n"
          + "    float distanceStretched,\n"
          + "    float interpolationStrength,\n"
          + "    float viewportDimension) {\n"
          + "  float offsetPos = inPos - reverseStretchDist;\n"
          + "  float posBasedVariation = mix(\n"
          + "      1.0, easeIn(offsetPos, inverseStretchAffectedDist), interpolationStrength);\n"
          + "  float stretchIntensity = (-overscroll) * posBasedVariation;\n"
          + "  return viewportDimension\n"
          + "      - (distanceStretched - (offsetPos / (1.0 + stretchIntensity)));\n"
          + "}\n"
          + "\n"
          + "float computeStretchedEffect(\n"
          + "    float inPos,\n"
          + "    float overscroll,\n"
          + "    float stretchAffectedDist,\n"
          + "    float inverseStretchAffectedDist,\n"
          + "    float distanceStretched,\n"
          + "    float distanceDiff,\n"
          + "    float interpolationStrength,\n"
          + "    float viewportDimension) {\n"
          + "  if (overscroll > 0.0) {\n"
          + "    if (inPos <= stretchAffectedDist) {\n"
          + "      return computeOverscrollStart(\n"
          + "          inPos, overscroll, stretchAffectedDist, inverseStretchAffectedDist,\n"
          + "          distanceStretched, interpolationStrength);\n"
          + "    } else {\n"
          + "      return distanceDiff + inPos;\n"
          + "    }\n"
          + "  } else if (overscroll < 0.0) {\n"
          + "    float stretchAffectedDistCalc = viewportDimension - stretchAffectedDist;\n"
          + "    if (inPos >= stretchAffectedDistCalc) {\n"
          + "      return computeOverscrollEnd(\n"
          + "          inPos, overscroll, stretchAffectedDistCalc, stretchAffectedDist,\n"
          + "          inverseStretchAffectedDist, distanceStretched, interpolationStrength,\n"
          + "          viewportDimension);\n"
          + "    } else {\n"
          + "      return -distanceDiff + inPos;\n"
          + "    }\n"
          + "  } else {\n"
          + "    return inPos;\n"
          + "  }\n"
          + "}\n"
          + "\n"
          + "half4 main(float2 fragCoord) {\n"
          + "  float2 containerOrigin = u_container.xy;\n"
          + "  float2 containerSize = u_container.zw - u_container.xy;\n"
          + "  float2 uv = (fragCoord - containerOrigin) / containerSize;\n"
          + "\n"
          + "  bool isVertical = u_overscroll_y != 0.0;\n"
          + "  float overscroll = isVertical ? u_overscroll_y : u_overscroll_x;\n"
          + "\n"
          + "  float normDistanceStretched = 1.0 / (1.0 + abs(overscroll));\n"
          + "  float normDistDiff = normDistanceStretched - 1.0;\n"
          + "\n"
          + "  float outU = uv.x;\n"
          + "  float outV = uv.y;\n"
          + "  if (isVertical) {\n"
          + "    outV = computeStretchedEffect(\n"
          + "        uv.y, overscroll, 1.0, 1.0, normDistanceStretched, normDistDiff,\n"
          + "        u_interpolation_strength, 1.0);\n"
          + "  } else {\n"
          + "    outU = computeStretchedEffect(\n"
          + "        uv.x, overscroll, 1.0, 1.0, normDistanceStretched, normDistDiff,\n"
          + "        u_interpolation_strength, 1.0);\n"
          + "  }\n"
          + "\n"
          + "  float2 src = containerOrigin + float2(outU, outV) * containerSize;\n"
          + "  return u_texture.eval(src);\n"
          + "}\n";

  /**
   * Initialize the FlutterMutatorView. Use this to set the screenDensity, which will be used to
   * correct the final transform matrix.
   */
  public FlutterMutatorView(
      @NonNull Context context,
      float screenDensity,
      @Nullable AndroidTouchProcessor androidTouchProcessor) {
    super(context, null);
    this.screenDensity = screenDensity;
    this.androidTouchProcessor = androidTouchProcessor;
    this.paint = new Paint();
  }

  /** Initialize the FlutterMutatorView. */
  public FlutterMutatorView(@NonNull Context context) {
    this(context, 1, /* androidTouchProcessor=*/ null);
  }

  @Nullable @VisibleForTesting ViewTreeObserver.OnGlobalFocusChangeListener activeFocusListener;

  /**
   * Sets a focus change listener that notifies when the current view or any of its descendant views
   * have received focus.
   *
   * <p>If there's an active focus listener, it will first remove the current listener, and then add
   * the new one.
   *
   * @param userFocusListener A user provided focus listener.
   */
  public void setOnDescendantFocusChangeListener(@NonNull OnFocusChangeListener userFocusListener) {
    unsetOnDescendantFocusChangeListener();

    final View mutatorView = this;
    final ViewTreeObserver observer = getViewTreeObserver();
    if (observer.isAlive() && activeFocusListener == null) {
      activeFocusListener =
          new ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override
            public void onGlobalFocusChanged(View oldFocus, View newFocus) {
              userFocusListener.onFocusChange(mutatorView, ViewUtils.childHasFocus(mutatorView));
            }
          };
      observer.addOnGlobalFocusChangeListener(activeFocusListener);
    }
  }

  /** Unsets any active focus listener. */
  public void unsetOnDescendantFocusChangeListener() {
    final ViewTreeObserver observer = getViewTreeObserver();
    if (observer.isAlive() && activeFocusListener != null) {
      final ViewTreeObserver.OnGlobalFocusChangeListener currFocusListener = activeFocusListener;
      activeFocusListener = null;
      observer.removeOnGlobalFocusChangeListener(currFocusListener);
    }
  }

  /**
   * Pass the necessary parameters to the view so it can apply correct mutations to its children.
   */
  public void readyToDisplay(
      @NonNull FlutterMutatorsStack mutatorsStack, int left, int top, int width, int height) {
    this.mutatorsStack = mutatorsStack;
    this.left = left;
    this.top = top;
    FrameLayout.LayoutParams layoutParams =
        new FrameLayout.LayoutParams(width, height, Gravity.LEFT | Gravity.TOP);
    layoutParams.leftMargin = left;
    layoutParams.topMargin = top;
    setLayoutParams(layoutParams);
    setWillNotDraw(false);
    if (Build.VERSION.SDK_INT >= API_LEVELS.API_33) {
      applyStretchEffect();
    }
  }

  /**
   * Applies (or clears) the Android overscroll stretch effect described by the mutators stack
   * using a {@link RuntimeShader} {@link RenderEffect}.
   *
   * <p>This covers content rendered through the view drawing pipeline. If the platform view
   * contains a {@link SurfaceView}, its content bypasses the drawing pipeline entirely: warping
   * the hwui-rendered content would desynchronize the punched hole from the surface behind it, so
   * the effect is skipped in that case. Stretching SurfaceView content requires compositor
   * support (SurfaceControl.Transaction#setStretchEffect, currently a hidden API).
   *
   * <p>Note that a RenderEffect cannot render outside the view's bounds. Content displaced past
   * the original bounds by a strong stretch is clipped; a follow-up could inflate this view by
   * the maximum displacement to avoid that.
   */
  @RequiresApi(API_LEVELS.API_33)
  private void applyStretchEffect() {
    FlutterMutatorsStack.FlutterStretchEffect stretch =
        mutatorsStack == null ? null : mutatorsStack.getFinalStretchEffect();
    if (stretch == null
        || (stretch.stretchX == 0 && stretch.stretchY == 0)
        || ViewUtils.hasChildViewOfType(this, VIEW_TYPES_REQUIRE_NO_STRETCH)) {
      setRenderEffect(null);
      return;
    }
    if (stretchShader == null) {
      stretchShader = new RuntimeShader(STRETCH_EFFECT_AGSL);
    }
    // The stretch rect is in the same coordinate space as the final clipping
    // paths; reverse this view's final offset to express it in view-local
    // coordinates, matching how the clipping paths are offset in draw().
    stretchShader.setFloatUniform(
        "u_container",
        stretch.rect.left - left,
        stretch.rect.top - top,
        stretch.rect.right - left,
        stretch.rect.bottom - top);
    stretchShader.setFloatUniform("u_overscroll_x", stretch.stretchX);
    stretchShader.setFloatUniform("u_overscroll_y", stretch.stretchY);
    stretchShader.setFloatUniform("u_interpolation_strength", stretch.interpolationStrength);
    setRenderEffect(RenderEffect.createRuntimeShaderEffect(stretchShader, "u_texture"));
  }

  @Override
  public void draw(Canvas canvas) {
    // Apply all clippings on the parent canvas.
    canvas.save();
    for (Path path : mutatorsStack.getFinalClippingPaths()) {
      // Reverse the current offset.
      //
      // The frame of this view includes the final offset of the bounding rect.
      // We need to apply all the mutators to the view, which includes the mutation that leads to
      // the final offset. We should reverse this final offset, both as a translate mutation and to
      // all the clipping paths
      Path pathCopy = new Path(path);
      pathCopy.offset(-left, -top);
      canvas.clipPath(pathCopy);
    }

    int newAlpha = (int) (255 * mutatorsStack.getFinalOpacity());
    boolean shouldApplyOpacity = paint.getAlpha() != newAlpha;
    if (shouldApplyOpacity) {
      paint.setAlpha((int) (255 * mutatorsStack.getFinalOpacity()));
      this.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
    }

    super.draw(canvas);
    canvas.restore();
  }

  @Override
  public void dispatchDraw(Canvas canvas) {
    // Apply all the transforms on the child canvas.
    canvas.save();

    canvas.concat(getPlatformViewMatrix());
    super.dispatchDraw(canvas);
    canvas.restore();
  }

  private Matrix getPlatformViewMatrix() {
    Matrix finalMatrix = new Matrix(mutatorsStack.getFinalMatrix());

    // Reverse scale based on screen scale.
    //
    // The Android frame is set based on the logical resolution instead of physical.
    // (https://developer.android.com/training/multiscreen/screendensities).
    // However, flow is based on the physical resolution. For example, 1000 pixels in flow equals
    // 500 points in Android. And until this point, we did all the calculation based on the flow
    // resolution. So we need to scale down to match Android's logical resolution.
    finalMatrix.preScale(1 / screenDensity, 1 / screenDensity);

    // Reverse the current offset.
    //
    // The frame of this view includes the final offset of the bounding rect.
    // We need to apply all the mutators to the view, which includes the mutation that leads to
    // the final offset. We should reverse this final offset, both as a translate mutation and to
    // all the clipping paths
    finalMatrix.postTranslate(-left, -top);

    return finalMatrix;
  }

  /** Intercept the events here and do not propagate them to the child platform views. */
  @Override
  public boolean onInterceptTouchEvent(MotionEvent event) {
    return true;
  }

  @Override
  public boolean requestSendAccessibilityEvent(View child, AccessibilityEvent event) {
    final View embeddedView = getChildAt(0);
    if (embeddedView != null
        && embeddedView.getImportantForAccessibility()
            == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
      return false;
    }
    // Forward the request only if the embedded view is in the Flutter accessibility tree.
    // The embedded view may be ignored when the framework doesn't populate a SemanticNode
    // for the current platform view.
    // See AccessibilityBridge for more.
    return super.requestSendAccessibilityEvent(child, event);
  }

  @Override
  @SuppressLint("ClickableViewAccessibility")
  public boolean onTouchEvent(MotionEvent event) {
    if (androidTouchProcessor == null) {
      return super.onTouchEvent(event);
    }
    final Matrix screenMatrix = new Matrix();
    screenMatrix.postTranslate(getLeft(), getTop());
    return androidTouchProcessor.onTouchEvent(event, screenMatrix);
  }
}
