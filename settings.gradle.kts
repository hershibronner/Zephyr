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

rootProject.name = "Zephyr"

// `core` is a standalone Kotlin/JVM build with zero Android dependencies, wired in as a
// composite build. Keeping it separate means `gradle -p core test` runs the domain logic
// anywhere a JDK and Maven Central are available, with no Android SDK involved.
includeBuild("core")

include(":app")
