plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    implementation(project(":contracts"))
    implementation(libs.okhttp)
    implementation(libs.jackson.databind)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}

sourceSets.test { resources.srcDir("../../shared/fixtures") }

tasks.test {
    useJUnitPlatform()
}
