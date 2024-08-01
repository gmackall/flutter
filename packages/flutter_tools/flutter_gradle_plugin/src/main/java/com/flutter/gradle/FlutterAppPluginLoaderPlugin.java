package com.flutter.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.initialization.Settings;
import org.gradle.api.plugins.ExtraPropertiesExtension;

import java.io.File;
import java.util.List;
import java.util.Map;

class FlutterAppPluginLoaderPlugin implements Plugin<Settings> {
    @Override
    public void apply(Settings settings) {
        File flutterProjectRoot = settings.getSettingsDir().getParentFile();

        // Create a Gradle extension "nativePluginLoader", and store it in the extra properties
        // for retrieval in the main Flutter Gradle Plugin.
        NativePluginLoader nativePluginLoader = settings.getExtensions().create("nativePluginLoader", NativePluginLoader.class);
        settings.getGradle().getExtensions().getByType(ExtraPropertiesExtension.class).set("nativePluginLoader", nativePluginLoader);
        //NativePluginLoader nativePluginLoader = settings.getGradle().getRootProject().getExtensions().create("nativePluginLoader", NativePluginLoader.class);

        List<Map<String, Object>> nativePlugins = nativePluginLoader.getPlugins(flutterProjectRoot);
        for (Map<String, Object> androidPlugin : nativePlugins) {
            File pluginDirectory = new File((String) androidPlugin.get("path"), "android");
            assert pluginDirectory.exists();
            settings.include(":" + androidPlugin.get("name"));
            settings.project(":" + androidPlugin.get("name")).setProjectDir(pluginDirectory);
        }
    }

}
