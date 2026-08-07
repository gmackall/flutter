// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.flutter.gradle

import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin

/**
 * The Flutter Plugin Gradle Plugin (FPGP) applied by Flutter plugins
 * that have migrated to use isolated AAR builds.
 *
 * Unlike the legacy subproject model, a migrated plugin is built as an independent AAR:
 *  1. Vends the `flutter` extension (compile/target/min sdk values).
 *  2. Adds the Flutter engine Maven repository.
 *  3. Adds the local plugins Maven repository for inter-plugin dependencies.
 *  4. Adds the Flutter embedding as a `compileOnly` dependency so it is not bundled in the AAR.
 *  5. Configures `maven-publish` to publish the release AAR and POM metadata to the local plugins repository.
 */
class FlutterPluginGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.logger.info("Applying FlutterPluginGradlePlugin to project ${project.name}")

        // Apply the "flutter" Gradle extension to plugins so that they can use its vended
        // compile/target/min sdk values.
        project.extensions.create("flutter", FlutterExtension::class.java)

        val flutterRoot = FlutterPluginUtils.resolveFlutterRoot(project)
        if (flutterRoot == null) {
            project.logger.error(
                "Flutter SDK root not found. Set the FLUTTER_ROOT environment variable, the " +
                    "flutter.sdk Gradle property, or flutter.sdk in local.properties."
            )
            return
        }

        // Add the Flutter engine repository for resolving embedding dependencies.
        FlutterPluginUtils.addFlutterEngineMavenRepository(project, flutterRoot)

        val engineVersion: String = FlutterPluginUtils.getFlutterEngineVersion(project, flutterRoot)

        // Add local plugins repository if specified (for inter-plugin dependencies)
        val localRepoPath = (project.findProperty("flutter.localPluginsRepo") as? String)
            ?: System.getenv("FLUTTER_LOCAL_PLUGINS_REPO")
        if (localRepoPath != null) {
            project.repositories.maven {
                url = project.uri(localRepoPath)
            }
        }

        // Apply maven-publish plugin
        project.pluginManager.apply(MavenPublishPlugin::class.java)

        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        if (localRepoPath != null) {
            publishing.repositories.maven {
                name = "localPluginsRepo"
                url = project.uri(localRepoPath)
            }
        }

        // Configure AGP library publishing for release variant automatically via AndroidComponents
        project.plugins.withId("com.android.library") {
            val androidComponents = project.extensions.findByType(
                com.android.build.api.variant.LibraryAndroidComponentsExtension::class.java
            )
            androidComponents?.finalizeDsl { extension ->
                extension.publishing.singleVariant("release")
            }
        }

        project.components.all(object : org.gradle.api.Action<org.gradle.api.component.SoftwareComponent> {
            override fun execute(component: org.gradle.api.component.SoftwareComponent) {
                if (component.name == "release" && localRepoPath != null) {
                    if (publishing.publications.findByName("release") == null) {
                        publishing.publications.create("release", MavenPublication::class.java) {
                            from(component)
                            groupId = "dev.flutter.plugins"
                            artifactId = project.name
                            version = (project.findProperty("flutter.pluginVersion") as? String) ?: "1.0.0"
                        }
                    }
                }
            }
        })

        project.afterEvaluate {
            // Flutter embedding should be compileOnly for AAR library builds so it's not bundled or conflicting
            if (project.configurations.findByName("compileOnly") != null) {
                val dependency = "io.flutter:flutter_embedding_release:$engineVersion"
                project.dependencies.add("compileOnly", dependency)
                project.logger.info("Added compileOnly dependency $dependency to project ${project.name}")
            }
        }
    }
}


