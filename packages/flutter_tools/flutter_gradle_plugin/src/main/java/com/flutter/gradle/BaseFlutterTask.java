package com.flutter.gradle;

import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.provider.sources.process.ExecSpecFactory;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFiles;
import org.gradle.process.ExecSpec;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import groovy.lang.Closure;
import groovy.lang.GString;
import groovy.lang.Reference;

public abstract class BaseFlutterTask extends DefaultTask {
    @OutputFiles
    public FileCollection getDependenciesFiles() {
        FileCollection depfiles = getProject().files();

        // Includes all sources used in the flutter compilation.
        depfiles = depfiles.plus(getProject().files(String.valueOf(getIntermediateDir()) + "/flutter_build.d"));
        return depfiles;
    }

    public void buildBundle() {
        if (!sourceDir.isDirectory()) {
            throw new GradleException("Invalid Flutter source directory: " + String.valueOf(getSourceDir()));
        }


        intermediateDir.mkdirs();

        // Compute the rule name for flutter assemble. To speed up builds that contain
        // multiple ABIs, the target name is used to communicate which ones are required
        // rather than the TargetPlatform. This allows multiple builds to share the same
        // cache.
        List<String> ruleNames = null;
        if (buildMode.equals("debug")) {
            ruleNames = new ArrayList<>(List.of("debug_android_application"));
        } else if (deferredComponents) {
            List<String> l = new ArrayList<>();
            for (String s : targetPlatformValues) {
                l.add("android_aot_deferred_components_bundle_" + buildMode + "_" + s + ")");
            }
            ruleNames = l;
        } else {
            List<String> l = new ArrayList<>();
            for (String s : targetPlatformValues) {
                l.add("android_aot_bundle_" + buildMode + "_" + s + ")");
            }
            ruleNames = l;
        }

        final List<String> finalRuleNames = ruleNames;
        getProject().exec(execSpec -> {
            execSpec.setExecutable(getFlutterExecutable());
            execSpec.setWorkingDir(getSourceDir());
            if (localEngine != null) {
                execSpec.args("--local-engine", localEngine);
                execSpec.args("--local-engine-src-path", localEngineSrcPath);
            }
            if (localEngineHost != null) {
                execSpec.args("--local-engine-host", localEngineHost);
            }
            if (verbose) {
                execSpec.args("--verbose");
            } else {
                execSpec.args("--quiet");
            }
            execSpec.args("assemble");
            execSpec.args("--no-version-check");
            execSpec.args("--depfile", intermediateDir + "/flutter_build.d");
            execSpec.args("--output", intermediateDir.toString());
            if (performanceMeasurementFile != null) {
                execSpec.args("--performance-measurement-file=" + performanceMeasurementFile);
            }
            if (!fastStart || !buildMode.equals("debug")) {
                execSpec.args("-dTargetFile=" + targetPath);
            } else {
                execSpec.args("-dTargetFile=" + Paths.get(flutterRoot.toPath().toString(), "examples", "splash", "lib", "main.dart"));
            }
            // By gemini, double check
            execSpec.args("-dTargetPlatform=android",
                    "-dBuildMode=" + buildMode);

            if (trackWidgetCreation != null) {
                execSpec.args("-dTrackWidgetCreation=" + trackWidgetCreation);
            }

            if (splitDebugInfo != null) {
                execSpec.args("-dSplitDebugInfo=" + splitDebugInfo);
            }

            if (treeShakeIcons) {
                execSpec.args("-dTreeShakeIcons=true");
            }

            if (dartObfuscation) {
                execSpec.args("-dDartObfuscation=true");
            }

            if (dartDefines != null) {
                execSpec.args("--DartDefines=" + dartDefines);
            }

            if (bundleSkSLPath != null) {
                execSpec.args("-dBundleSkSLPath=" + bundleSkSLPath);
            }

            if (codeSizeDirectory != null) {
                execSpec.args("-dCodeSizeDirectory=" + codeSizeDirectory);
            }

            if (flavor != null) {
                execSpec.args("-dFlavor=" + flavor);
            }

            if (extraGenSnapshotOptions != null) {
                execSpec.args("--ExtraGenSnapshotOptions=" + extraGenSnapshotOptions);
            }

            if (frontendServerStarterPath != null) {
                execSpec.args("-dFrontendServerStarterPath=" + frontendServerStarterPath);
            }

            if (extraFrontEndOptions != null) {
                execSpec.args("--ExtraFrontEndOptions=" + extraFrontEndOptions);
            }

            execSpec.args("-dAndroidArchs=" + String.join(" ", targetPlatformValues));
            execSpec.args("-dMinSdkVersion=" + minSdkVersion);
            execSpec.args(finalRuleNames);
        });
    }

