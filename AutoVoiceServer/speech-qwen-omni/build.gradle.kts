plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    api(project(":contracts"))
    implementation(project(":agent-loop"))
    implementation(libs.okhttp)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(project(":navigation-domain"))
}

tasks.test {
    useJUnitPlatform()
}
