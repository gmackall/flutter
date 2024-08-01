package com.flutter.gradle;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFiles;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import groovy.lang.Closure;

public class FlutterTask extends BaseFlutterTask {
    @OutputDirectory
    public File getOutputDirectory() {
        return getIntermediateDir();
    }

    @Internal
    public String getAssetsDirectory() {
        return String.valueOf(getOutputDirectory()) + "/flutter_assets";
    }

    @Internal
    public CopySpec getAssets() {
        return getProject().copySpec(copySpec -> {
            copySpec.from(getIntermediateDir()); // Assuming getIntermediateDir() is available
            copySpec.include("flutter_assets/**");
        });
    }

    @Internal
    public CopySpec getSnapshots() {
        return getProject().copySpec(copySpec -> {
            copySpec.from(getIntermediateDir());

            String buildMode = getBuildMode();
            if (buildMode.equals("release") || buildMode.equals("profile")) {
                List<String> targetPlatformValues = getTargetPlatformValues(); // Assuming getTargetPlatformValues() is available

                for (String targetArch : targetPlatformValues) {
                    copySpec.include(FlutterPlugin.PLATFORM_ARCH_MAP.get(targetArch) + "/app.so");
                }
            }
        });
    }

    // TODO(gmackall): See how gemini did here :)
    FileCollection readDependencies(File dependenciesFile, boolean inputs) {
        if (dependenciesFile.exists()) {
            try {
                String depText = Files.readString(dependenciesFile.toPath());
                String[] parts = depText.split(": ");
                String matcherString = inputs ? parts[1] : parts[0];

                Pattern pattern = Pattern.compile("(\\\\ |[^\\s])+");
                Matcher matcher = pattern.matcher(matcherString);

                List<String> depList = new ArrayList<>();
                while (matcher.find()) {
                    depList.add(matcher.group(0).replaceAll("\\\\ ", " "));
                }
                return getProject().files(depList);
            } catch (IOException e) {
                // Handle the IOException appropriately (e.g., log an error or throw an exception)
                getLogger().error("Error reading dependencies file: " + e.getMessage());
            }
        }
        return getProject().files();
    }

    @InputFiles
    public FileCollection getSourceFiles() {
        FileCollection sources = getProject().files();
        for (File depfile : getDependenciesFiles()) {
            sources = sources.plus(readDependencies(depfile, true));
        }

        return sources.plus(getProject().files("pubspec.yaml"));
    }

    @OutputFiles
    public FileCollection getOutputFiles() {
        FileCollection sources = getProject().files();
        for (File depfile : getDependenciesFiles()) {
            sources = sources.plus(readDependencies(depfile, false));
        }

        return sources;
    }

    @TaskAction
    public void build() {
        buildBundle();
    }
}
