plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":voice-core"))
    implementation(project(":business-core"))
    implementation(project(":tts"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test { useJUnitPlatform() }
