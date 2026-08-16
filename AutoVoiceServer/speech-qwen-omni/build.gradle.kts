plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    api(project(":contracts"))
    implementation(libs.okhttp)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
}
