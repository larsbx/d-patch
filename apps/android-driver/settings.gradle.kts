// Module layout from Section 25.1. Every module exists from Slice 0 so a later
// slice adds code to a named place rather than inventing structure under
// deadline. The `infra:*` modules are the vendor-containment boundary Section
// 28.4 requires: Google SDK types must not escape them.

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
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "dispatch-driver"

include(":app")
include(":core:model")
include(":core:network")
include(":core:database")
include(":core:auth")
include(":core:designsystem")
include(":feature:home")
include(":feature:status")
include(":feature:map")
include(":feature:inbox")
include(":feature:approvals")
include(":feature:settings")
include(":service:location")
include(":service:notifications")
include(":infra:googlemaps")
include(":infra:face")
