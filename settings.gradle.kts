pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The YouTube extractor (NewPipeExtractor) is published through JitPack
        maven("https://jitpack.io")
    }
}

rootProject.name = "Qeshev"
include(":app")
