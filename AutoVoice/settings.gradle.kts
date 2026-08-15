// settings.gradle.kts
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // 讯飞离线命令词 AIKit.aar（本地厂商 SDK，不入库，见 .gitignore）
        flatDir { dirs("adapter-iflytek/libs") }
    }
}
rootProject.name = "AutoVoice"
include(":voice-core", ":gateway-client", ":adapter-local", ":adapter-iflytek", ":iflytek-sdk-stub", ":app")
