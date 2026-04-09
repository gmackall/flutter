pluginManagement {
    val (flutterSdkPath, agpVersion) =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            val agpVersion = providers.gradleProperty("agp.version").orNull
                ?: properties.getProperty("agp.version")
                ?: "8.11.1"
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            Pair(flutterSdkPath, agpVersion)
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.library") {
                useVersion(agpVersion)
            }
        }
    }

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
rootProject.name = "sample_plugin"
