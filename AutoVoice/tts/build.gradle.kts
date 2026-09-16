plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":voice-core"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test { useJUnitPlatform() }
