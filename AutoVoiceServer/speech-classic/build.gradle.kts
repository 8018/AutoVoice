plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    api(project(":contracts"))
    implementation(project(":asr-gateway"))
    implementation(project(":llm"))
    implementation(libs.slf4j.api)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnitPlatform()
}
