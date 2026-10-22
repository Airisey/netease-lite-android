// 网易云音乐 Lite · Android
// 版本与 SDK 由用户指定：minSdk 26 / targetSdk 36 / compileSdk 36 / com.netease.lite
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

rootProject.name = "netease-lite"
include(":app")
