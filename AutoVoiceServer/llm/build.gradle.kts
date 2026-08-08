plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    // LlmProvider/Reply/SessionContext 是公开 API 的一部分（implements LlmProvider、返回 CompletableFuture<Reply>）
    api(project(":contracts"))
    implementation(libs.okhttp)
    implementation(libs.jackson.databind)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}

sourceSets.test { resources.srcDir("../../shared/fixtures") }

tasks.test {
    useJUnitPlatform()
}
