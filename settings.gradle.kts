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
        // Tesseract4Android (Apache-2.0) is only published on JitPack.
        // The repository is restricted to that single group.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.adaptech-cz.Tesseract4Android") }
        }
    }
}

rootProject.name = "KiadasKoveto"

include(
    ":app",
    ":core",
    ":receipt-parser",
    ":database",
    ":ocr",
    ":local-ai",
)
