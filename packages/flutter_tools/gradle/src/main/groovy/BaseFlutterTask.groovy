import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFiles

import java.nio.file.Paths

abstract class BaseFlutterTask extends DefaultTask {

    @Internal
    File flutterRoot

    @Internal
    File flutterExecutable

    @Input
    String buildMode

    @Input
    int minSdkVersion

    @Optional @Input
    String localEngine

    @Optional @Input
    String localEngineHost

    @Optional @Input
    String localEngineSrcPath

    @Optional @Input
    Boolean fastStart

    @Input
    String targetPath

    @Optional @Input
    Boolean verbose

    @Optional @Input
    String[] fileSystemRoots

    @Optional @Input
    String fileSystemScheme

    @Input
    Boolean trackWidgetCreation

    @Optional @Input
    List<String> targetPlatformValues

    @Internal
    File sourceDir

    @Internal
    File intermediateDir

    @Optional @Input
    String frontendServerStarterPath

    @Optional @Input
    String extraFrontEndOptions

    @Optional @Input
    String extraGenSnapshotOptions

    @Optional @Input
    String splitDebugInfo

    @Optional @Input
    Boolean treeShakeIcons

    @Optional @Input
    Boolean dartObfuscation

    @Optional @Input
    String dartDefines

    @Optional @Input
    String bundleSkSLPath

    @Optional @Input
    String codeSizeDirectory

    @Optional @Input
    String performanceMeasurementFile

    @Optional @Input
    Boolean deferredComponents

    @Optional @Input
    Boolean validateDeferredComponents

    @Optional @Input
    Boolean skipDependencyChecks
    @Optional @Input
    String flavor

    @OutputFiles
    FileCollection getDependenciesFiles() {
        FileCollection depfiles = project.files()

        // Includes all sources used in the flutter compilation.
        depfiles += project.files("${intermediateDir}/flutter_build.d")
        return depfiles
    }

    void buildBundle() {
        if (!sourceDir.isDirectory()) {
            throw new GradleException("Invalid Flutter source directory: ${sourceDir}")
        }

        intermediateDir.mkdirs()

        // Compute the rule name for flutter assemble. To speed up builds that contain
        // multiple ABIs, the target name is used to communicate which ones are required
        // rather than the TargetPlatform. This allows multiple builds to share the same
        // cache.
        String[] ruleNames
        if (buildMode == "debug") {
            ruleNames = ["debug_android_application"]
        } else if (deferredComponents) {
            ruleNames = targetPlatformValues.collect { "android_aot_deferred_components_bundle_${buildMode}_$it" }
        } else {
            ruleNames = targetPlatformValues.collect { "android_aot_bundle_${buildMode}_$it" }
        }
        project.exec {
            logging.captureStandardError(LogLevel.ERROR)
            executable(flutterExecutable.absolutePath)
            workingDir(sourceDir)
            if (localEngine != null) {
                args "--local-engine", localEngine
                args "--local-engine-src-path", localEngineSrcPath
            }
            if (localEngineHost != null) {
                args "--local-engine-host", localEngineHost
            }
            if (verbose) {
                args "--verbose"
            } else {
                args "--quiet"
            }
            args("assemble")
            args("--no-version-check")
            args("--depfile", "${intermediateDir}/flutter_build.d")
            args("--output", "${intermediateDir}")
            if (performanceMeasurementFile != null) {
                args("--performance-measurement-file=${performanceMeasurementFile}")
            }
            if (!fastStart || buildMode != "debug") {
                args("-dTargetFile=${targetPath}")
            } else {
                args("-dTargetFile=${Paths.get(flutterRoot.absolutePath, "examples", "splash", "lib", "main.dart")}")
            }
            args("-dTargetPlatform=android")
            args("-dBuildMode=${buildMode}")
            if (trackWidgetCreation != null) {
                args("-dTrackWidgetCreation=${trackWidgetCreation}")
            }
            if (splitDebugInfo != null) {
                args("-dSplitDebugInfo=${splitDebugInfo}")
            }
            if (treeShakeIcons == true) {
                args("-dTreeShakeIcons=true")
            }
            if (dartObfuscation == true) {
                args("-dDartObfuscation=true")
            }
            if (dartDefines != null) {
                args("--DartDefines=${dartDefines}")
            }
            if (bundleSkSLPath != null) {
                args("-dBundleSkSLPath=${bundleSkSLPath}")
            }
            if (codeSizeDirectory != null) {
                args("-dCodeSizeDirectory=${codeSizeDirectory}")
            }
            if (flavor != null) {
                args("-dFlavor=${flavor}")
            }
            if (extraGenSnapshotOptions != null) {
                args("--ExtraGenSnapshotOptions=${extraGenSnapshotOptions}")
            }
            if (frontendServerStarterPath != null) {
                args("-dFrontendServerStarterPath=${frontendServerStarterPath}")
            }
            if (extraFrontEndOptions != null) {
                args("--ExtraFrontEndOptions=${extraFrontEndOptions}")
            }
            args("-dAndroidArchs=${targetPlatformValues.join(' ')}")
            args("-dMinSdkVersion=${minSdkVersion}")
            args(ruleNames)
        }
    }

}