plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    implementation(project(":contracts"))
    implementation(libs.slf4j.api)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnitPlatform()
}
