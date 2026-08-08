plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    // TtsProvider/Reply 是公开 API 的一部分（implements TtsProvider、返回 Reply）
    api(project(":contracts"))
    implementation(libs.okhttp)
    implementation(libs.jackson.databind)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
}
