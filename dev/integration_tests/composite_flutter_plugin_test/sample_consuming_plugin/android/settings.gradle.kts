pluginManagement {
    val flutterSdkPath =
        run {
            // A migrated plugin is built standalone by the Flutter tool, so its android directory
            // has no generated local.properties. Fall back to FLUTTER_ROOT, which the tool sets in
            // the environment of the plugin build.
            val properties = java.util.Properties()
            val localPropertiesFile = file("local.properties")
            if (localPropertiesFile.exists()) {
                localPropertiesFile.inputStream().use { properties.load(it) }
            }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
                ?: System.getenv("FLUTTER_ROOT")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties and FLUTTER_ROOT not set" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// A migrated plugin pins its own AGP version. It is deliberately NOT unified with the host app's:
// decoupling the plugin's toolchain from the app's is the whole point of building it as an AAR,
// and an AAR built by one AGP version is consumable by any other.
plugins {
    id("com.android.library") version "8.11.1" apply false
}

rootProject.name = "sample_consuming_plugin"
