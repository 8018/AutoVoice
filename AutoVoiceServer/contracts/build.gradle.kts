plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    testImplementation(libs.junit)
}

sourceSets.test { resources.srcDir("../../shared/fixtures") }

tasks.test {
    useJUnitPlatform()
}