    public File getFlutterRoot() {
        return flutterRoot;
    }

    public void setFlutterRoot(File flutterRoot) {
        this.flutterRoot = flutterRoot;
    }

    public File getFlutterExecutable() {
        return flutterExecutable;
    }

    public void setFlutterExecutable(File flutterExecutable) {
        this.flutterExecutable = flutterExecutable;
    }

    public String getBuildMode() {
        return buildMode;
    }

    public void setBuildMode(String buildMode) {
        this.buildMode = buildMode;
    }

    public int getMinSdkVersion() {
        return minSdkVersion;
    }

    public void setMinSdkVersion(int minSdkVersion) {
        this.minSdkVersion = minSdkVersion;
    }

    public String getLocalEngine() {
        return localEngine;
    }

    public void setLocalEngine(String localEngine) {
        this.localEngine = localEngine;
    }

    public String getLocalEngineHost() {
        return localEngineHost;
    }

    public void setLocalEngineHost(String localEngineHost) {
        this.localEngineHost = localEngineHost;
    }

    public String getLocalEngineSrcPath() {
        return localEngineSrcPath;
    }

    public void setLocalEngineSrcPath(String localEngineSrcPath) {
        this.localEngineSrcPath = localEngineSrcPath;
    }

    public Boolean getFastStart() {
        return fastStart;
    }

