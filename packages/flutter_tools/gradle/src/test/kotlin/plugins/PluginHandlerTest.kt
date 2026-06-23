// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.flutter.gradle.plugins

import com.android.build.api.dsl.ApplicationBuildType
import com.android.build.api.dsl.ApplicationExtension
import com.flutter.gradle.FlutterExtension
import com.flutter.gradle.FlutterPluginUtils
import com.flutter.gradle.FlutterPluginUtilsTest.Companion.EXAMPLE_ENGINE_VERSION
import com.flutter.gradle.FlutterPluginUtilsTest.Companion.cameraDependency
import com.flutter.gradle.FlutterPluginUtilsTest.Companion.flutterPluginAndroidLifecycleDependency
import com.flutter.gradle.FlutterPluginUtilsTest.Companion.pluginListWithDevDependency
import com.flutter.gradle.FlutterPluginUtilsTest.Companion.pluginListWithoutDevDependency
import com.flutter.gradle.NativePluginLoaderReflectionBridge
import io.mockk.called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.verify
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.jetbrains.kotlin.gradle.plugin.extraProperties
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginHandlerTest {
    // getPluginListWithoutDevDependencies
    @Test
    fun `getPluginListWithoutDevDependencies removes dev dependencies from list`() {
        val project = mockk<Project>()
        val pluginHandler = PluginHandler(project)
        mockkObject(NativePluginLoaderReflectionBridge)
        // mock return of NativePluginLoaderReflectionBridge.getPlugins
        every {
            NativePluginLoaderReflectionBridge.getPlugins(
                any(),
                any()
            )
        } returns pluginListWithDevDependency
        // mock method calls that are invoked by the args to NativePluginLoaderReflectionBridge
        every { project.extraProperties } returns mockk()
        every { project.extensions.findByType(FlutterExtension::class.java) } returns FlutterExtension()
        every { project.file(any()) } returns mockk()

        val result = pluginHandler.getPluginListWithoutDevDependencies()
        assertEquals(pluginListWithoutDevDependency, result)
    }

    @Test
    fun `getPluginListWithoutDevDependencies does not modify list without dev dependencies`() {
        val project = mockk<Project>()
        val pluginHandler = PluginHandler(project)
        mockkObject(NativePluginLoaderReflectionBridge)
        // mock return of NativePluginLoaderReflectionBridge.getPlugins
        every {
            NativePluginLoaderReflectionBridge.getPlugins(
                any(),
                any()
            )
        } returns pluginListWithoutDevDependency
        // mock method calls that are invoked by the args to NativePluginLoaderReflectionBridge
        every { project.extraProperties } returns mockk()
        every { project.extensions.findByType(FlutterExtension::class.java) } returns FlutterExtension()
        every { project.file(any()) } returns mockk()

        val result = pluginHandler.getPluginListWithoutDevDependencies()
        assertEquals(pluginListWithoutDevDependency, result)
    }

    // getPluginList skipped as it is a wrapper around a single reflection call

    // pluginSupportsAndroidPlatform
    @Test
    fun `pluginSupportsAndroidPlatform returns true when android directory exists with gradle build file`(
        @TempDir tempDir: Path
    ) {
        val projectDir = tempDir.resolve("my-plugin")
        projectDir.toFile().mkdirs()

        val androidDir = tempDir.resolve("android")
        androidDir.toFile().mkdirs()
        File(androidDir.toFile(), "build.gradle").createNewFile()

        val mockProject =
            mockk<Project> {
                every { this@mockk.projectDir } returns projectDir.toFile()
            }

        assertTrue {
            PluginHandler.pluginSupportsAndroidPlatform(mockProject)
        } // Replace YourClass with the actual class containing the method
    }

    @Test
    fun `pluginSupportsAndroidPlatform returns false when gradle build file does not exist`(
        @TempDir tempDir: Path
    ) {
        val projectDir = tempDir.resolve("my-plugin")
        projectDir.toFile().mkdirs()

        val mockProject =
            mockk<Project> {
                every { this@mockk.projectDir } returns projectDir.toFile()
            }

        assertFalse {
            PluginHandler.pluginSupportsAndroidPlatform(mockProject)
        }
    }

    @Test
    fun `configurePlugins throws IllegalArgumentException when plugin has no name`(
        @TempDir tempDir: Path
    ) {
        val project = mockk<Project>()

        // configuration for configureLegacyPluginEachProjects
        val projectDir = tempDir.resolve("my-plugin")
        projectDir.toFile().mkdirs()
        every { project.projectDir } returns projectDir.toFile()
        val settingsGradle = File(projectDir.parent.toFile(), "settings.gradle")
        settingsGradle.createNewFile()
        val mockLogger = mockk<Logger>()
        every { project.logger } returns mockLogger

        val pluginWithoutName: MutableMap<String?, Any?> = cameraDependency.toMutableMap()
        pluginWithoutName.remove("name")

        mockkObject(NativePluginLoaderReflectionBridge)
        // mock return of NativePluginLoaderReflectionBridge.getPlugins
        every { NativePluginLoaderReflectionBridge.getPlugins(any(), any()) } returns
            listOf(
                pluginWithoutName
            )
        // mock method calls that are invoked by the args to NativePluginLoaderReflectionBridge
        every { project.extraProperties } returns mockk()
        every { project.extensions.findByType(FlutterExtension::class.java) } returns FlutterExtension()
        every { project.file(any()) } returns mockk()

        val pluginHandler = PluginHandler(project)
        assertThrows<IllegalArgumentException> {
            pluginHandler.configurePlugins(
                engineVersionValue = EXAMPLE_ENGINE_VERSION
            )
        }
    }

    @Test
    fun `configurePlugins adds plugin project and configures its dependencies`(
        @TempDir tempDir: Path
    ) {
        val project = mockk<Project>()

        // configuration for configureLegacyPluginEachProjects
        val projectDir = tempDir.resolve("my-plugin")
        projectDir.toFile().mkdirs()
        every { project.projectDir } returns projectDir.toFile()
        val settingsGradle = File(projectDir.parent.toFile(), "settings.gradle")
        settingsGradle.createNewFile()
        val mockLogger = mockk<Logger>()
        every { project.logger } returns mockLogger

        val pluginProject = mockk<Project>()
        val pluginDependencyProject = mockk<Project>()
        val mockBuildType = mockk<ApplicationBuildType>()
        every { pluginProject.hasProperty("local-engine-repo") } returns false
        every { pluginProject.hasProperty("android") } returns true
        val mockPluginContainer = mockk<org.gradle.api.plugins.PluginContainer>()
        every { pluginProject.plugins } returns mockPluginContainer
        every { mockPluginContainer.hasPlugin("com.android.application") } returns false
        every { mockBuildType.name } returns "debug"
        every { mockBuildType.isDebuggable } returns true
        every { project.rootProject.findProject(":${cameraDependency["name"]}") } returns pluginProject
        every { project.rootProject.findProject(":${flutterPluginAndroidLifecycleDependency["name"]}") } returns pluginDependencyProject
        every { pluginProject.extensions.create(any(), any<Class<Any>>()) } returns mockk()
        val captureActionSlot = slot<Action<Project>>()
        val capturePluginActionSlot = mutableListOf<Action<Project>>()
        every { project.afterEvaluate(any<Action<Project>>()) } returns Unit
        every { pluginProject.afterEvaluate(any<Action<Project>>()) } returns Unit

        val mockProjectAndroidExtension = mockk<ApplicationExtension>()
        val mockPluginAndroidExtension = mockk<ApplicationExtension>()
        every { project.extensions.findByName("android") } returns mockProjectAndroidExtension
        every { pluginProject.extensions.findByName("android") } returns mockPluginAndroidExtension
        val mockProjectBuildTypes =
            mockk<NamedDomainObjectContainer<ApplicationBuildType>>()
        val mockPluginProjectBuildTypes =
            mockk<NamedDomainObjectContainer<ApplicationBuildType>>()
        every { mockProjectAndroidExtension.buildTypes } returns mockProjectBuildTypes
        every { mockPluginAndroidExtension.buildTypes } returns mockPluginProjectBuildTypes
        // The plugin already has the app's build types, so no copying is needed.
        every { mockPluginProjectBuildTypes.findByName(any()) } returns mockBuildType
        every { pluginProject.configurations.named(any<String>()) } returns mockk()
        every { pluginProject.dependencies.add(any(), any()) } returns mockk()

        // Return a fresh iterator on each iteration of the app's build types.
        every { mockProjectBuildTypes.iterator() } answers { mutableListOf(mockBuildType).iterator() }
        every { project.dependencies.add(any(), any()) } returns mockk()
        every { mockProjectAndroidExtension.compileSdk } returns 35
        every { mockProjectAndroidExtension.compileSdkPreview } returns null
        every { mockPluginAndroidExtension.compileSdk } returns 35
        every { mockPluginAndroidExtension.compileSdkPreview } returns null

        val pluginHandler = PluginHandler(project)
        mockkObject(NativePluginLoaderReflectionBridge)
        // mock return of NativePluginLoaderReflectionBridge.getPlugins
        val pluginWithDependencies: MutableMap<String?, Any?> = cameraDependency.toMutableMap()
        pluginWithDependencies["dependencies"] =
            listOf(flutterPluginAndroidLifecycleDependency["name"])
        every { NativePluginLoaderReflectionBridge.getPlugins(any(), any()) } returns
            listOf(
                pluginWithDependencies
            )
        // mock method calls that are invoked by the args to NativePluginLoaderReflectionBridge
        every { project.extraProperties } returns mockk()
        every { project.extensions.findByType(FlutterExtension::class.java) } returns FlutterExtension()
        every { project.file(any()) } returns mockk()

        pluginHandler.configurePlugins(
            engineVersionValue = EXAMPLE_ENGINE_VERSION
        )

        verify { project.afterEvaluate(capture(captureActionSlot)) }
        verify { pluginProject.afterEvaluate(capture(capturePluginActionSlot)) }
        captureActionSlot.captured.execute(project)
        capturePluginActionSlot[0].execute(pluginProject)
        capturePluginActionSlot[1].execute(pluginProject)
        verify { pluginProject.extensions.create("flutter", FlutterExtension::class.java) }
        verify {
            pluginProject.dependencies.add(
                "debugApi",
                "io.flutter:flutter_embedding_debug:$EXAMPLE_ENGINE_VERSION"
            )
        }
        verify { project.dependencies.add("debugApi", pluginProject) }
        verify { mockLogger wasNot called }
        // The plugin already has the app's build types, so none should be created on it.
        verify(exactly = 0) { mockPluginProjectBuildTypes.create(any<String>(), any<Action<ApplicationBuildType>>()) }

        verify { pluginProject.dependencies.add("implementation", pluginDependencyProject) }
    }

    @Test
    fun `configurePlugins throws IllegalArgumentException when plugin has null dependencies`(
        @TempDir tempDir: Path
    ) {
        val project = mockk<Project>()

        // configuration for configureLegacyPluginEachProjects
        val projectDir = tempDir.resolve("my-plugin")
        projectDir.toFile().mkdirs()
        every { project.projectDir } returns projectDir.toFile()
        val settingsGradle = File(projectDir.parent.toFile(), "settings.gradle")
        settingsGradle.createNewFile()
        val mockLogger = mockk<Logger>()
        every { project.logger } returns mockLogger

        val pluginProject = mockk<Project>()
        val mockBuildType = mockk<ApplicationBuildType>()
        every { pluginProject.hasProperty("local-engine-repo") } returns false
        every { pluginProject.hasProperty("android") } returns true
        every { mockBuildType.name } returns "debug"
        every { mockBuildType.isDebuggable } returns true
        val pluginWithNullDependencies: MutableMap<String?, Any?> = cameraDependency.toMutableMap()
        pluginWithNullDependencies["dependencies"] = null
        every { project.rootProject.findProject(":${pluginWithNullDependencies["name"]}") } returns pluginProject
        every { pluginProject.extensions.create(any(), any<Class<Any>>()) } returns mockk()
        every { project.afterEvaluate(any<Action<Project>>()) } returns Unit
        every { pluginProject.afterEvaluate(any<Action<Project>>()) } returns Unit

        val mockProjectAndroidExtension = mockk<ApplicationExtension>()
        val mockPluginAndroidExtension = mockk<ApplicationExtension>()
        every { project.extensions.findByName("android") } returns mockProjectAndroidExtension
        every { pluginProject.extensions.findByName("android") } returns mockPluginAndroidExtension
        val mockProjectBuildTypes =
            mockk<NamedDomainObjectContainer<ApplicationBuildType>>()
        val mockPluginProjectBuildTypes =
            mockk<NamedDomainObjectContainer<ApplicationBuildType>>()
        every { mockProjectAndroidExtension.buildTypes } returns mockProjectBuildTypes
        every { mockPluginAndroidExtension.buildTypes } returns mockPluginProjectBuildTypes
        // The plugin already has the app's build types, so no copying is needed.
        every { mockPluginProjectBuildTypes.findByName(any()) } returns mockBuildType
        every { pluginProject.configurations.named(any<String>()) } returns mockk()
        every { pluginProject.dependencies.add(any(), any()) } returns mockk()

        // Return a fresh iterator on each iteration of the app's build types.
        every { mockProjectBuildTypes.iterator() } answers { mutableListOf(mockBuildType).iterator() }
        every { project.dependencies.add(any(), any()) } returns mockk()
        every { mockProjectAndroidExtension.compileSdk } returns 35
        every { mockProjectAndroidExtension.compileSdkPreview } returns null
        every { mockPluginAndroidExtension.compileSdk } returns 35
        every { mockPluginAndroidExtension.compileSdkPreview } returns null

        val pluginHandler = PluginHandler(project)
        mockkObject(NativePluginLoaderReflectionBridge)
        // mock return of NativePluginLoaderReflectionBridge.getPlugins
        every { NativePluginLoaderReflectionBridge.getPlugins(any(), any()) } returns
            listOf(
                pluginWithNullDependencies
            )
        // mock method calls that are invoked by the args to NativePluginLoaderReflectionBridge
        every { project.extraProperties } returns mockk()
        every { project.extensions.findByType(FlutterExtension::class.java) } returns FlutterExtension()
        every { project.file(any()) } returns mockk()

        assertThrows<IllegalArgumentException> {
            pluginHandler.configurePlugins(
                engineVersionValue = EXAMPLE_ENGINE_VERSION
            )
        }
    }

    @Test
    fun `configurePlugins copies missing app build types onto app plugins via initWith`(
        @TempDir tempDir: Path
    ) {
        val project = mockk<Project>()
        val pluginProject = mockk<Project>()

        setupBasicMocks(project, pluginProject, mockk(), tempDir)
        setupPluginMocks(project)

        // The plugin is itself an app project.
        mockkObject(FlutterPluginUtils)
        every { FlutterPluginUtils.isBuiltAsApp(pluginProject) } returns true

        val mockProjectAndroidExtension = mockk<ApplicationExtension>()
        val mockPluginAndroidExtension = mockk<ApplicationExtension>()
        every { project.extensions.findByName("android") } returns mockProjectAndroidExtension
        every { pluginProject.extensions.findByName("android") } returns mockPluginAndroidExtension
        every { mockProjectAndroidExtension.compileSdk } returns 35
        every { mockProjectAndroidExtension.compileSdkPreview } returns null
        every { mockPluginAndroidExtension.compileSdk } returns 35
        every { mockPluginAndroidExtension.compileSdkPreview } returns null

        val mockProjectBuildTypes = mockk<NamedDomainObjectContainer<ApplicationBuildType>>()
        val mockPluginProjectBuildTypes = mockk<NamedDomainObjectContainer<ApplicationBuildType>>()
        every { mockProjectAndroidExtension.buildTypes } returns mockProjectBuildTypes
        every { mockPluginAndroidExtension.buildTypes } returns mockPluginProjectBuildTypes

        val appBuildType = mockk<ApplicationBuildType>()
        every { appBuildType.name } returns "debug"
        every { appBuildType.isDebuggable } returns true
        every { mockProjectBuildTypes.iterator() } answers { mutableListOf(appBuildType).iterator() }

        // The plugin does not have this build type yet, so it should be created on the plugin.
        every { mockPluginProjectBuildTypes.findByName("debug") } returns null
        val mockCreatedBuildType = mockk<ApplicationBuildType>(relaxed = true)
        val createActionSlot = slot<Action<ApplicationBuildType>>()
        every { mockPluginProjectBuildTypes.create("debug", capture(createActionSlot)) } returns mockCreatedBuildType

        val pluginAfterEvaluate = slot<Action<Project>>()
        every { pluginProject.afterEvaluate(capture(pluginAfterEvaluate)) } returns Unit

        val pluginHandler = PluginHandler(project)
        pluginHandler.configurePlugins(engineVersionValue = EXAMPLE_ENGINE_VERSION)
        pluginAfterEvaluate.captured.execute(pluginProject)

        // The missing build type is created on the plugin and initialized wholesale from the app
        // build type (initWith copies app-specific properties too, which is safe for app plugins).
        verify { mockPluginProjectBuildTypes.create("debug", any<Action<ApplicationBuildType>>()) }
        createActionSlot.captured.execute(mockCreatedBuildType)
        verify { mockCreatedBuildType.initWith(appBuildType) }
    }

    @Test
    fun `configurePlugins copies only library-compatible properties onto library plugins`(
        @TempDir tempDir: Path
    ) {
        val project = mockk<Project>()
        val pluginProject = mockk<Project>()

        setupBasicMocks(project, pluginProject, mockk(), tempDir)
        setupPluginMocks(project)

        // The plugin is a library project.
        mockkObject(FlutterPluginUtils)
        every { FlutterPluginUtils.isBuiltAsApp(pluginProject) } returns false

        val mockProjectAndroidExtension = mockk<ApplicationExtension>()
        val mockPluginAndroidExtension = mockk<ApplicationExtension>()
        every { project.extensions.findByName("android") } returns mockProjectAndroidExtension
        every { pluginProject.extensions.findByName("android") } returns mockPluginAndroidExtension
        every { mockProjectAndroidExtension.compileSdk } returns 35
        every { mockProjectAndroidExtension.compileSdkPreview } returns null
        every { mockPluginAndroidExtension.compileSdk } returns 35
        every { mockPluginAndroidExtension.compileSdkPreview } returns null

        val mockProjectBuildTypes = mockk<NamedDomainObjectContainer<ApplicationBuildType>>()
        val mockPluginProjectBuildTypes = mockk<NamedDomainObjectContainer<ApplicationBuildType>>()
        every { mockProjectAndroidExtension.buildTypes } returns mockProjectBuildTypes
        every { mockPluginAndroidExtension.buildTypes } returns mockPluginProjectBuildTypes

        val appBuildType = mockk<ApplicationBuildType>()
        every { appBuildType.name } returns "debug"
        every { appBuildType.isDebuggable } returns true
        every { appBuildType.isMinifyEnabled } returns false
        every { mockProjectBuildTypes.iterator() } answers { mutableListOf(appBuildType).iterator() }

        every { mockPluginProjectBuildTypes.findByName("debug") } returns null
        val mockCreatedBuildType = mockk<ApplicationBuildType>(relaxed = true)
        val createActionSlot = slot<Action<ApplicationBuildType>>()
        every { mockPluginProjectBuildTypes.create("debug", capture(createActionSlot)) } returns mockCreatedBuildType

        val pluginAfterEvaluate = slot<Action<Project>>()
        every { pluginProject.afterEvaluate(capture(pluginAfterEvaluate)) } returns Unit

        val pluginHandler = PluginHandler(project)
        pluginHandler.configurePlugins(engineVersionValue = EXAMPLE_ENGINE_VERSION)
        pluginAfterEvaluate.captured.execute(pluginProject)

        // The missing build type is created on the plugin, but only the library-compatible
        // isMinifyEnabled is copied; app-specific properties (which initWith would copy, and
        // isDebuggable which the new-DSL library build type does not even expose) are not.
        verify { mockPluginProjectBuildTypes.create("debug", any<Action<ApplicationBuildType>>()) }
        createActionSlot.captured.execute(mockCreatedBuildType)
        verify { mockCreatedBuildType.isMinifyEnabled = false }
        verify(exactly = 0) { mockCreatedBuildType.initWith(any()) }
    }

    private fun setupBasicMocks(
        project: Project,
        pluginProject: Project,
        mockBuildType: ApplicationBuildType,
        tempDir: Path
    ) {
        // Configuration for project directory
        val projectDir = tempDir.resolve("my-plugin")
        projectDir.toFile().mkdirs()
        every { project.projectDir } returns projectDir.toFile()
        val settingsGradle = File(projectDir.parent.toFile(), "settings.gradle")
        settingsGradle.createNewFile()
        val mockLogger = mockk<Logger>()
        every { project.logger } returns mockLogger

        // Plugin project setup
        every { pluginProject.hasProperty("local-engine-repo") } returns false
        every { pluginProject.hasProperty("android") } returns true
        val mockPluginContainer = mockk<org.gradle.api.plugins.PluginContainer>()
        every { pluginProject.plugins } returns mockPluginContainer
        every { mockPluginContainer.hasPlugin("com.android.application") } returns false
        every { mockBuildType.name } returns "debug"
        every { mockBuildType.isDebuggable } returns true
        every { project.rootProject.findProject(":${cameraDependency["name"]}") } returns pluginProject
        every { pluginProject.extensions.create(any(), any<Class<Any>>()) } returns mockk()
        every { project.afterEvaluate(any<Action<Project>>()) } returns Unit
        every { pluginProject.afterEvaluate(any<Action<Project>>()) } returns Unit

        // Dependencies and configurations
        every { pluginProject.configurations.named(any<String>()) } returns mockk()
        every { pluginProject.dependencies.add(any(), any()) } returns mockk()
        every { project.dependencies.add(any(), any()) } returns mockk()
    }

    private fun setupPluginMocks(project: Project) {
        mockkObject(NativePluginLoaderReflectionBridge)
        every { NativePluginLoaderReflectionBridge.getPlugins(any(), any()) } returns listOf(cameraDependency)
        every { project.extraProperties } returns mockk()
        every { project.extensions.findByType(FlutterExtension::class.java) } returns FlutterExtension()
        every { project.file(any()) } returns mockk()
    }
}
