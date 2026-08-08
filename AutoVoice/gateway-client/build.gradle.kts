plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":voice-core"))
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.gson)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
