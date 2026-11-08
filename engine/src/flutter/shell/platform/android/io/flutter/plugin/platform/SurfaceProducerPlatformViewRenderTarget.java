// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.platform;

import static io.flutter.Build.API_LEVELS;

import android.view.Surface;
import androidx.annotation.RequiresApi;
import io.flutter.view.TextureRegistry.SurfaceProducer;

@RequiresApi(API_LEVELS.API_29)
public class SurfaceProducerPlatformViewRenderTarget implements PlatformViewRenderTarget {
  private static final String TAG = "SurfaceProducerRenderTarget";
  /**
   * Maximum buffered images for TLHC platform views: 2 for double-buffered {@code
   * lockHardwareCanvas()}, 2 for Impeller Vulkan frames in flight, plus 1 headroom buffer during
   * 120Hz frame pacing spikes (trimmed from the 7-buffer video/camera default).
   */
  public static final int MAX_IMAGES = 5;

  private SurfaceProducer producer;

  public SurfaceProducerPlatformViewRenderTarget(SurfaceProducer producer) {
    this.producer = producer;
    this.producer.setMaxImages(MAX_IMAGES);
  }

  // Called when the render target should be resized.
  public void resize(int width, int height) {
    this.producer.setSize(width, height);
  }

  // Returns the currently specified width.
  public int getWidth() {
    return this.producer.getWidth();
  }

  // Returns the currently specified height.
  public int getHeight() {
    return this.producer.getHeight();
  }

  // The id of this render target.
  public long getId() {
    return this.producer.id();
  }

  // Releases backing resources.
  public void release() {
    this.producer.release();
    this.producer = null;
  }

  // Returns true in the case that backing resource have been released.
  public boolean isReleased() {
    return this.producer == null;
  }

  // Returns the Surface to be rendered on to.
  public Surface getSurface() {
    return this.producer.getSurface();
  }

  public void scheduleFrame() {
    this.producer.scheduleFrame();
  }
}
