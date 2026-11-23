// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.flutter.gradle.tasks

import groovy.util.Node
import io.mockk.mockk
import io.mockk.verify
import org.gradle.api.logging.Logger
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [EnableAndroidPerformanceHintsManifestTaskHelper].
 */
class EnableAndroidPerformanceHintsManifestTaskTest {
    private val defaultNamespace = "dev.flutter.example"

    private fun createTempManifestFile(content: String): File {
        val manifestFile = File.createTempFile("AndroidManifestTest", ".xml")
        manifestFile.deleteOnExit()
        manifestFile.writeText(content.trimIndent())
        return manifestFile
    }

    private fun createTempOutputFile(): File {
        val outputFile = File.createTempFile("AndroidManifestUpdated", ".xml")
        outputFile.deleteOnExit()
        return outputFile
    }

    private fun findPerformanceHintsMetadataValue(manifestFile: File): String? {
        val manifest: Node =
            groovy.xml
                .XmlParser(false, false)
                .parse(manifestFile)
        val applicationNode: Node =
            manifest.children().filterIsInstance<Node>().find { node ->
                node.name() == "application"
            } ?: return null
        val metadataNode: Node? =
            applicationNode.children().filterIsInstance<Node>().find { node ->
                node.name() == "meta-data" &&
                    node.attribute("android:name") ==
                    EnableAndroidPerformanceHintsManifestTaskHelper.ANDROID_PERFORMANCE_HINTS_METADATA_NAME
            }
        return metadataNode?.attribute("android:value")?.toString()
    }

    @Test
    fun addsFalseMetadataWhenDisabledAndAbsent() {
        val manifestFile =
            createTempManifestFile(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="$defaultNamespace">
                    <application android:label="Test App">
                        <activity android:name=".MainActivity" android:exported="true" />
                    </application>
                </manifest>
                """
            )
        val updatedManifest = createTempOutputFile()

        EnableAndroidPerformanceHintsManifestTaskHelper.processAndroidPerformanceHintsManifest(
            manifestFile = manifestFile,
            updatedManifest = updatedManifest,
            requestedEnableAndroidPerformanceHints = false
        )

        assertEquals("false", findPerformanceHintsMetadataValue(updatedManifest))
    }

    @Test
    fun leavesManifestUnmodifiedWhenEnabledAndAbsent() {
        val manifestFile =
            createTempManifestFile(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="$defaultNamespace">
                    <!-- provenance comment -->
                    <application android:label="Test App">
                        <activity android:name=".MainActivity" android:exported="true" />
                    </application>
                </manifest>
                """
            )
        val updatedManifest = createTempOutputFile()

        EnableAndroidPerformanceHintsManifestTaskHelper.processAndroidPerformanceHintsManifest(
            manifestFile = manifestFile,
            updatedManifest = updatedManifest,
            requestedEnableAndroidPerformanceHints = true
        )

        assertNull(findPerformanceHintsMetadataValue(updatedManifest))
        assertEquals(
            manifestFile.readText(),
            updatedManifest.readText(),
            "A silent manifest should be copied unmodified when hints are enabled by default"
        )
    }

    @Test
    fun manifestTrueWinsOverDisabledDefaultWhenNoExplicitFlag() {
        val manifestFile =
            createTempManifestFile(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="$defaultNamespace">
                    <application android:label="Test App">
                        <meta-data android:name="${EnableAndroidPerformanceHintsManifestTaskHelper.ANDROID_PERFORMANCE_HINTS_METADATA_NAME}" android:value="true" />
                    </application>
                </manifest>
                """
            )
        val updatedManifest = createTempOutputFile()

        EnableAndroidPerformanceHintsManifestTaskHelper.processAndroidPerformanceHintsManifest(
            manifestFile = manifestFile,
            updatedManifest = updatedManifest,
            requestedEnableAndroidPerformanceHints = false
        )

        assertEquals("true", findPerformanceHintsMetadataValue(updatedManifest))
        assertEquals(
            manifestFile.readText(),
            updatedManifest.readText(),
            "A manifest that sets the value should be copied unmodified when no flag was passed"
        )
    }

    @Test
    fun manifestFalseWinsOverEnabledDefaultWhenNoExplicitFlag() {
        val manifestFile =
            createTempManifestFile(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="$defaultNamespace">
                    <application android:label="Test App">
                        <meta-data android:name="${EnableAndroidPerformanceHintsManifestTaskHelper.ANDROID_PERFORMANCE_HINTS_METADATA_NAME}" android:value="false" />
                    </application>
                </manifest>
                """
            )
        val updatedManifest = createTempOutputFile()

        EnableAndroidPerformanceHintsManifestTaskHelper.processAndroidPerformanceHintsManifest(
            manifestFile = manifestFile,
            updatedManifest = updatedManifest,
            requestedEnableAndroidPerformanceHints = true
        )

        assertEquals("false", findPerformanceHintsMetadataValue(updatedManifest))
        assertEquals(
            manifestFile.readText(),
            updatedManifest.readText(),
            "A manifest that sets the value should be copied unmodified when no flag was passed"
        )
    }

