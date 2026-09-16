plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":voice-core"))
    implementation(libs.gson)
    testImplementation(libs.junit)
}

tasks.test { useJUnitPlatform() }
