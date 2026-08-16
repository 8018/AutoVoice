pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }
rootProject.name = "AutoVoiceServer"
include(":contracts", ":arbitration", ":session", ":llm", ":asr-gateway", ":tts-gateway", ":offline-command", ":gateway", ":app", ":tts-server", ":telemetry")
include(":skill-mcp")
include(":skill-manager")
include(":speech-classic")
include(":speech-qwen-omni")
