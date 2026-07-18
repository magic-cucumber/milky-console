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

include(":plugin:loader")
include(":plugin:protocol")
include(":plugin:api")
include(":plugin:processor")
include(":plugin:sample")

include(":utils:pipe")
include(":utils:dlloader")
include(":utils:logger")
include(":utils:process")
include(":utils:event-bus")
