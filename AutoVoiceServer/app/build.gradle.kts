plugins {
    `java`
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

val voiceBackend = providers.gradleProperty("voiceBackend").orElse("classic").get()
require(voiceBackend in setOf("classic", "omni")) {
    "Unsupported -PvoiceBackend=$voiceBackend (classic | omni)"
}

sourceSets.main {
    java.srcDir("src/$voiceBackend/java")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":session"))
    implementation(project(":tts-gateway"))
    implementation(project(":gateway"))
    implementation(project(":offline-command"))
    implementation(project(":navigation-domain"))
    implementation(project(":telemetry"))
    implementation(project(":skill-mcp"))
    implementation(project(":llm"))
    implementation(project(":asr-gateway"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.okhttp)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
    if (voiceBackend == "classic") {
        implementation(project(":speech-classic"))
    } else {
        implementation(project(":speech-qwen-omni"))
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    mainClass.set("com.autovoice.server.app.AutoVoiceServerApplication")
}
