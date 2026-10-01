plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":voice-core"))
    implementation(project(":voice-engine-api"))
    implementation(project(":business-core"))
    implementation(project(":tts"))
    implementation(libs.coroutines.core)
    implementation(libs.gson)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test { useJUnitPlatform() }
