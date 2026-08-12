plugins {
    `java`
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":tts-gateway"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.okhttp)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    mainClass.set("com.autovoice.server.ttsserver.TtsServerApplication")
}
