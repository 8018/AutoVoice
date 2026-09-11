plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    api(project(":contracts"))
    testImplementation(libs.junit)
    testImplementation(project(":navigation-domain"))
}

tasks.test {
    useJUnitPlatform()
}
