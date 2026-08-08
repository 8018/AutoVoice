plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)
}

sourceSets.test { resources.srcDir("../../shared/fixtures") }

tasks.test {
    useJUnitPlatform()
}
