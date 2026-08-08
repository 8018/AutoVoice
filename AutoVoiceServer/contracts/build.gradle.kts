plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    useJUnitPlatform()
}
