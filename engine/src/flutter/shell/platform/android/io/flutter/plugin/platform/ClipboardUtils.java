// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.platform;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import io.flutter.Log;
import io.flutter.embedding.engine.systemchannels.PlatformChannel;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Internal helper for reading, writing, and inspecting Android clipboard text content within
 * Flutter's Android embedding.
 *
 * <p>Centralizes clipboard handling for both {@link PlatformPlugin} (serving {@code
 * Clipboard.getData}, {@code Clipboard.setData}, and {@code Clipboard.hasStrings} on {@code
 * flutter/platform}) and {@link io.flutter.plugin.editing.TextInputPlugin} (reading clipboard text
 * synchronously inside {@link android.view.ActionMode.Callback#onActionItemClicked} when the user
 * selects Paste in the system floating context menu).
 *
 * <p>This class is an internal implementation detail of Flutter's Android embedding and is not part
 * of the public Android embedding API. External callers must not depend on it.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class ClipboardUtils {
  private static final String TAG = "ClipboardUtils";

  private ClipboardUtils() {}

  /**
   * Reads text from the primary clipboard item in the requested {@code format}, coercing {@code
   * content://} URIs that expose a {@code text/*} stream.
   */
  @Nullable
  public static CharSequence getClipboardData(
      @NonNull Context context, @Nullable PlatformChannel.ClipboardContentFormat format) {
    ClipboardManager clipboard =
        (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard == null || !clipboard.hasPrimaryClip()) {
      return null;
    }

    CharSequence itemText = null;
    try {
      ClipData clip = clipboard.getPrimaryClip();
      if (clip == null || clip.getItemCount() == 0) {
        return null;
      }
      if (format == null || format == PlatformChannel.ClipboardContentFormat.PLAIN_TEXT) {
        ClipData.Item item = clip.getItemAt(0);
        // First, try getting clipboard data as text; no further processing required if so.
        itemText = item.getText();
        if (itemText == null) {
          // Clipboard data does not contain text, so check whether or not it contains a URI to
          // extract text from.
          Uri itemUri = item.getUri();
          if (itemUri == null) {
            Log.w(
                TAG, "Clipboard item contained no textual content nor a URI to retrieve it from.");
            return null;
          }

          // Only attempt to extract text from a URI if it uses the content scheme.
          String uriScheme = itemUri.getScheme();
          if (!"content".equals(uriScheme)) {
            Log.w(
                TAG,
                "Clipboard item contains a Uri with scheme '" + uriScheme + "' that is unhandled.");
            return null;
          }

          AssetFileDescriptor assetFileDescriptor =
              context.getContentResolver().openTypedAssetFileDescriptor(itemUri, "text/*", null);

          // Safely return clipboard data coerced into text; will return either itemText or text
          // retrieved from its URI.
          itemText = item.coerceToText(context);
          if (assetFileDescriptor != null) {
            assetFileDescriptor.close();
          }
        }

        return itemText;
      }
    } catch (SecurityException e) {
      Log.w(
          TAG,
          "Attempted to get clipboard data that requires additional permission(s).\n"
              + "See the exception details for which permission(s) are required, and consider"
              + " adding them to your Android Manifest as described in:\n"
              + "https://developer.android.com/guide/topics/permissions/overview",
          e);
      return null;
    } catch (FileNotFoundException e) {
      Log.w(TAG, "Clipboard text was unable to be received from content URI.");
      return null;
    } catch (IOException e) {
      Log.w(TAG, "Failed to close AssetFileDescriptor while trying to read text from URI.", e);
      return itemText;
    }

    return null;
  }

  /** Copies plain {@code text} into the system primary clipboard. */
  public static void setClipboardData(@NonNull Context context, @NonNull String text) {
    ClipboardManager clipboard =
        (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard == null) {
      return;
    }
    ClipData clip = ClipData.newPlainText("text label?", text);
    clipboard.setPrimaryClip(clip);
  }

  /**
   * Returns whether the primary clipboard description advertises {@code text/*} content without
   * reading the clip payload itself.
   */
  public static boolean clipboardHasStrings(@NonNull Context context) {
    ClipboardManager clipboard =
        (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    // Android 12+ shows a toast notification when an app reads clipboard contents directly. Query
    // the clip description metadata instead of reading the clip payload.
    if (clipboard == null || !clipboard.hasPrimaryClip()) {
      return false;
    }
    ClipDescription description = clipboard.getPrimaryClipDescription();
    if (description == null) {
      return false;
    }
    return description.hasMimeType("text/*");
  }
}
