plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    api(project(":contracts"))
    implementation(libs.jackson.databind)
    testImplementation(libs.junit)
}

sourceSets.test { resources.srcDir("../../shared/fixtures") }

tasks.test {
    useJUnitPlatform()
}
