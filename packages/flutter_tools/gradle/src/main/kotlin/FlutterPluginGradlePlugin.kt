// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.flutter.gradle

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import java.io.File

/**
 * Applied by Flutter plugins that have opted in to being built as an isolated AAR by setting
 * `flutter.plugin.migrated=true` in their `android/gradle.properties`.
 *
 * The plugin runs in one of two modes, chosen by whether the Flutter tool asked this build to
 * publish an AAR:
 *
 *  * **Publish mode** — the tool invoked this plugin's own Gradle build to produce an AAR for the
 *    shared plugin cache. The plugin configures `maven-publish`, resolves the Flutter engine and
 *    sibling plugin AARs, and publishes a single variant under the coordinate the tool chose.
 *
 *  * **Subproject mode** — this plugin was *demoted* and is being built from source as a
 *    subproject of the app, exactly as in the legacy model. That happens when the plugin is a path
 *    dependency (the developer may be editing it), when it builds native code, or when it depends
 *    on another plugin that is itself built from source. In this mode the plugin substitutes any
 *    inter-plugin Maven coordinates back to project dependencies, so that a plugin author writes
 *    one build file that works in both modes.
 */
class FlutterPluginGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Apply the "flutter" Gradle extension so plugins can use its vended compile/target/min
        // sdk values, matching what the legacy subproject model gave them.
        if (project.extensions.findByName("flutter") == null) {
            project.extensions.create("flutter", FlutterExtension::class.java)
        }

        val flutterRoot: File? = FlutterPluginUtils.resolveFlutterRoot(project)
        if (flutterRoot == null) {
            project.logger.error(
                "Flutter SDK root not found. Set the FLUTTER_ROOT environment variable, the " +
                    "flutter.sdk Gradle property, or flutter.sdk in local.properties."
            )
            return
        }

        FlutterPluginUtils.addFlutterEngineMavenRepository(project, flutterRoot)
        val engineVersion: String = FlutterPluginUtils.getFlutterEngineVersion(project, flutterRoot)
        addEmbeddingAsCompileOnlyDependency(project, engineVersion)

        val publishRepo: String? = project.findProperty(PROP_PUBLISH_REPO) as? String
        if (publishRepo == null) {
            configureAsSubproject(project)
        } else {
            configureForPublishing(project, publishRepo)
        }
    }

    /**
     * Adds the Flutter embedding as a `compileOnly` dependency.
     *
     * The embedding must not be bundled into the AAR: the app already depends on the embedding
     * variant matching its own build mode, and bundling would produce duplicate classes. The
     * release embedding is used for both published variants because the dependency is compile
     * only, so which variant was on the compile classpath has no effect at runtime.
     */
    private fun addEmbeddingAsCompileOnlyDependency(
        project: Project,
        engineVersion: String
    ) {
        project.afterEvaluate {
            if (project.configurations.findByName("compileOnly") == null) {
                return@afterEvaluate
            }
            val dependency = "io.flutter:flutter_embedding_release:$engineVersion"
            project.dependencies.add("compileOnly", dependency)
            project.logger.info("Added compileOnly dependency $dependency to project ${project.name}")
        }
    }

    /**
     * Configures this plugin for the case where it is built from source as a subproject of the
     * app rather than consumed as an AAR.
     *
     * A migrated plugin declares its dependencies on sibling plugins using Maven coordinates. When
     * the sibling is also being built from source those coordinates do not exist in any
     * repository, so they are substituted back to project dependencies. This is what lets the
     * demotion set be closed transitively without plugin authors maintaining two build files.
     */
    private fun configureAsSubproject(project: Project) {
        project.configurations.all {
            resolutionStrategy.dependencySubstitution {
                all {
                    val requested = this.requested
                    if (requested !is org.gradle.api.artifacts.component.ModuleComponentSelector) {
                        return@all
                    }
                    if (requested.group != FLUTTER_PLUGIN_GROUP_ID &&
                        requested.group != FLUTTER_PLUGIN_DEBUG_GROUP_ID
                    ) {
                        return@all
                    }
                    val substitute = project.rootProject.findProject(":${requested.module}") ?: return@all
                    useTarget(
                        project.dependencies.project(mapOf("path" to substitute.path)),
                        "Flutter plugin ${requested.module} is being built from source in this build."
                    )
                }
            }
        }
    }

    /**
     * Configures `maven-publish` so the Flutter tool can build this plugin into an AAR and cache
     * it for reuse across builds and across projects.
     */
    private fun configureForPublishing(
        project: Project,
        publishRepo: String
    ) {
        val resolveRepo: String? = project.findProperty(PROP_RESOLVE_REPO) as? String
        if (resolveRepo != null) {
            project.repositories.maven { url = project.uri(resolveRepo) }
        }

        val variant: String = (project.findProperty(PROP_VARIANT) as? String) ?: "release"
        val groupId: String = (project.findProperty(PROP_GROUP_ID) as? String) ?: FLUTTER_PLUGIN_GROUP_ID
        val artifactId: String = (project.findProperty(PROP_PLUGIN_NAME) as? String) ?: project.name
        val version: String = (project.findProperty(PROP_PLUGIN_VERSION) as? String) ?: "0.0.0"

        project.pluginManager.apply(MavenPublishPlugin::class.java)
        val publishing: PublishingExtension = project.extensions.getByType(PublishingExtension::class.java)
        publishing.repositories.maven {
            name = PUBLISH_REPOSITORY_NAME
            url = project.uri(publishRepo)
        }

        project.plugins.withId("com.android.library") {
            configureConsumerProguardFiles(project)
            val androidComponents =
                project.extensions.findByType(LibraryAndroidComponentsExtension::class.java)
            androidComponents?.finalizeDsl { extension ->
                // Publish exactly the variant the tool asked for. Sources are published alongside
                // so that IDE navigation into plugin code keeps working, which is otherwise a real
                // regression relative to the subproject model.
                extension.publishing.singleVariant(variant) {
                    withSourcesJar()
                }
            }
        }

        project.components.all {
            if (name != variant || publishing.publications.findByName(PUBLICATION_NAME) != null) {
                return@all
            }
            val component = this
            publishing.publications.create(PUBLICATION_NAME, MavenPublication::class.java) {
                from(component)
                this.groupId = groupId
                this.artifactId = artifactId
                this.version = version
            }
        }

        writeRepositoriesManifest(project, publishRepo, groupId, artifactId, version)
    }

    /**
     * Wires up the plugin's consumer ProGuard rules.
     *
     * In the subproject model an app's R8 run saw the plugin's `proguard-rules.pro` directly. Once
     * the plugin is an AAR, only rules registered as *consumer* rules travel with it, so a plugin
     * that relies on reflection would silently break under minification without this.
     */
    private fun configureConsumerProguardFiles(project: Project) {
        val libraryExtension = project.extensions.findByType(LibraryExtension::class.java) ?: return
        val consumerRules = project.file("consumer-rules.pro")
        val proguardRules = project.file("proguard-rules.pro")
        if (consumerRules.exists()) {
            libraryExtension.defaultConfig.consumerProguardFiles(consumerRules)
        } else if (proguardRules.exists()) {
            libraryExtension.defaultConfig.consumerProguardFiles(proguardRules)
        }
    }

    /**
     * Records the non-default Maven repositories this plugin resolves its dependencies from.
     *
     * A plugin's `implementation` dependencies become dependencies of the *app* once the plugin
     * ships as an AAR, so any repository beyond the ones the app already declares has to be
     * declared by the app as well. Writing them next to the published artifacts lets the tool
     * collect them and inject them into the app build, so migration does not require every plugin
     * author to document a repository list.
     *
     * Credentials are deliberately not recorded. If an injected repository needs authentication
     * the app owner has to supply it, and they get an error naming the repository rather than an
     * unresolvable dependency.
     */
    private fun writeRepositoriesManifest(
        project: Project,
        publishRepo: String,
        groupId: String,
        artifactId: String,
        version: String
    ) {
        project.afterEvaluate {
            val urls: List<String> =
                project.repositories
                    .filterIsInstance<MavenArtifactRepository>()
                    .map { it.url.toString() }
                    .filter { url -> DEFAULT_REPOSITORY_PREFIXES.none { url.startsWith(it) } }
                    .filterNot { it.startsWith("file:") }
                    .distinct()

            val destination =
                File(
                    File(publishRepo, groupId.replace('.', File.separatorChar)),
                    "$artifactId${File.separator}$version"
                )
            destination.mkdirs()
            val manifest = File(destination, "$artifactId-$version-repositories.json")
            val entries = urls.joinToString(separator = ",\n") { "    { \"url\": \"$it\" }" }
            manifest.writeText("{\n  \"repositories\": [\n$entries\n  ]\n}\n")
        }
    }

    companion object {
        internal const val FLUTTER_PLUGIN_GROUP_ID = "dev.flutter.plugins"
        internal const val FLUTTER_PLUGIN_DEBUG_GROUP_ID = "dev.flutter.plugins.debug"

        internal const val PUBLICATION_NAME = "flutterPlugin"
        internal const val PUBLISH_REPOSITORY_NAME = "FlutterLocal"

        internal const val PROP_PUBLISH_REPO = "flutter.plugin.publishRepo"
        internal const val PROP_RESOLVE_REPO = "flutter.plugin.resolveRepo"
        internal const val PROP_VARIANT = "flutter.plugin.variant"
        internal const val PROP_GROUP_ID = "flutter.plugin.groupId"
        internal const val PROP_PLUGIN_NAME = "flutter.plugin.name"
        internal const val PROP_PLUGIN_VERSION = "flutter.plugin.version"

        private val DEFAULT_REPOSITORY_PREFIXES =
            listOf(
                "https://dl.google.com/dl/android/maven2",
                "https://maven.google.com",
                "https://repo.maven.apache.org/maven2",
                "https://repo1.maven.org/maven2",
                "https://plugins.gradle.org/m2",
                "https://storage.googleapis.com/download.flutter.io"
            )
    }
}
