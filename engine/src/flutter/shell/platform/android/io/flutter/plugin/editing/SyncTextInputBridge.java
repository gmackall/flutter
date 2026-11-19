// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.editing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.embedding.engine.FlutterJNI;
import java.nio.ByteBuffer;

/**
 * Package-private abstraction over the native synchronous Android text input JNI entry points so
 * {@link TextInputPlugin} and {@link FlutterInputConnection} can be unit tested under Robolectric
 * without loading {@code libflutter.so}.
 */
interface SyncTextInputBridge {
  /**
   * Returns true if synchronous text input is enabled for this engine and the Dart framework has
   * registered its root-isolate handler on the merged platform/UI thread.
   */
  boolean isSyncTextInputEnabled();

  /**
   * Synchronously sends a little-endian binary IME request frame to Dart and returns the encoded
   * response bytes, or {@code null} if sync dispatch is inactive or failed.
   */
  @Nullable
  byte[] dispatchSyncTextInput(@NonNull ByteBuffer request, int length);

  /**
   * Registers or clears the listener that receives synchronous Dart-to-Android programmatic editing
   * state notifications.
   */
  void setEditingStateListener(@Nullable FlutterJNI.SyncTextInputEditingStateListener listener);

  /** Clears the editing state listener only if it currently matches {@code listener}. */
  void clearEditingStateListener(@NonNull FlutterJNI.SyncTextInputEditingStateListener listener);

  /** Production implementation backed by {@link FlutterJNI}. */
  final class FlutterJNIBridge implements SyncTextInputBridge {
    @NonNull private final FlutterJNI flutterJNI;

    FlutterJNIBridge(@NonNull FlutterJNI flutterJNI) {
      this.flutterJNI = flutterJNI;
    }

    @Override
    public boolean isSyncTextInputEnabled() {
      return flutterJNI.isSyncTextInputEnabled();
    }

    @Nullable
    @Override
    public byte[] dispatchSyncTextInput(@NonNull ByteBuffer request, int length) {
      return flutterJNI.dispatchSyncTextInput(request, length);
    }

    @Override
    public void setEditingStateListener(
        @Nullable FlutterJNI.SyncTextInputEditingStateListener listener) {
      flutterJNI.setSyncTextInputEditingStateListener(listener);
    }

    @Override
    public void clearEditingStateListener(
        @NonNull FlutterJNI.SyncTextInputEditingStateListener listener) {
      flutterJNI.clearSyncTextInputEditingStateListener(listener);
    }
  }
}
