pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com.android.*")
                includeGroupByRegex("com.google.*")
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
    }
}

rootProject.name = "TihwinNew"
include(":app", ":libaums-core", ":libaums-httpserver", ":libaums-storageprovider", ":libaums-libusbcommunication", ":libaums-javafs")

project(":libaums-core").projectDir = file("libaums/libaums")
project(":libaums-httpserver").projectDir = file("libaums/httpserver")
project(":libaums-storageprovider").projectDir = file("libaums/storageprovider")
project(":libaums-libusbcommunication").projectDir = file("libaums/libusbcommunication")
project(":libaums-javafs").projectDir = file("libaums/javafs")

 