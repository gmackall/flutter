// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine.mutatorsstack;

import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The mutator stack containing a list of mutators
 *
 * <p>The mutators can be applied to a {@link io.flutter.plugin.platform.PlatformView} to perform a
 * series mutations. See {@link FlutterMutatorsStack.FlutterMutator} for informations on Mutators.
 */
@Keep
public class FlutterMutatorsStack {
  /**
   * The type of a Mutator See {@link FlutterMutatorsStack.FlutterMutator} for informations on
   * Mutators.
   */
  public enum FlutterMutatorType {
    CLIP_RECT,
    CLIP_RRECT,
    CLIP_PATH,
    TRANSFORM,
    OPACITY,
    STRETCH_EFFECT
  }

  /**
   * The Android overscroll stretch effect to apply to a platform view.
   *
   * <p>The stretch remaps content non-linearly within {@code rect} (the bounds of the stretched
   * container, e.g. a scrollable's viewport) according to the normalized overscroll amounts. The
   * math must stay in sync with the fragment shader the Flutter framework uses to stretch
   * Flutter-rendered content (packages/flutter/lib/src/widgets/shaders/stretch_effect.frag).
   */
  public static class FlutterStretchEffect {
    /** Normalized overscroll amount in the horizontal direction, between -1 and 1. */
    public final float stretchX;

    /** Normalized overscroll amount in the vertical direction, between -1 and 1. */
    public final float stretchY;

    /** The intensity of the position-based interpolation of the stretch curve. */
    public final float interpolationStrength;

    /**
     * The bounds of the stretched container, in the same coordinate space as the final clipping
     * paths (i.e. transformed by the matrix accumulated up to this mutator's stack position).
     */
    @NonNull public final RectF rect;

    public FlutterStretchEffect(
        float stretchX, float stretchY, float interpolationStrength, @NonNull RectF rect) {
      this.stretchX = stretchX;
      this.stretchY = stretchY;
      this.interpolationStrength = interpolationStrength;
      this.rect = rect;
    }
  }

  /**
   * A class represents a mutator
   *
   * <p>A mutator contains information of a single mutation operation that can be applied to a
   * {@link io.flutter.plugin.platform.PlatformView}. See {@link
   * FlutterMutatorsStack.FlutterMutator} for informations on Mutators.
   */
  public class FlutterMutator {

    @Nullable private Matrix matrix;
    @Nullable private Rect rect;
    @Nullable private Path path;
    @Nullable private float[] radiis;
    private float opacity = 1.f;

    private FlutterMutatorType type;

    /**
     * Initialize a clip rect mutator.
     *
     * @param rect the rect to be clipped.
     */
    public FlutterMutator(Rect rect) {
      this.type = FlutterMutatorType.CLIP_RECT;
      this.rect = rect;
    }

    /**
     * Initialize a clip rrect mutator.
     *
     * @param rect the rect of the rrect
     * @param radiis the radiis of the rrect. Array of 8 values, 4 pairs of [X,Y]. This value cannot
     *     be null.
     */
    public FlutterMutator(Rect rect, float[] radiis) {
      this.type = FlutterMutatorType.CLIP_RRECT;
      this.rect = rect;
      this.radiis = radiis;
    }

    /**
     * Initialize a clip path mutator.
     *
     * @param path the path to be clipped.
     */
    public FlutterMutator(Path path) {
      this.type = FlutterMutatorType.CLIP_PATH;
      this.path = path;
    }

    /**
     * Initialize a transform mutator.
     *
     * @param matrix the transform matrix to apply.
     */
    public FlutterMutator(Matrix matrix) {
      this.type = FlutterMutatorType.TRANSFORM;
      this.matrix = matrix;
    }

    /**
     * Initialize an opacity mutator.
     *
     * @param opacity the opacity value to apply. The value must be between 0 and 1, inclusive.
     */
    public FlutterMutator(float opacity) {
      this.type = FlutterMutatorType.OPACITY;
      this.opacity = opacity;
    }

    /**
     * Get the mutator type.
     *
     * @return The type of the mutator.
     */
    public FlutterMutatorType getType() {
      return type;
    }

    /**
     * Get the rect of the mutator if the {@link #getType()} returns FlutterMutatorType.CLIP_RECT.
     *
     * @return the clipping rect if the type is FlutterMutatorType.CLIP_RECT; otherwise null.
     */
    public Rect getRect() {
      return rect;
    }

    /**
     * Get the path of the mutator if the {@link #getType()} returns FlutterMutatorType.CLIP_PATH.
     *
     * @return the clipping path if the type is FlutterMutatorType.CLIP_PATH; otherwise null.
     */
    public Path getPath() {
      return path;
    }

    /**
     * Get the matrix of the mutator if the {@link #getType()} returns FlutterMutatorType.TRANSFORM.
     *
     * @return the matrix if the type is FlutterMutatorType.TRANSFORM; otherwise null.
     */
    public Matrix getMatrix() {
      return matrix;
    }

    /**
     * Get the opacity of the mutator if the {@link #getType()} returns FlutterMutatorType.OPACITY.
     *
     * @return the opacity of the mutator if the type is FlutterMutatorType.OPACITY; otherwise 1.
     */
    public float getOpacity() {
      return opacity;
    }
  }

  private @NonNull List<FlutterMutator> mutators;

  private List<Path> finalClippingPaths;
  private Matrix finalMatrix;
  private float finalOpacity;
  @Nullable private FlutterStretchEffect finalStretchEffect;

