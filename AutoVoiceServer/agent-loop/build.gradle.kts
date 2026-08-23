plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    api(project(":contracts"))
    implementation(libs.jackson.databind)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnitPlatform()
}
