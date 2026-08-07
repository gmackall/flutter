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

// Integration tests that cover this class include
// - packages/flutter_tools/test/integration.shard/android_gradle_daemon_cache_test.dart
// - packages/flutter_tools/test/integration.shard/android_plugin_compilesdkversion_mismatch_test.dart
// And can be run by following the README in  packages/flutter_tools/.

/**
 * This plugin applies the native plugin loader plugin (../scripts/native_plugin_loader.gradle.kts)
 * and then configures the main project to `include` each of the loaded flutter plugins.
 */
@Suppress("unused") // This class is used by packages/flutter_tools/gradle/build.gradle.kts.
class FlutterAppPluginLoaderPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {

        val flutterProjectRoot: File = settings.settingsDir.parentFile

        if (!settings.extraProperties.has(FLUTTER_SDK_PATH)) {
            val properties = Properties()
            val localPropertiesFile = File(settings.rootProject.projectDir, "local.properties")
            localPropertiesFile.inputStream().use { properties.load(it) }
            settings.extraProperties.set(FLUTTER_SDK_PATH, properties.getProperty("flutter.sdk"))
            assert(
                settings.extraProperties.get(FLUTTER_SDK_PATH) != null
            ) { "flutter.sdk not set in local.properties" }
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

        val localRepoDir = File(flutterProjectRoot, "build/flutter_plugins_aar_repo")
        localRepoDir.mkdirs()
        val flutterSdk = settings.extraProperties.get(FLUTTER_SDK_PATH) as String

        val allPlugins = NativePluginLoaderReflectionBridge
            .getPlugins(settings.extraProperties, flutterProjectRoot)

        val migratedPlugins = allPlugins.filter { it["is_migrated"] == true }
        val sortedMigratedPlugins = topologicalSortPlugins(migratedPlugins)

        val commonBuildArgs = mutableListOf<String>()
        commonBuildArgs.add("-Pflutter.localPluginsRepo=${localRepoDir.absolutePath}")
        commonBuildArgs.add("-Pflutter.sdk=$flutterSdk")
        commonBuildArgs.add("-q")

        if (settings.gradle.startParameter.isOffline) {
            commonBuildArgs.add("--offline")
        }

        // Forward host project properties (-P)
        settings.gradle.startParameter.projectProperties.forEach { (key, value) ->
            if (!key.startsWith("flutter.localPluginsRepo") && !key.startsWith("flutter.sdk")) {
                commonBuildArgs.add("-P$key=$value")
            }
        }

        // Forward host system properties (-D)
        val systemPropertiesToForward = listOf(
            "http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "http.nonProxyHosts",
            "javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword"
        )
        systemPropertiesToForward.forEach { key ->
            System.getProperty(key)?.let { value ->
                commonBuildArgs.add("-D$key=$value")
            }
        }
        settings.gradle.startParameter.systemPropertiesArgs.forEach { (key, value) ->
            commonBuildArgs.add("-D$key=$value")
        }

        sortedMigratedPlugins.forEach { androidPlugin ->
            val pluginDirectory = File(androidPlugin["path"] as String, "android")
            check(
                pluginDirectory.exists()
            ) { "Plugin directory does not exist: ${pluginDirectory.absolutePath}" }
            val pluginName = androidPlugin["name"] as String

            val pluginBuildDir = File(flutterProjectRoot, "build/plugins_build/$pluginName/build")
            val pluginCacheDir = File(flutterProjectRoot, "build/plugins_build/$pluginName/.gradle")

            val buildArgs = commonBuildArgs.toMutableList()
            buildArgs.add("--project-cache-dir")
            buildArgs.add(pluginCacheDir.absolutePath)
            buildArgs.add("-Pflutter.pluginBuildDir=${pluginBuildDir.absolutePath}")

            val connector = org.gradle.tooling.GradleConnector.newConnector()
                .forProjectDirectory(pluginDirectory)
            val connection = connector.connect()
            try {
                connection.newBuild()
                    .forTasks("publishReleasePublicationToLocalPluginsRepoRepository")
                    .withArguments(buildArgs)
                    .setStandardError(System.err)
                    .run()
            } finally {
                connection.close()
            }
        }

        allPlugins.forEach { androidPlugin ->
            val pluginDirectory = File(androidPlugin["path"] as String, "android")
            check(
                pluginDirectory.exists()
            ) { "Plugin directory does not exist: ${pluginDirectory.absolutePath}" }
            val pluginName = androidPlugin["name"] as String

            val isMigrated = androidPlugin["is_migrated"] as? Boolean ?: false

            if (!isMigrated) {
                settings.include(":$pluginName")
                settings.project(":$pluginName").projectDir = pluginDirectory
            }
        }
    }

    private fun topologicalSortPlugins(plugins: List<Map<String?, Any?>>): List<Map<String?, Any?>> {
        val result = mutableListOf<Map<String?, Any?>>()
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        val pluginMap = plugins.associateBy { it["name"] as String }

        fun visit(plugin: Map<String?, Any?>) {
            val name = plugin["name"] as String
            if (name in visited) return
            if (name in visiting) {
                return
            }
            visiting.add(name)
            val deps = (plugin["dependencies"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            for (dep in deps) {
                val depPlugin = pluginMap[dep]
                if (depPlugin != null) {
                    visit(depPlugin)
                }
            }
            visiting.remove(name)
            visited.add(name)
            result.add(plugin)
        }

        for (plugin in plugins) {
            visit(plugin)
        }
        return result
    }
}