    public void setFastStart(Boolean fastStart) {
        this.fastStart = fastStart;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    public Boolean getVerbose() {
        return verbose;
    }

    public void setVerbose(Boolean verbose) {
        this.verbose = verbose;
    }

    public String[] getFileSystemRoots() {
        return fileSystemRoots;
    }

    public void setFileSystemRoots(String[] fileSystemRoots) {
        this.fileSystemRoots = fileSystemRoots;
    }

    public String getFileSystemScheme() {
        return fileSystemScheme;
    }

    public void setFileSystemScheme(String fileSystemScheme) {
        this.fileSystemScheme = fileSystemScheme;
    }

    public Boolean getTrackWidgetCreation() {
        return trackWidgetCreation;
    }

    public void setTrackWidgetCreation(Boolean trackWidgetCreation) {
        this.trackWidgetCreation = trackWidgetCreation;
    }

    public List<String> getTargetPlatformValues() {
        return targetPlatformValues;
    }

    public void setTargetPlatformValues(List<String> targetPlatformValues) {
        this.targetPlatformValues = targetPlatformValues;
    }

    public File getSourceDir() {
        return sourceDir;
    }

    public void setSourceDir(File sourceDir) {
        this.sourceDir = sourceDir;
    }

    public File getIntermediateDir() {
        return intermediateDir;
    }

    public void setIntermediateDir(File intermediateDir) {
        this.intermediateDir = intermediateDir;
    }

    public String getFrontendServerStarterPath() {
        return frontendServerStarterPath;
    }

    public void setFrontendServerStarterPath(String frontendServerStarterPath) {
        this.frontendServerStarterPath = frontendServerStarterPath;
    }

    public String getExtraFrontEndOptions() {
        return extraFrontEndOptions;
    }

    public void setExtraFrontEndOptions(String extraFrontEndOptions) {
        this.extraFrontEndOptions = extraFrontEndOptions;
    }

    public String getExtraGenSnapshotOptions() {
        return extraGenSnapshotOptions;
    }

    public void setExtraGenSnapshotOptions(String extraGenSnapshotOptions) {
        this.extraGenSnapshotOptions = extraGenSnapshotOptions;
    }

    public String getSplitDebugInfo() {
        return splitDebugInfo;
    }

    public void setSplitDebugInfo(String splitDebugInfo) {
        this.splitDebugInfo = splitDebugInfo;
    }

    public Boolean getTreeShakeIcons() {
        return treeShakeIcons;
    }

    public void setTreeShakeIcons(Boolean treeShakeIcons) {
        this.treeShakeIcons = treeShakeIcons;
    }

    public Boolean getDartObfuscation() {
        return dartObfuscation;
    }

    public void setDartObfuscation(Boolean dartObfuscation) {
        this.dartObfuscation = dartObfuscation;
    }

    public String getDartDefines() {
        return dartDefines;
    }

    public void setDartDefines(String dartDefines) {
        this.dartDefines = dartDefines;
    }

    public String getBundleSkSLPath() {
        return bundleSkSLPath;
    }

    public void setBundleSkSLPath(String bundleSkSLPath) {
        this.bundleSkSLPath = bundleSkSLPath;
    }

    public String getCodeSizeDirectory() {
        return codeSizeDirectory;
    }

    public void setCodeSizeDirectory(String codeSizeDirectory) {
        this.codeSizeDirectory = codeSizeDirectory;
    }

    public String getPerformanceMeasurementFile() {
        return performanceMeasurementFile;
    }

    public void setPerformanceMeasurementFile(String performanceMeasurementFile) {
        this.performanceMeasurementFile = performanceMeasurementFile;
    }

    public Boolean getDeferredComponents() {
        return deferredComponents;
    }

    public void setDeferredComponents(Boolean deferredComponents) {
        this.deferredComponents = deferredComponents;
    }

    public Boolean getValidateDeferredComponents() {
        return validateDeferredComponents;
    }

    public void setValidateDeferredComponents(Boolean validateDeferredComponents) {
        this.validateDeferredComponents = validateDeferredComponents;
    }

    public Boolean getSkipDependencyChecks() {
        return skipDependencyChecks;
    }

    public void setSkipDependencyChecks(Boolean skipDependencyChecks) {
        this.skipDependencyChecks = skipDependencyChecks;
    }

    public String getFlavor() {
        return flavor;
    }

    public void setFlavor(String flavor) {
        this.flavor = flavor;
    }

    @Internal
    private File flutterRoot;
    @Internal
    private File flutterExecutable;
    @Input
    private String buildMode;
    @Input
    private int minSdkVersion;
    @Optional
    @Input
    private String localEngine;
    @Optional
    @Input
    private String localEngineHost;
    @Optional
    @Input
    private String localEngineSrcPath;
    @Optional
    @Input
    private Boolean fastStart;
    @Input
    private String targetPath;
    @Optional
    @Input
    private Boolean verbose;
    @Optional
    @Input
    private String[] fileSystemRoots;
    @Optional
    @Input
    private String fileSystemScheme;
    @Input
    private Boolean trackWidgetCreation;
    @Optional
    @Input
    private List<String> targetPlatformValues;
    @Internal
    private File sourceDir;
    @Internal
    private File intermediateDir;
    @Optional
    @Input
    private String frontendServerStarterPath;
    @Optional
    @Input
    private String extraFrontEndOptions;
    @Optional
    @Input
    private String extraGenSnapshotOptions;
    @Optional
    @Input
    private String splitDebugInfo;
    @Optional
    @Input
    private Boolean treeShakeIcons;
    @Optional
    @Input
    private Boolean dartObfuscation;
    @Optional
    @Input
    private String dartDefines;
    @Optional
    @Input
    private String bundleSkSLPath;
    @Optional
    @Input
    private String codeSizeDirectory;
    @Optional
    @Input
    private String performanceMeasurementFile;
    @Optional
    @Input
    private Boolean deferredComponents;
    @Optional
    @Input
    private Boolean validateDeferredComponents;
    @Optional
    @Input
    private Boolean skipDependencyChecks;
    @Optional
    @Input
    private String flavor;
}
