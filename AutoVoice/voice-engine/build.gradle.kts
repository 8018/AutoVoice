plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":voice-engine-api"))
    implementation(project(":voice-core"))
    implementation(project(":gateway-client"))
    implementation(project(":message-dispatch"))
    implementation(libs.coroutines.core)
    implementation(libs.gson)
    testImplementation(libs.junit)
}

tasks.test { useJUnitPlatform() }
