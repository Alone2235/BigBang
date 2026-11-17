pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Xposed 经典 API 82（LSPosed 兼容）
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "BigBang"
include(":app")
