dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared")
println("settings dir + " + settingsDir.absolutePath)
project(":shared").projectDir = File(settingsDir, "../shared/")
