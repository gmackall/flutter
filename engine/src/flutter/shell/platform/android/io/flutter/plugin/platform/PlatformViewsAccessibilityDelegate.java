// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.platform;

import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.view.AccessibilityBridge;

/** Facilitates interaction between the accessibility bridge and embedded platform views. */
public interface PlatformViewsAccessibilityDelegate {
  /**
   * Returns the root of the view hierarchy for the platform view with the requested id, or null if
   * there is no corresponding view.
   */
  @Nullable
  View getPlatformViewById(int viewId);

  /**
   * Returns the Flutter-owned container view wrapping the platform view with the requested id (such
   * as {@link PlatformViewWrapper} or {@link
   * io.flutter.embedding.engine.mutatorsstack.FlutterMutatorView}), or null if no wrapper view is
   * attached.
   *
   * <p>For platform views that do not use a virtual display, {@link AccessibilityBridge} attaches
   * this wrapper view to the accessibility tree and manages its accessibility importance instead of
   * mutating the platform view returned by {@link #getPlatformViewById(int)}.
   *
   * <p>The default implementation falls back to {@link #getPlatformViewById(int)} for backwards
   * compatibility; when this method returns the same view instance as {@link
   * #getPlatformViewById(int)}, {@link AccessibilityBridge} preserves any existing {@link
   * View.AccessibilityDelegate} on that view instead of replacing it.
   *
   * <p>This member is not intended for external use.
   */
  @Nullable
  default View getPlatformViewWrapperById(int viewId) {
    return getPlatformViewById(viewId);
  }

  /** Returns true if the platform view uses virtual displays. */
  boolean usesVirtualDisplay(int id);

  /**
   * Attaches an accessibility bridge for this platform views accessibility delegate.
   *
   * <p>Accessibility events originating in platform views belonging to this delegate will be
   * delegated to this accessibility bridge.
   */
  void attachAccessibilityBridge(@NonNull AccessibilityBridge accessibilityBridge);

  /**
   * Detaches the current accessibility bridge.
   *
   * <p>Any accessibility events sent by platform views belonging to this delegate will be ignored
   * until a new accessibility bridge is attached.
   */
  void detachAccessibilityBridge();
}
