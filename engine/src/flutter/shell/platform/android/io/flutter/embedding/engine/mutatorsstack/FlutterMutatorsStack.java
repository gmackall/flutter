// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine.mutatorsstack;

import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RectF;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    RUNTIME_SHADER_FILTER
  }

  /**
   * A runtime effect ({@code ui.ImageFilter.shader}) image filter, translated by the engine into an
   * AGSL program that can be applied to a platform view as a {@link android.graphics.RenderEffect}.
   *
   * <p>Programs are registered once by the engine (see {@link #registerRuntimeShaderProgram}) and
   * then referenced by their id from {@link RuntimeShaderFilter}s.
   */
  static final class RuntimeShaderProgram {
    final long id;
    @NonNull final String agslSource;
    @NonNull final String inputShaderName;
    @NonNull final String[] uniformNames;
    @NonNull final int[] uniformSizes;

    RuntimeShaderProgram(
        long id,
        @NonNull String agslSource,
        @NonNull String inputShaderName,
        @NonNull String[] uniformNames,
        @NonNull int[] uniformSizes) {
      this.id = id;
      this.agslSource = agslSource;
      this.inputShaderName = inputShaderName;
      this.uniformNames = uniformNames;
      this.uniformSizes = uniformSizes;
    }
  }

  /**
   * A runtime shader filter that applies to a platform view.
   *
   * <p>{@code uniformValues} contains the values of all of the program's uniforms, concatenated in
   * the order of {@link RuntimeShaderProgram#uniformNames}.
   *
   * <p>The frame ({@code left}, {@code top}, {@code right}, {@code bottom}) is the rect of the
   * filter input in physical pixels, in the coordinate space of the Flutter view. The shader's
   * coordinates are relative to the top left corner of the frame, so the platform view has to be
   * laid out with the frame as its bounds for the filter to line up with the Flutter content that
   * the same filter is applied to.
   */
  static final class RuntimeShaderFilter {
    final long programId;
    @NonNull final float[] uniformValues;
    final int left;
    final int top;
    final int right;
    final int bottom;

    RuntimeShaderFilter(
        long programId, @NonNull float[] uniformValues, int left, int top, int right, int bottom) {
      this.programId = programId;
      this.uniformValues = uniformValues;
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
    }

    int getWidth() {
      return right - left;
    }

    int getHeight() {
      return bottom - top;
    }

    boolean isEmpty() {
      return right <= left || bottom <= top;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof RuntimeShaderFilter)) {
        return false;
      }
      RuntimeShaderFilter filter = (RuntimeShaderFilter) other;
      return programId == filter.programId
          && left == filter.left
          && top == filter.top
          && right == filter.right
          && bottom == filter.bottom
          && Arrays.equals(uniformValues, filter.uniformValues);
    }

    @Override
    public int hashCode() {
      int result = Long.hashCode(programId);
      result = 31 * result + left;
      result = 31 * result + top;
      result = 31 * result + right;
      result = 31 * result + bottom;
      result = 31 * result + Arrays.hashCode(uniformValues);
      return result;
    }
  }

  private static final Map<Long, RuntimeShaderProgram> runtimeShaderPrograms =
      new ConcurrentHashMap<>();

  /**
   * Registers an AGSL program that {@link #pushRuntimeShaderFilter} can refer to by {@code id}.
   *
   * <p>Called by the engine the first time a program is used.
   *
   * @param id the process-wide unique id of the program.
   * @param agslSource the AGSL source of the program.
   * @param inputShaderName the name of the {@code uniform shader} that receives the filter input.
   * @param uniformNames the names of the float uniforms that are set for every frame.
   * @param uniformSizes the number of floats of each of the uniforms in {@code uniformNames}.
   */
  @Keep
  public static void registerRuntimeShaderProgram(
      long id,
      @NonNull String agslSource,
      @NonNull String inputShaderName,
      @NonNull String[] uniformNames,
      @NonNull int[] uniformSizes) {
    runtimeShaderPrograms.put(
        id, new RuntimeShaderProgram(id, agslSource, inputShaderName, uniformNames, uniformSizes));
  }

  /** Returns the program registered with {@code id}, or null if there is none. */
  @Nullable
  static RuntimeShaderProgram getRuntimeShaderProgram(long id) {
    return runtimeShaderPrograms.get(id);
  }

  @VisibleForTesting
  static void clearRuntimeShaderProgramsForTesting() {
    runtimeShaderPrograms.clear();
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
    @Nullable private RectF rect;
    @Nullable private Path path;
    @Nullable private float[] radiis;
    private float opacity = 1.f;
    @Nullable private RuntimeShaderFilter runtimeShaderFilter;

    private FlutterMutatorType type;

    /**
     * Initialize a clip rect mutator.
     *
     * @param rect the rect to be clipped.
     */
    public FlutterMutator(RectF rect) {
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
    public FlutterMutator(RectF rect, float[] radiis) {
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
     * Initialize a runtime shader filter mutator.
     *
     * @param filter the filter to apply.
     */
    FlutterMutator(@NonNull RuntimeShaderFilter filter) {
      this.type = FlutterMutatorType.RUNTIME_SHADER_FILTER;
      this.runtimeShaderFilter = filter;
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
    public RectF getRect() {
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

    /**
     * Get the runtime shader filter of the mutator if the {@link #getType()} returns {@link
     * FlutterMutatorType#RUNTIME_SHADER_FILTER}.
     *
     * @return the filter if the type is {@link FlutterMutatorType#RUNTIME_SHADER_FILTER}; otherwise
     *     null.
     */
    @Nullable
    RuntimeShaderFilter getRuntimeShaderFilter() {
      return runtimeShaderFilter;
    }
  }

  private @NonNull List<FlutterMutator> mutators;

  private List<Path> finalClippingPaths;
  private Matrix finalMatrix;
  private float finalOpacity;
  @Nullable private RuntimeShaderFilter finalRuntimeShaderFilter;

  /** Initialize the mutator stack. */
  public FlutterMutatorsStack() {
    this.mutators = new ArrayList<FlutterMutator>();
    finalMatrix = new Matrix();
    finalClippingPaths = new ArrayList<Path>();
    finalOpacity = 1.f;
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

  /**
   * Push a clipRect {@link FlutterMutatorsStack.FlutterMutator} to the stack.
   *
   * <p>The bounds are in (fractional) physical pixels. Rounding them to whole pixels here would
   * shrink the clip by up to a pixel, which shows up as a sliver of Flutter content along the right
   * and bottom edges of the clipped platform view. See
   * https://github.com/flutter/flutter/issues/189834.
   */
  public void pushClipRect(float left, float top, float right, float bottom) {
    RectF rect = new RectF(left, top, right, bottom);
    FlutterMutator mutator = new FlutterMutator(rect);
    mutators.add(mutator);
    Path path = new Path();
    path.addRect(rect, Path.Direction.CCW);
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
  public void pushClipRRect(float left, float top, float right, float bottom, float[] radiis) {
    RectF rect = new RectF(left, top, right, bottom);
    FlutterMutator mutator = new FlutterMutator(rect, radiis);
    mutators.add(mutator);
    Path path = new Path();
    path.addRoundRect(rect, radiis, Path.Direction.CCW);
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
   * Push a runtime shader filter {@link FlutterMutatorsStack.FlutterMutator} to the stack.
   *
   * @param programId the id of a program registered with {@link #registerRuntimeShaderProgram}.
   * @param uniformValues the values of all of the program's uniforms, concatenated.
   * @param left the left edge of the filter input in physical pixels.
   * @param top the top edge of the filter input in physical pixels.
   * @param right the right edge of the filter input in physical pixels.
   * @param bottom the bottom edge of the filter input in physical pixels.
   */
  @Keep
  public void pushRuntimeShaderFilter(
      long programId, @NonNull float[] uniformValues, int left, int top, int right, int bottom) {
    RuntimeShaderFilter filter =
        new RuntimeShaderFilter(programId, uniformValues, left, top, right, bottom);
    mutators.add(new FlutterMutator(filter));
    // Only a single RenderEffect can be applied to a platform view, so only the outermost filter
    // is applied.
    if (finalRuntimeShaderFilter == null) {
      finalRuntimeShaderFilter = filter;
    }
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
   * Returns the runtime shader filter to apply to the view, or null if there is none.
   *
   * <p>If the stack contains more than one runtime shader filter, this is the outermost one.
   */
  @Nullable
  RuntimeShaderFilter getFinalRuntimeShaderFilter() {
    return finalRuntimeShaderFilter;
  }
}