    @Test
    fun manifestFalseCopiedUnmodifiedWhenDisabledDefaultAndNoExplicitFlag() {
        val manifestFile =
            createTempManifestFile(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="$defaultNamespace">
                    <!-- keep comment -->
                    <application android:label="Test App">
                        <meta-data android:name="${EnableAndroidPerformanceHintsManifestTaskHelper.ANDROID_PERFORMANCE_HINTS_METADATA_NAME}" android:value="false" />
                    </application>
                </manifest>
                """
            )
        val updatedManifest = createTempOutputFile()

        EnableAndroidPerformanceHintsManifestTaskHelper.processAndroidPerformanceHintsManifest(
            manifestFile = manifestFile,
            updatedManifest = updatedManifest,
            requestedEnableAndroidPerformanceHints = false
        )

        assertEquals("false", findPerformanceHintsMetadataValue(updatedManifest))
        assertEquals(manifestFile.readText(), updatedManifest.readText())
    }

    @Test
    fun explicitNoEnableOverridesManifestTrue() {
        val manifestFile =
            createTempManifestFile(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="$defaultNamespace">
                    <application android:label="Test App">
                        <meta-data android:name="${EnableAndroidPerformanceHintsManifestTaskHelper.ANDROID_PERFORMANCE_HINTS_METADATA_NAME}" android:value="true" />
                    </application>
                </manifest>
                """
            )
        val updatedManifest = createTempOutputFile()
        val logger = mockk<Logger>(relaxed = true)

        EnableAndroidPerformanceHintsManifestTaskHelper.processAndroidPerformanceHintsManifest(
            manifestFile = manifestFile,
            updatedManifest = updatedManifest,
            requestedEnableAndroidPerformanceHints = false,
            explicitEnableAndroidPerformanceHints = false,
            logger = logger
        )

        assertEquals("false", findPerformanceHintsMetadataValue(updatedManifest))
        verify(exactly = 1) {
            logger.lifecycle(
                match { message ->
                    message.contains(
                        "${EnableAndroidPerformanceHintsManifestTaskHelper.NO_ENABLE_ANDROID_PERFORMANCE_HINTS_FLAG} overrides"
                    ) &&
                        message.contains("to \"true\"")
                }
            )
        }
    }

    @Test
    fun explicitEnableOverridesManifestFalse() {
        val manifestFile =
            createTempManifestFile(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="$defaultNamespace">
                    <application android:label="Test App">
                        <meta-data android:name="${EnableAndroidPerformanceHintsManifestTaskHelper.ANDROID_PERFORMANCE_HINTS_METADATA_NAME}" android:value="false" />
                    </application>
                </manifest>
                """
            )
        val updatedManifest = createTempOutputFile()
        val logger = mockk<Logger>(relaxed = true)

        EnableAndroidPerformanceHintsManifestTaskHelper.processAndroidPerformanceHintsManifest(
            manifestFile = manifestFile,
            updatedManifest = updatedManifest,
            requestedEnableAndroidPerformanceHints = true,
            explicitEnableAndroidPerformanceHints = true,
            logger = logger
        )

        assertEquals("true", findPerformanceHintsMetadataValue(updatedManifest))
        verify(exactly = 1) {
            logger.lifecycle(
                match { message ->
                    message.contains(
                        "${EnableAndroidPerformanceHintsManifestTaskHelper.ENABLE_ANDROID_PERFORMANCE_HINTS_FLAG} overrides"
                    ) &&
                        message.contains("to \"false\"")
                }
            )
        }
    }

    @Test
    fun saysNothingWhenExplicitFlagMatchesManifest() {
        val manifestFile =
            createTempManifestFile(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="$defaultNamespace">
                    <application android:label="Test App">
                        <meta-data android:name="${EnableAndroidPerformanceHintsManifestTaskHelper.ANDROID_PERFORMANCE_HINTS_METADATA_NAME}" android:value="false" />
                    </application>
                </manifest>
                """
            )
        val updatedManifest = createTempOutputFile()
        val logger = mockk<Logger>(relaxed = true)

        EnableAndroidPerformanceHintsManifestTaskHelper.processAndroidPerformanceHintsManifest(
            manifestFile = manifestFile,
            updatedManifest = updatedManifest,
            requestedEnableAndroidPerformanceHints = false,
            explicitEnableAndroidPerformanceHints = false,
            logger = logger
        )

        assertEquals("false", findPerformanceHintsMetadataValue(updatedManifest))
        assertEquals(
            manifestFile.readText(),
            updatedManifest.readText(),
            "A manifest that already agrees with the flag should be copied unmodified"
        )
        verify(exactly = 0) { logger.lifecycle(any()) }
        verify(exactly = 0) { logger.warn(any()) }
    }

