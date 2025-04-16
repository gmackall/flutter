package com.flutter.gradle

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

class ModulePluginLoaderAlt : Plugin<Settings> {
    override fun apply(target: Settings) {
        println("hi gray in modulePluginLoaderAlt")
    }
}
