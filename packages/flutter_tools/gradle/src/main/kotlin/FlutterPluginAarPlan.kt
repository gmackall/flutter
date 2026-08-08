// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.flutter.gradle

import groovy.json.JsonSlurper
import java.io.File

/**
 * A plugin consumed as a prebuilt AAR from the local plugin repository.
 */
data class AarPluginEntry(
    val name: String,
    val groupId: String,
    val debugGroupId: String,
    val version: String,
    val isDevDependency: Boolean
) {
    /**
     * The Maven coordinate to depend on for a given Flutter build mode.
     *
     * Debug and release are separate coordinates rather than two variants of one module, so that
     * an app's custom build types resolve without needing attribute matching to reach across the
     * module boundary. Profile consumes the release AAR: the plugin compiles against the embedding
     * as `compileOnly`, so the embedding variant it saw has no runtime effect, and profile wants
     * optimized plugin code.
     */
    fun coordinateForBuildMode(buildMode: String): String =
        if (buildMode == "debug") {
            "$debugGroupId:$name:$version"
        } else {
            "$groupId:$name:$version"
        }
}

/**
 * A plugin built from source as a Gradle subproject, as in the legacy model.
 */
data class SubprojectPluginEntry(
    val name: String,
    val path: String,
    val isDevDependency: Boolean,
    val reason: String,
    val culprit: String?
)

/**
 * The Flutter tool's decision about how each Android plugin in this build is consumed.
 *
 * Written by the tool to `<local plugin repo>/flutter_plugin_aar_plan.json` before Gradle is
 * invoked. When the file is missing — for example when Gradle is run directly rather than through
 * `flutter build` — there is no plan and every plugin is built from source, which is the legacy
 * behavior.
 */
data class FlutterPluginAarPlan(
    val repository: File,
    val extraRepositories: List<String>,
    val aarPlugins: List<AarPluginEntry>,
    val subprojectPlugins: List<SubprojectPluginEntry>
) {
    companion object {
        const val PLAN_FILE_NAME = "flutter_plugin_aar_plan.json"

        /** Must match kAndroidPluginBuildPlanVersion in lib/src/android/plugin_aar.dart. */
        const val SUPPORTED_PLAN_VERSION = 1

        /** The path of the local plugin repository, relative to the Flutter project root. */
        const val LOCAL_REPO_RELATIVE_PATH = "build/flutter_plugins_aar_repo"

        fun localRepoDirectory(flutterProjectRoot: File): File = File(flutterProjectRoot, LOCAL_REPO_RELATIVE_PATH)

        /**
         * Reads the plan for [flutterProjectRoot], or returns null when there is none.
         *
         * A plan written by a newer tool than this Gradle plugin understands is ignored rather than
         * rejected, so that a tool/SDK mismatch degrades to building plugins from source instead of
         * failing the build outright.
         */
        @Suppress("UNCHECKED_CAST")
        fun readOrNull(flutterProjectRoot: File): FlutterPluginAarPlan? {
            val repository = localRepoDirectory(flutterProjectRoot)
            val planFile = File(repository, PLAN_FILE_NAME)
            if (!planFile.exists()) {
                return null
            }
            val parsed = JsonSlurper().parseText(planFile.readText()) as? Map<*, *> ?: return null
            val version = (parsed["version"] as? Number)?.toInt()
            if (version != SUPPORTED_PLAN_VERSION) {
                return null
            }
            val aarPlugins =
                (parsed["aar"] as? List<*> ?: emptyList<Any>()).mapNotNull { element ->
                    val entry = element as? Map<*, *> ?: return@mapNotNull null
                    AarPluginEntry(
                        name = entry["name"] as? String ?: return@mapNotNull null,
                        groupId = entry["groupId"] as? String ?: return@mapNotNull null,
                        debugGroupId = entry["debugGroupId"] as? String ?: return@mapNotNull null,
                        version = entry["version"] as? String ?: return@mapNotNull null,
                        isDevDependency = entry["dev_dependency"] as? Boolean ?: false
                    )
                }
            val subprojectPlugins =
                (parsed["subprojects"] as? List<*> ?: emptyList<Any>()).mapNotNull { element ->
                    val entry = element as? Map<*, *> ?: return@mapNotNull null
                    SubprojectPluginEntry(
                        name = entry["name"] as? String ?: return@mapNotNull null,
                        path = entry["path"] as? String ?: return@mapNotNull null,
                        isDevDependency = entry["dev_dependency"] as? Boolean ?: false,
                        reason = entry["reason"] as? String ?: "unknown",
                        culprit = entry["culprit"] as? String
                    )
                }
            return FlutterPluginAarPlan(
                repository = File(parsed["repository"] as? String ?: repository.absolutePath),
                extraRepositories =
                    (parsed["extraRepositories"] as? List<*> ?: emptyList<Any>())
                        .filterIsInstance<String>(),
                aarPlugins = aarPlugins,
                subprojectPlugins = subprojectPlugins
            )
        }
    }
}
