package com.flutter.gradle;

import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.gradle.api.GradleException;

/**
 * For apps only. Provides the flutter extension used in the app-level Gradle
 * build file (app/build.gradle or app/build.gradle.kts).
 * <p>
 * The versions specified here should match the values in
 * packages/flutter_tools/lib/src/android/gradle_utils.dart, so when bumping,
 * make sure to update the versions specified there.
 * <p>
 * Learn more about extensions in Gradle:
 * * https://docs.gradle.org/8.0.2/userguide/custom_plugins.html#sec:getting_input_from_the_build
 */
public class FlutterExtension {

    // Gradle requires an explicit empty constructor.
    public FlutterExtension() {}

    /**
     * Returns flutterVersionCode as an integer with error handling.
     */
    public Integer getVersionCode() {
        if (flutterVersionCode == null) {
            throw new GradleException("flutterVersionCode must not be null.");
        }


        // TDOO(gmackall): Make this pure java.
        if (!StringGroovyMethods.isNumber(flutterVersionCode)) {
            throw new GradleException("flutterVersionCode must be an integer.");
        }


        return StringGroovyMethods.toInteger(flutterVersionCode);
    }

    /**
     * Returns flutterVersionName with error handling.
     */
    public String getVersionName() {
        if (flutterVersionName == null) {
            throw new GradleException("flutterVersionName must not be null.");
        }


        return flutterVersionName;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    /**
     * Sets the compileSdkVersion used by default in Flutter app projects.
     */
    public final int compileSdkVersion = 34;
    /**
     * Sets the minSdkVersion used by default in Flutter app projects.
     */
    public final int minSdkVersion = 21;
    /**
     * Sets the targetSdkVersion used by default in Flutter app projects.
     * targetSdkVersion should always be the latest available stable version.
     * <p>
     * See https://developer.android.com/guide/topics/manifest/uses-sdk-element.
     */
    public final int targetSdkVersion = 34;
    /**
     * Sets the ndkVersion used by default in Flutter app projects.
     * Chosen as default version of the AGP version below as found in
     * https://developer.android.com/studio/projects/install-ndk#default-ndk-per-agp.
     */
    public final String ndkVersion = "23.1.7779620";
    /**
     * Specifies the relative directory to the Flutter project directory.
     * In an app project, this is ../.. since the app's Gradle build file is under android/app.
     */
    private String source = "../..";
    /**
     * Allows to override the target file. Otherwise, the target is lib/main.dart.
     */
    private String target;
    /**
     * The versionCode that was read from app's local.properties.
     */
    public String flutterVersionCode = null;
    /**
     * The versionName that was read from app's local.properties.
     */
    public String flutterVersionName = null;
}
