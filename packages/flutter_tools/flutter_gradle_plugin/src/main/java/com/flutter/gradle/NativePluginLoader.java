package com.flutter.gradle;

import org.gradle.api.GradleException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import groovy.json.JsonSlurper;

class NativePluginLoader {

    // Gradle requires an explicit empty constructor.
    public NativePluginLoader() {}

    // This string must match _kFlutterPluginsHasNativeBuildKey defined in
    // packages/flutter_tools/lib/src/flutter_plugins.dart.
    static final String nativeBuildKey = "native_build";
    static final String flutterPluginsDependenciesFile = ".flutter-plugins-dependencies";

    /**
     * Gets the list of plugins that support the Android platform.
     * The list contains map elements with the following content:
     * {
     *     "name": "plugin-a",
     *     "path": "/path/to/plugin-a",
     *     "dependencies": ["plugin-b", "plugin-c"],
     *     "native_build": true
     * }
     *
     * Therefore the map value can either be a `String`, a `List<String>` or a `boolean`.
     */
    List<Map<String, Object>> getPlugins(File flutterSourceDirectory) {
        List<Map<String, Object>> nativePlugins = new ArrayList<>();
        Map<String, Object> meta = getDependenciesMetadata(flutterSourceDirectory);
        if (meta == null) {
            return nativePlugins;
        }

        assert meta.get("plugins") instanceof Map;
        Map<String, Object> plugins = (Map<String, Object>) meta.get("plugins");
        assert plugins.get("android") instanceof List;
        List<Map<String, Object>> androidPlugins = (List<Map<String, Object>>) plugins.get("android");

        for (Map<String, Object> androidPlugin : androidPlugins) {
            // Type assertions
            assert androidPlugin.get("name") instanceof String;
            assert androidPlugin.get("path") instanceof String;
            assert androidPlugin.get("dependencies") instanceof List;

            // Check if the plugin needs to be built (default to true)
            boolean needsBuild = true;
            if (androidPlugin.containsKey(nativeBuildKey)) {
                needsBuild = (Boolean) androidPlugin.get(nativeBuildKey);
            }

            if (needsBuild) {
                nativePlugins.add(androidPlugin);
            }
        }
        return nativePlugins;
    }



    private Map<String, Object> parsedFlutterPluginsDependencies;

    /**
     * Parses <project-src>/.flutter-plugins-dependencies
     */
    Map<String, Object> getDependenciesMetadata(File flutterSourceDirectory) {
        // Consider a `.flutter-plugins-dependencies` file with the following content:
        // {
        //     "plugins": {
        //       "android": [
        //         {
        //           "name": "plugin-a",
        //           "path": "/path/to/plugin-a",
        //           "dependencies": ["plugin-b", "plugin-c"],
        //           "native_build": true
        //         },
        //         {
        //           "name": "plugin-b",
        //           "path": "/path/to/plugin-b",
        //           "dependencies": ["plugin-c"],
        //           "native_build": true
        //         },
        //         {
        //           "name": "plugin-c",
        //           "path": "/path/to/plugin-c",
        //           "dependencies": [],
        //           "native_build": true
        //         },
        //       ],
        //     },
        //     "dependencyGraph": [
        //       {
        //         "name": "plugin-a",
        //         "dependencies": ["plugin-b","plugin-c"]
        //       },
        //       {
        //         "name": "plugin-b",
        //         "dependencies": ["plugin-c"]
        //       },
        //       {
        //         "name": "plugin-c",
        //         "dependencies": []
        //       }
        //     ]
        // }
        // This means, `plugin-a` depends on `plugin-b` and `plugin-c`.
        // `plugin-b` depends on `plugin-c`.
        // `plugin-c` doesn't depend on anything.
        if (parsedFlutterPluginsDependencies != null) {
            return parsedFlutterPluginsDependencies;
        }
        File pluginsDependencyFile = new File(flutterSourceDirectory, flutterPluginsDependenciesFile);
        if (pluginsDependencyFile.exists()) {
            try {
                Object object = new JsonSlurper().parseText(Files.readString(pluginsDependencyFile.toPath()));
                assert(object instanceof Map);
                parsedFlutterPluginsDependencies = (Map<String, Object>) object;
                return parsedFlutterPluginsDependencies;
            } catch (IOException e) {
                // TODO(gmackall): do
                throw new GradleException("Failure while parsing old plugin dependency file.", e);
            }
        }
        return null;
    }
}
