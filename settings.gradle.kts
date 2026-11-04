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

rootProject.name = "zapette"

include(
    ":app",
    ":core:model",
    ":protocol:kodi",
    ":protocol:mediaserver",
    ":protocol:plex",
    ":protocol:upnp",
    ":protocol:roku",
    ":protocol:airplay",
    ":protocol:firetv",
    ":feature:cast",
)
