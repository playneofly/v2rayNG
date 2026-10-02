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
        maven { url = uri("https://jitpack.io") }

        // FILTERNET: Psiphon publishes its Android library as a Maven
        // repository committed inside its own Git repo rather than to Maven
        // Central, so the URL points at raw.githubusercontent.com. The
        // content filter keeps every other dependency away from it, so a
        // typo elsewhere cannot silently resolve against GitHub.
        maven {
            url = uri("https://raw.githubusercontent.com/Psiphon-Labs/psiphon-tunnel-core-Android-library/master/releases")
            content { includeGroup("ca.psiphon") }
        }
    }
}

rootProject.name = "v2rayNG"
include(":app")
