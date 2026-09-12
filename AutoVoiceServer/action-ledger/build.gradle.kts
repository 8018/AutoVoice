plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    api(project(":contracts"))
    implementation(libs.sqlite.jdbc)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":contracts")))
}

tasks.test {
    useJUnitPlatform()
}
