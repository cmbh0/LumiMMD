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

rootProject.name = "LumiMMD"
include(":engine", ":app")

// 可选：桌面无头渲染/回归测试工具（纯 JVM + LWJGL + Mesa），仅开发调试使用。
// 用 `./gradlew -PwithDesktop :desktop:run` 启用。
if (providers.gradleProperty("withDesktop").isPresent) {
    include(":desktop")
}
