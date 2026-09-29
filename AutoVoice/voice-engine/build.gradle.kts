plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":voice-engine-api"))
    testImplementation(libs.junit)
}

tasks.test { useJUnitPlatform() }
