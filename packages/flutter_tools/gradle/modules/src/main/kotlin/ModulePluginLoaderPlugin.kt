package com.flutter.gradle.modules

import com.flutter.gradle.shared.NativePluginLoaderConverted
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import java.io.File

class ModulePluginLoaderPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        println(settings.settingsDir.absolutePath)
        val moduleProjectRoot =
            settings
                .project(":flutter")
                .projectDir.parentFile.parentFile
        val nativePlugins = NativePluginLoaderConverted.getPlugins(moduleProjectRoot)

        nativePlugins.forEach { androidPlugin ->
            val pluginDirectory = File(androidPlugin["path"] as String, "android")
            check(pluginDirectory.exists())
            settings.include(":${androidPlugin["name"]}")
            settings
                .project(":${androidPlugin["name"]}")
                .projectDir = pluginDirectory
        }

        val flutterModulePath =
            settings
                .project(":flutter")
                .projectDir.parentFile.absolutePath
        val nativePluginNames = nativePlugins.mapNotNull { it["name"] as? String }.toSet()
        settings.gradle.projectsLoaded {
            this.rootProject.beforeEvaluate {
                this.subprojects {
                    if (this.name in nativePluginNames) {
                        val androidPluginBuildOutputDir =
                            File(flutterModulePath + File.separator + "plugins_build_output" + File.separator + this.name)
                        if (!androidPluginBuildOutputDir.exists()) {
                            androidPluginBuildOutputDir.mkdirs()
                        }
                        this.layout.buildDirectory.fileValue(androidPluginBuildOutputDir)
                    }
                }
            }
//            val mainModuleName = settings.bind.variables['mainModuleName'] TODO, but I don't think this is used?
//            if (_mainModuleName != null && !_mainModuleName.empty) {
//                p.ext.mainModuleName = _mainModuleName
//            }
            this.rootProject.afterEvaluate {
                this.subprojects {
                    if (this.name != "flutter") {
                        this.evaluationDependsOn(":flutter")
                    }
                }
            }
        }
    }
}
