import org.gradle.api.file.CopySpec
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction

class FlutterTask extends BaseFlutterTask {

    @OutputDirectory
    File getOutputDirectory() {
        return intermediateDir
    }

    @Internal
    String getAssetsDirectory() {
        return "${outputDirectory}/flutter_assets"
    }

    @Internal
    CopySpec getAssets() {
        return project.copySpec {
            from("${intermediateDir}")
            include("flutter_assets/**") // the working dir and its files
        }
    }

    @Internal
    CopySpec getSnapshots() {
        return project.copySpec {
            from("${intermediateDir}")

            if (buildMode == "release" || buildMode == "profile") {
                targetPlatformValues.each {
                    include("${PLATFORM_ARCH_MAP[targetArch]}/app.so")
                }
            }
        }
    }

    FileCollection readDependencies(File dependenciesFile, Boolean inputs) {
        if (dependenciesFile.exists()) {
            // Dependencies file has Makefile syntax:
            //   <target> <files>: <source> <files> <separated> <by> <non-escaped space>
            String depText = dependenciesFile.text
            // So we split list of files by non-escaped(by backslash) space,
            def matcher = depText.split(": ")[inputs ? 1 : 0] =~ /(\\ |[^\s])+/
            // then we replace all escaped spaces with regular spaces
            def depList = matcher.collect{ it[0].replaceAll("\\\\ ", " ") }
            return project.files(depList)
        }
        return project.files()
    }

    @InputFiles
    FileCollection getSourceFiles() {
        FileCollection sources = project.files()
        for (File depfile in getDependenciesFiles()) {
            sources += readDependencies(depfile, true)
        }
        return sources + project.files("pubspec.yaml")
    }

    @OutputFiles
    FileCollection getOutputFiles() {
        FileCollection sources = project.files()
        for (File depfile in getDependenciesFiles()) {
            sources += readDependencies(depfile, false)
        }
        return sources
    }

    @TaskAction
    void build() {
        buildBundle()
    }

}