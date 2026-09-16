plugins {
    `java-library`
    // D01b:跨模块测试夹具(TestClock/FutureGate/FakeUpstream/SlowDrain)
    `java-test-fixtures`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    api(libs.jackson.databind)
    testImplementation(libs.junit)
    testImplementation(libs.jackson.datatype.jsr310)
}

sourceSets.test { resources.srcDir("../../shared/fixtures") }

tasks.test {
    useJUnitPlatform()
}
