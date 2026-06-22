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

rootProject.name = "Edge2Remote"
include(":relay")
// :app porte le plugin Android → exige le SDK. On l'exclut quand on ne build que
// le relais (build Docker fly.io : `RELAY_ONLY=1`), pour ne pas avoir besoin du
// SDK Android dans l'image.
if (System.getenv("RELAY_ONLY") != "1") {
    include(":app")
}
