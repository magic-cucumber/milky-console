rootProject.name = "milky-console"

pluginManagement {
    repositories {
        google {
            content {
              	includeGroupByRegex("com\\.android.*")
              	includeGroupByRegex("com\\.google.*")
              	includeGroupByRegex("androidx.*")
              	includeGroupByRegex("android.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
              	includeGroupByRegex("com\\.android.*")
              	includeGroupByRegex("com\\.google.*")
              	includeGroupByRegex("androidx.*")
              	includeGroupByRegex("android.*")
            }
        }
        mavenCentral()
    }
}
include(":core")
include(":core-utils")

include(":plugin:loader")
include(":plugin:protocol")
include(":plugin:api")
include(":plugin:sample")

include(":processor:protocol")
include(":processor:core")

include(":utils:pipe")
include(":utils:dlloader")
include(":utils:logger")
include(":utils:process")
include(":utils:event-bus")
include(":utils:file-watcher")
