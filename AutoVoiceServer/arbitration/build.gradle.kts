plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    implementation(project(":contracts"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnitPlatform()
}
