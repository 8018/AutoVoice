pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }
rootProject.name = "AutoVoiceServer"
include(":contracts", ":arbitration", ":session", ":nlu-traditional", ":llm", ":asr-gateway", ":tts-gateway", ":gateway", ":app")