  /** Initialize the mutator stack. */
  public FlutterMutatorsStack() {
    this.mutators = new ArrayList<FlutterMutator>();
    finalMatrix = new Matrix();
    finalClippingPaths = new ArrayList<Path>();
    finalOpacity = 1.f;
    finalStretchEffect = null;
  }

  /**
   * Push a transform {@link FlutterMutatorsStack.FlutterMutator} to the stack.
   *
   * @param values the transform matrix to be pushed to the stack. The array matches how a {@link
   *     android.graphics.Matrix} is constructed.
   */
  public void pushTransform(float[] values) {
    Matrix matrix = new Matrix();
    matrix.setValues(values);
    FlutterMutator mutator = new FlutterMutator(matrix);
    mutators.add(mutator);
    finalMatrix.preConcat(mutator.getMatrix());
  }

  /** Push a clipRect {@link FlutterMutatorsStack.FlutterMutator} to the stack. */
  public void pushClipRect(int left, int top, int right, int bottom) {
    Rect rect = new Rect(left, top, right, bottom);
    FlutterMutator mutator = new FlutterMutator(rect);
    mutators.add(mutator);
    Path path = new Path();
    path.addRect(new RectF(rect), Path.Direction.CCW);
    path.transform(finalMatrix);
    finalClippingPaths.add(path);
  }

  /**
   * Push a clipRRect {@link FlutterMutatorsStack.FlutterMutator} to the stack.
   *
   * @param left left offset of the rrect.
   * @param top top offset of the rrect.
   * @param right right position of the rrect.
   * @param bottom bottom position of the rrect.
   * @param radiis the radiis of the rrect. It must be size of 8, including an x and y for each
   *     corner.
   */
  public void pushClipRRect(int left, int top, int right, int bottom, float[] radiis) {
    Rect rect = new Rect(left, top, right, bottom);
    FlutterMutator mutator = new FlutterMutator(rect, radiis);
    mutators.add(mutator);
    Path path = new Path();
    path.addRoundRect(new RectF(rect), radiis, Path.Direction.CCW);
    path.transform(finalMatrix);
    finalClippingPaths.add(path);
  }

  /**
   * Push an opacity {@link FlutterMutatorsStack.FlutterMutator} to the stack.
   *
   * @param opacity the opacity value to be pushed to the stack.
   */
  public void pushOpacity(float opacity) {
    FlutterMutator mutator = new FlutterMutator(opacity);
    mutators.add(mutator);
    finalOpacity *= opacity;
  }

  /**
   * Push a clipPath {@link FlutterMutatorsStack.FlutterMutator} to the stack.
   *
   * @param path the path to be clipped.
   */
  public void pushClipPath(Path path) {
    FlutterMutator mutator = new FlutterMutator(path);
    mutators.add(mutator);
    path.transform(finalMatrix);
    finalClippingPaths.add(path);
  }

  /**
   * Push a stretch effect to the stack.
   *
   * <p>The container rect is transformed with the matrix accumulated up to this stack position,
   * matching how clipping paths are recorded. If multiple stretch effects are pushed (nested
   * stretched scrollables), the innermost one wins.
   *
   * @param stretchX normalized overscroll amount in the horizontal direction, between -1 and 1.
   * @param stretchY normalized overscroll amount in the vertical direction, between -1 and 1.
   * @param interpolationStrength the intensity of the position-based interpolation.
   * @param left left of the stretched container rect.
   * @param top top of the stretched container rect.
   * @param right right of the stretched container rect.
   * @param bottom bottom of the stretched container rect.
   */
  public void pushStretchEffect(
      float stretchX,
      float stretchY,
      float interpolationStrength,
      float left,
      float top,
      float right,
      float bottom) {
    RectF rect = new RectF(left, top, right, bottom);
    finalMatrix.mapRect(rect);
    finalStretchEffect = new FlutterStretchEffect(stretchX, stretchY, interpolationStrength, rect);
  }

  /**
   * Get a list of all the raw mutators. The 0 index of the returned list is the top of the stack.
   */
  public List<FlutterMutator> getMutators() {
    return mutators;
  }

  /**
   * Get a list of all the clipping operations. All the clipping operations -- whether it is clip
   * rect, clip rrect, or clip path -- are converted into Paths. The paths are also transformed with
   * the matrix that up to their stack positions. For example: If the stack looks like (from top to
   * bottom): TransA -&gt; ClipA -&gt; TransB -&gt; ClipB, the final paths will look like
   * [TransA*ClipA, TransA*TransB*ClipB].
   *
   * <p>Clipping this list to the parent canvas of a view results the final clipping path.
   */
  public List<Path> getFinalClippingPaths() {
    return finalClippingPaths;
  }

  /**
   * Returns the final matrix. Apply this matrix to the canvas of a view results the final
   * transformation of the view.
   */
  public Matrix getFinalMatrix() {
    return finalMatrix;
  }

  /**
   * Returns the final opacity. The value must be between 0 and 1, inclusive, or behavior will be
   * undefined.
   */
  public float getFinalOpacity() {
    return finalOpacity;
  }

  /**
   * Returns the final stretch effect to apply to the view, or null if no stretch effect is
   * active. The rect of the returned stretch effect is in the same coordinate space as the final
   * clipping paths.
   */
  @Nullable
  public FlutterStretchEffect getFinalStretchEffect() {
    return finalStretchEffect;
  }
}
