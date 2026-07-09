// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine;

import android.content.Context;

import androidx.annotation.NonNull;
import java.util.*;

/**
 * DEPRECATED. Please see {@link FlutterEngineFlags} for the list of arguments to use or update if
 * you are adding a new flag.
 *
 * <p>Arguments that can be delivered to the Flutter shell when it is created.
 *
 * <p>The term "shell" refers to the native code that adapts Flutter to different platforms.
 * Flutter's Android Java code initializes a native "shell" and passes these arguments to that
 * native shell when it is initialized. See {@link
 * io.flutter.embedding.engine.loader.FlutterLoader#ensureInitializationComplete(Context, String[])}
 * for more information.
 */
// TODO(camsim99): Delete this class when support for setting engine shell arguments via Intent
// is no longer supported. See https://github.com/flutter/flutter/issues/180686.
@SuppressWarnings({"WeakerAccess", "unused"})
@Deprecated
public class FlutterShellArgs {


  @NonNull private Set<String> args;

  /**
   * Creates a set of Flutter shell arguments from a given {@code String[]} array. The given
   * arguments are automatically de-duplicated.
   */
  public FlutterShellArgs(@NonNull String[] args) {
    this.args = new HashSet<>(Arrays.asList(args));
  }

  /**
   * Creates a set of Flutter shell arguments from a given {@code List<String>}. The given arguments
   * are automatically de-duplicated.
   */
  public FlutterShellArgs(@NonNull List<String> args) {
    this.args = new HashSet<>(args);
  }

  /** Creates a set of Flutter shell arguments from a given {@code Set<String>}. */
  public FlutterShellArgs(@NonNull Set<String> args) {
    this.args = new HashSet<>(args);
  }

  /**
   * Adds the given {@code arg} to this set of arguments.
   *
   * @param arg argument to add
   */
  public void add(@NonNull String arg) {
    args.add(arg);
  }

  /**
   * Removes the given {@code arg} from this set of arguments.
   *
   * @param arg argument to remove
   */
  public void remove(@NonNull String arg) {
    args.remove(arg);
  }

  /**
   * Returns a new {@code String[]} array which contains each of the arguments within this {@code
   * FlutterShellArgs}.
   *
   * @return array of arguments
   */
  @NonNull
  public String[] toArray() {
    String[] argsArray = new String[args.size()];
    return args.toArray(argsArray);
  }
}
