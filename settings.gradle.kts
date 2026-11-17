pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "WatchSleepAlarm"
include(":phone", ":wear", ":core:model", ":core:analysis", ":core:settings", ":core:alarm", ":core:sensors", ":core:health", ":core:sync")

include(":core:update")

include(":core:diagnostics")
