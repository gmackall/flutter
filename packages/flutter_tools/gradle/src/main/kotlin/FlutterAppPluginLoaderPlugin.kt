// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.flutter.gradle

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.jetbrains.kotlin.gradle.plugin.extraProperties
import java.io.File
import java.nio.file.Paths
import java.util.Properties

private const val FLUTTER_SDK_PATH = "flutterSdkPath"

// TODO(gmackall): Migrate the settings plugin to a proper Gradle plugin, and out of the
//  buildscript block. This is a prerequisite for supporting the configuration cache.
@Suppress("unused") // This class is used by packages/flutter_tools/gradle/build.gradle.kts.
class FlutterAppPluginLoaderPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        val flutterProjectRoot: File = settings.settingsDir.parentFile

        if (!settings.extraProperties.has(FLUTTER_SDK_PATH)) {
            val localPropertiesFile = File(settings.rootProject.projectDir, "local.properties")
            if (localPropertiesFile.exists()) {
                val properties = Properties()
                localPropertiesFile.inputStream().use { properties.load(it) }
                properties.getProperty("flutter.sdk")?.let {
                    settings.extraProperties.set(FLUTTER_SDK_PATH, it)
                }
            }
            if (!settings.extraProperties.has(FLUTTER_SDK_PATH)) {
                val fallback =
                    System.getenv("FLUTTER_ROOT")
                        ?: settings.providers.gradleProperty("flutter.sdk").orNull
                if (fallback != null) {
                    settings.extraProperties.set(FLUTTER_SDK_PATH, fallback)
                }
            }
            check(settings.extraProperties.has(FLUTTER_SDK_PATH)) {
                "flutter.sdk not set in local.properties, the FLUTTER_ROOT environment variable, " +
                    "or the flutter.sdk Gradle property."
            }
        }

        settings.apply {
            from(
                Paths.get(
                    settings.extraProperties.get(FLUTTER_SDK_PATH) as String,
                    "packages",
                    "flutter_tools",
                    "gradle",
                    "src",
                    "main",
                    "scripts",
                    "native_plugin_loader.gradle.kts"
                )
            )
        }

        // The Flutter tool decides which plugins are consumed as prebuilt AARs and which are built
        // from source, and writes that decision out before invoking Gradle. When there is no plan
        // — Gradle invoked directly, or an SDK newer than this plugin understands — every plugin
        // is included as a subproject, which is the legacy behavior.
        val plan: FlutterPluginAarPlan? = FlutterPluginAarPlan.readOrNull(flutterProjectRoot)
        if (plan == null) {
            includeAllPluginsAsSubprojects(settings, flutterProjectRoot)
            return
        }

        plan.subprojectPlugins.forEach { plugin ->
            val pluginDirectory = File(plugin.path, "android")
            check(
                pluginDirectory.exists()
            ) { "Plugin directory does not exist: ${pluginDirectory.absolutePath}" }
            settings.include(":${plugin.name}")
            settings.project(":${plugin.name}").projectDir = pluginDirectory
        }
    }

    /**
     * Includes every Android plugin as a Gradle subproject, the model used before plugins could be
     * built as isolated AARs.
     */
    private fun includeAllPluginsAsSubprojects(
        settings: Settings,
        flutterProjectRoot: File
    ) {
        NativePluginLoaderReflectionBridge
            .getPlugins(settings.extraProperties, flutterProjectRoot)
            .forEach { androidPlugin ->
                val pluginDirectory = File(androidPlugin["path"] as String, "android")
                check(
                    pluginDirectory.exists()
                ) { "Plugin directory does not exist: ${pluginDirectory.absolutePath}" }
                val pluginName = androidPlugin["name"] as String
                settings.include(":$pluginName")
                settings.project(":$pluginName").projectDir = pluginDirectory
            }
    }
}
