plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    api(project(":contracts"))
    implementation(project(":agent-loop"))
    implementation(libs.okhttp)
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson2)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
}
