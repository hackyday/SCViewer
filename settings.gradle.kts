pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SCViewer"
include(":app")

// Foojay toolchain resolver: lets Gradle auto-provision a JDK 21 if the
// system Java is missing jlink / is the wrong version. Must be applied in
// the settings script — it's a settings plugin, not a project plugin.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
