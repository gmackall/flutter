// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.flutter.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Stages the `flutter_assets` produced by the Flutter build into a dedicated [destinationDir], under
 * a top-level `flutter_assets` directory.
 *
 * The [destinationDir] is registered as a generated asset source directory on the Android variant
 * (`variant.sources.assets`), so AGP merges it into the variant's assets. This replaces the legacy
 * approach of copying `flutter_assets` into the merge-assets output directory after the merge task
 * had run.
 *
 * Like [CopyFlutterJniLibsTask], it deliberately writes to its own output directory rather than into
 * the Flutter task's output directory (overlapping task outputs previously broke Gradle's
 * incremental checks), and it tolerates the Flutter compile task being absent (e.g. an
 * `assembleAndroidTest` build), in which case it stages nothing. See
 * https://github.com/flutter/flutter/issues/186810 and
 * https://github.com/flutter/flutter/issues/188785.
 */
abstract class CopyFlutterAssetsTask : DefaultTask() {
    /**
     * The Flutter build output directory (the `flutter assemble` `--output` location), which
     * contains the `flutter_assets` directory.
     *
     * Optional: it is absent when there is no Flutter compile task for the variant, in which case
     * this task stages nothing.
     */
    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val intermediateDir: DirectoryProperty

    @get:OutputDirectory
    abstract val destinationDir: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun copy() {
        fileSystemOperations.sync {
            into(destinationDir)
            // Preserve the user read/write permissions the legacy copyFlutterAssets task applied to
            // the staged assets.
            filePermissions {
                user {
                    read = true
                    write = true
                }
            }
            // When there is no Flutter build for this variant (e.g. an assembleAndroidTest build),
            // there is nothing to stage; the empty sync simply clears destinationDir.
            if (intermediateDir.isPresent) {
                from(intermediateDir) {
                    include(FlutterTaskHelper.FLUTTER_ASSETS_INCLUDE_DIRECTORY)
                }
            }
        }
    }
}