    @Test
    fun explicitEnableWritesTrueWhenAbsentWithoutWarning() {
        val manifestFile =
            createTempManifestFile(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="$defaultNamespace">
                    <application android:label="Test App" />
                </manifest>
                """
            )
        val updatedManifest = createTempOutputFile()
        val logger = mockk<Logger>(relaxed = true)

        EnableAndroidPerformanceHintsManifestTaskHelper.processAndroidPerformanceHintsManifest(
            manifestFile = manifestFile,
            updatedManifest = updatedManifest,
            requestedEnableAndroidPerformanceHints = true,
            explicitEnableAndroidPerformanceHints = true,
            logger = logger
        )

        assertEquals("true", findPerformanceHintsMetadataValue(updatedManifest))
        verify(exactly = 0) { logger.lifecycle(any()) }
        verify(exactly = 0) { logger.warn(any()) }
    }

    @Test
    fun explicitNoEnableWritesFalseWhenAbsentWithoutWarning() {
        val manifestFile =
            createTempManifestFile(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="$defaultNamespace">
                    <application android:label="Test App" />
                </manifest>
                """
            )
        val updatedManifest = createTempOutputFile()
        val logger = mockk<Logger>(relaxed = true)

        EnableAndroidPerformanceHintsManifestTaskHelper.processAndroidPerformanceHintsManifest(
            manifestFile = manifestFile,
            updatedManifest = updatedManifest,
            requestedEnableAndroidPerformanceHints = false,
            explicitEnableAndroidPerformanceHints = false,
            logger = logger
        )

        assertEquals("false", findPerformanceHintsMetadataValue(updatedManifest))
        verify(exactly = 0) { logger.lifecycle(any()) }
        verify(exactly = 0) { logger.warn(any()) }
    }

    @Test
    fun explicitFlagOverridesResourceRef() {
        val manifestFile =
            createTempManifestFile(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="$defaultNamespace">
                    <application android:label="Test App">
                        <meta-data android:name="${EnableAndroidPerformanceHintsManifestTaskHelper.ANDROID_PERFORMANCE_HINTS_METADATA_NAME}" android:value="@bool/enable_adpf" />
                    </application>
                </manifest>
                """
            )
        val updatedManifest = createTempOutputFile()
        val logger = mockk<Logger>(relaxed = true)

        EnableAndroidPerformanceHintsManifestTaskHelper.processAndroidPerformanceHintsManifest(
            manifestFile = manifestFile,
            updatedManifest = updatedManifest,
            requestedEnableAndroidPerformanceHints = false,
            explicitEnableAndroidPerformanceHints = false,
            logger = logger
        )

        assertEquals("false", findPerformanceHintsMetadataValue(updatedManifest))
        verify(exactly = 1) {
            logger.lifecycle(
                match { message ->
                    message.contains(
                        "${EnableAndroidPerformanceHintsManifestTaskHelper.NO_ENABLE_ANDROID_PERFORMANCE_HINTS_FLAG} overrides"
                    ) &&
                        message.contains("to \"@bool/enable_adpf\"")
                }
            )
        }
    }

    @Test
    fun addsApplicationElementWhenAbsent() {
        val manifestFile =
            createTempManifestFile(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="$defaultNamespace">
                </manifest>
                """
            )
        val updatedManifest = createTempOutputFile()

        EnableAndroidPerformanceHintsManifestTaskHelper.processAndroidPerformanceHintsManifest(
            manifestFile = manifestFile,
            updatedManifest = updatedManifest,
            requestedEnableAndroidPerformanceHints = false
        )

        assertEquals("false", findPerformanceHintsMetadataValue(updatedManifest))
    }

    @Test
    fun keepsOtherMetadataIntact() {
        val manifestFile =
            createTempManifestFile(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="$defaultNamespace">
                    <application android:label="Test App">
                        <meta-data android:name="io.flutter.embedding.android.EnableImpeller" android:value="true" />
                        <activity android:name=".MainActivity" android:exported="true" />
                    </application>
                </manifest>
                """
            )
        val updatedManifest = createTempOutputFile()

        EnableAndroidPerformanceHintsManifestTaskHelper.processAndroidPerformanceHintsManifest(
            manifestFile = manifestFile,
            updatedManifest = updatedManifest,
            requestedEnableAndroidPerformanceHints = false
        )

        val parsed = groovy.xml.XmlParser(false, false).parse(updatedManifest)
        val appNode = parsed.children().filterIsInstance<Node>().first { it.name() == "application" }
        val metadataByName =
            appNode
                .children()
                .filterIsInstance<Node>()
                .filter { it.name() == "meta-data" }
                .associate { it.attribute("android:name").toString() to it.attribute("android:value").toString() }

        assertEquals("true", metadataByName["io.flutter.embedding.android.EnableImpeller"])
        assertEquals(
            "false",
            metadataByName[EnableAndroidPerformanceHintsManifestTaskHelper.ANDROID_PERFORMANCE_HINTS_METADATA_NAME]
        )
    }
}
