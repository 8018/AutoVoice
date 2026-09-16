plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    implementation(project(":contracts"))
    testImplementation(libs.junit)
    // D01b 测试夹具(TestClock 等)
    testImplementation(testFixtures(project(":contracts")))
}

tasks.test {
    useJUnitPlatform()
}
