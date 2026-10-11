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
        // FreeReflection (com.github.tiann) usado pelo motor virtual Bcore.
        maven("https://www.jitpack.io")
    }
}

rootProject.name = "Port Store"
include(":app")
// Motor de virtualização (instalação virtual) — vendored de
// https://github.com/ALEX5402/NewBlackbox @ 89b59836c66f173756a4ae258cf379a957649820 (Apache-2.0)
include(":Bcore")
include(":black-reflection")
include(":compiler")
// Camada de isolamento do motor: o app só enxerga :virtual (interface VirtualEngine).
include(":virtual")
