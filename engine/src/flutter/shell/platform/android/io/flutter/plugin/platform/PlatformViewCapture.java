// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.platform;

import static io.flutter.Build.API_LEVELS;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.UiThread;
import io.flutter.Log;
import io.flutter.embedding.engine.mutatorsstack.FlutterMutatorView;
import io.flutter.view.TextureRegistry;
import java.lang.ref.WeakReference;

/**
 * Renders a Hybrid Composition++ platform view offscreen, so that the engine can apply a {@code
 * BackdropFilter} that is painted over the platform view to it.
 *
 * <p>The live platform view stays in the view hierarchy. In addition, while a capture exists, the
 * platform view is drawn into a {@link TextureRegistry.SurfaceProducer} (backed by an {@code
 * ImageReader} of {@code HardwareBuffer}s, the same mechanism that texture layer platform views
 * use) whenever its content changes. The engine imports the latest buffer as a texture and draws it
 * in place of the live platform view while the filter applies.
 */
@RequiresApi(API_LEVELS.API_34)
final class PlatformViewCapture {
  private static final String TAG = "PlatformViewCapture";

  @NonNull private final TextureRegistry.SurfaceProducer producer;
  @NonNull private final WeakReference<TextureRegistry.ImageConsumer> consumerReference;
  @NonNull private final Runnable onUncapturable;
  private final FlutterMutatorView.ContentListener contentListener =
      new FlutterMutatorView.ContentListener() {
        @Override
        public void onContentInvalidated() {
          dirty = true;
        }

        @Override
        public void onContentDrawn() {
          // Capture in the same frame as the live platform view is drawn, so the
          // capture lags the live view as little as possible. Drawing the view again
          // here reuses the display lists that were just recorded.
          if (needsCapture()) {
            capture();
          }
        }
      };

  @Nullable private FlutterMutatorView parent;
  @Nullable private View view;
  // Whether the platform view may have changed since it was last captured.
  private boolean dirty = true;
  private int lastVisibility = View.VISIBLE;
  private boolean hadAnimation = false;
  private boolean released = false;

  /**
   * Creates a capture that renders into {@code producer}, which must also be an {@link
   * TextureRegistry.ImageConsumer} (see {@link #canUseProducer}).
   */
  PlatformViewCapture(
      @NonNull TextureRegistry.SurfaceProducer producer, @NonNull Runnable onUncapturable) {
    this.producer = producer;
    this.consumerReference = new WeakReference<>((TextureRegistry.ImageConsumer) producer);
    this.onUncapturable = onUncapturable;
  }

  /** Whether the engine can read captures rendered into {@code producer}. */
  static boolean canUseProducer(@NonNull TextureRegistry.SurfaceProducer producer) {
    return producer instanceof TextureRegistry.ImageConsumer;
  }

  /**
   * Whether {@code view} can be captured by drawing it into a canvas.
   *
   * <p>A {@link SurfaceView} renders into its own surface, which drawing the view does not include,
   * so a capture would contain a hole.
   */
  static boolean canCapture(@NonNull View view) {
    if (view instanceof SurfaceView) {
      return false;
    }
    if (view instanceof ViewGroup) {
      final ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        if (!canCapture(group.getChildAt(i))) {
          return false;
        }
      }
    }
    return true;
  }

  /** The image consumer the engine reads captures from. Stable for the lifetime of the capture. */
  @NonNull
  WeakReference<TextureRegistry.ImageConsumer> getConsumerReference() {
    return consumerReference;
  }

  /** Captures {@code view} now if it may have changed since it was last captured. */
  @UiThread
  void captureIfNeeded(@NonNull FlutterMutatorView parent, @NonNull View view) {
    if (released) {
      return;
    }
    if (this.parent != parent) {
      if (this.parent != null) {
        this.parent.setContentListener(null);
      }
      this.parent = parent;
      parent.setContentListener(contentListener);
    }
    final boolean viewChanged = this.view != view;
    this.view = view;
    if (viewChanged || needsCapture()) {
      capture();
    }
  }

  private boolean needsCapture() {
    return dirty
        || (view != null
            && (view.getVisibility() != lastVisibility
                || (hadAnimation && view.getAnimation() == null)
                || view.getWidth() != producer.getWidth()
                || view.getHeight() != producer.getHeight()));
  }

  /**
   * Sets whether the engine draws the capture in place of the live platform view, in which case the
   * live view does not draw to the window (it is only drawn into the capture).
   */
  @UiThread
  void setSubstituted(boolean substituted) {
    if (released || parent == null) {
      return;
    }
    parent.setDrawToWindow(!substituted);
  }

  /** Stops capturing and releases the buffers. */
  @UiThread
  void release() {
    if (released) {
      return;
    }
    released = true;
    view = null;
    if (parent != null) {
      parent.setContentListener(null);
      parent.setDrawToWindow(true);
      parent = null;
    }
    producer.release();
  }

  @UiThread
  private void capture() {
    final FlutterMutatorView parent = this.parent;
    final View view = this.view;
    if (released || parent == null || view == null) {
      return;
    }
    if (!canCapture(view)) {
      dirty = false;
      onUncapturable.run();
      return;
    }
    final int width = view.getWidth();
    final int height = view.getHeight();
    if (width <= 0
        || height <= 0
        || (view.getVisibility() != View.GONE && view.isLayoutRequested())) {
      // Not laid out yet; try again next frame.
      return;
    }
    producer.setSize(width, height);
    final Surface surface = producer.getSurface();
    if (surface == null || !surface.isValid()) {
      return;
    }
    final Canvas canvas;
    try {
      canvas = surface.lockHardwareCanvas();
    } catch (IllegalArgumentException | IllegalStateException e) {
      Log.d(TAG, "Could not capture platform view: " + e);
      return;
    }
    dirty = false;
    lastVisibility = view.getVisibility();
    try {
      canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
      parent.drawChild(canvas, view);
    } finally {
      hadAnimation = view.getAnimation() != null;
      surface.unlockCanvasAndPost(canvas);
    }
  }
}
