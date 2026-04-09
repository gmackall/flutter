// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.flutter.gradle

import com.android.build.gradle.BaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File
import java.util.Properties

/**
 * The Flutter Plugin Gradle Plugin (FPGP) applied by Flutter plugins
 * that have migrated to use composite builds.
 */
class FlutterPluginGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.logger.quiet("Applying FlutterPluginGradlePlugin to project ${project.name}")

        // Apply the "flutter" Gradle extension to plugins so that they can use its vended
        // compile/target/min sdk values.
        project.extensions.create("flutter", FlutterExtension::class.java)

        // Add Flutter repository for resolving embedding dependencies.
        project.repositories.maven {
            url = project.uri("https://storage.googleapis.com/download.flutter.io")
        }

        project.afterEvaluate {
            val flutterRoot = resolveFlutterRoot(project)
            if (flutterRoot == null) {
                project.logger.error("Flutter SDK root not found. Please set FLUTTER_ROOT environment variable or flutter.sdk in local.properties.")
                return@afterEvaluate
            }

            val engineVersion = readEngineVersion(flutterRoot)
            if (engineVersion == null) {
                project.logger.error("Engine version not found in Flutter SDK.")
                return@afterEvaluate
            }

            // Add dependencies to standard configurations if they exist.
            // This avoids accessing the Android extension which can fail due to classloader isolation in composite builds.
            val debugApi = project.configurations.findByName("debugApi")
            if (debugApi != null) {
                val dependency = "io.flutter:flutter_embedding_debug:$engineVersion"
                project.dependencies.add("debugApi", dependency)
                project.logger.quiet("Added dependency $dependency to configuration debugApi")
            }
            val releaseApi = project.configurations.findByName("releaseApi")
            if (releaseApi != null) {
                val dependency = "io.flutter:flutter_embedding_release:$engineVersion"
                project.dependencies.add("releaseApi", dependency)
                project.logger.quiet("Added dependency $dependency to configuration releaseApi")
            }
        }
    }

    private fun resolveFlutterRoot(project: Project): File? {
        val flutterRootSystemVal = System.getenv("FLUTTER_ROOT")
        if (flutterRootSystemVal != null) {
            return File(flutterRootSystemVal)
        }
        val localPropertiesFile = File(project.projectDir, "local.properties")
        if (localPropertiesFile.exists()) {
            val properties = Properties()
            localPropertiesFile.inputStream().use { properties.load(it) }
            val sdkPath = properties.getProperty("flutter.sdk")
            if (sdkPath != null) {
                return File(sdkPath)
            }
        }
        return null
    }

    private fun readEngineVersion(flutterRoot: File): String? {
        val engineStampPath = File(flutterRoot, "bin/cache/engine.stamp")
        if (!engineStampPath.exists()) return null
        val engineStampContent = engineStampPath.readText().trim()
        return "1.0.0-$engineStampContent"
    }
}
