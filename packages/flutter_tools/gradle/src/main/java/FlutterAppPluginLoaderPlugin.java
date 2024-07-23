import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.initialization.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

class FlutterAppPluginLoaderPlugin implements Plugin<Settings> {
//    @Override
//    public void apply(Settings settings) {
//        File flutterProjectRoot = settings.getSettingsDir().getParentFile();
//
//        if(!settings.ext.hasProperty('flutterSdkPath')) {
//            def properties = new Properties()
//            def localPropertiesFile = new File(settings.rootProject.projectDir, "local.properties")
//            localPropertiesFile.withInputStream { properties.load(it) }
//            settings.ext.flutterSdkPath = properties.getProperty("flutter.sdk")
//            assert settings.ext.flutterSdkPath != null, "flutter.sdk not set in local.properties"
//        }
//
//        // Load shared gradle functions
//        settings.apply from: Paths.get(settings.ext.flutterSdkPath, "packages", "flutter_tools", "gradle", "src", "main", "groovy", "native_plugin_loader.groovy")
//
//        List<Map<String, Object>> nativePlugins = settings.ext.nativePluginLoader.getPlugins(flutterProjectRoot)
//        nativePlugins.each { androidPlugin ->
//                def pluginDirectory = new File(androidPlugin.path as String, 'android')
//            assert pluginDirectory.exists()
//            settings.include(":${androidPlugin.name}")
//            settings.project(":${androidPlugin.name}").projectDir = pluginDirectory
//        }
//    }
    @Override
    public void apply(Settings settings) {
        File flutterProjectRoot = settings.getSettingsDir().getParentFile();

        // Retrieve Flutter SDK path
        if (!settings.getExtensions().getExtraProperties().has("flutterSdkPath")) {
            Properties properties = new Properties();
            File localPropertiesFile = new File(settings.getRootProject().getProjectDir(), "local.properties");
            try (FileInputStream inputStream = new FileInputStream(localPropertiesFile)) {
                properties.load(inputStream);
                String flutterSdkPath = properties.getProperty("flutter.sdk");
                settings.getExtensions().add("flutterSdkPath", flutterSdkPath);
                if (flutterSdkPath == null) {
                    throw new GradleException("flutter.sdk not set in local.properties");
                }
            } catch (IOException e) {
                // Handle IOException appropriately
                e.printStackTrace();
            }
        }

        // Load shared Gradle functions
        String flutterSdkPath = (String) settings.getExtensions().getByName("flutterSdkPath");
        // Construct the path to 'native_plugin_loader.groovy' using Path
        Path nativePluginLoaderPath = Paths.get(flutterSdkPath, "packages", "flutter_tools", "gradle", "src", "main", "groovy", "native_plugin_loader.groovy");
        Map<String, String> options = new HashMap<>();
        options.put("from", nativePluginLoaderPath.toString());
        settings.apply(options);

        // Process native plugins
        NativePluginLoader nativePluginLoader = (NativePluginLoader) settings.getExtensions().getByName("nativePluginLoader");
        List<Map<String, Object>> nativePlugins = nativePluginLoader.getPlugins(flutterProjectRoot);
        for (Map<String, Object> androidPlugin : nativePlugins) {
            File pluginDirectory = new File((String) androidPlugin.get("path"), "android");
            assert pluginDirectory.exists();
            settings.include(":" + androidPlugin.get("name"));
            settings.project(":" + androidPlugin.get("name")).setProjectDir(pluginDirectory);
        }
    }

}
