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
    implementation(project(":arbitration"))
    implementation(project(":session"))
    implementation(project(":llm"))
    implementation(project(":asr-gateway"))
    implementation(project(":tts-gateway"))
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.spring.boot.starter.json)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}

sourceSets.test { resources.srcDir("../../shared/fixtures") }

tasks.test {
    useJUnitPlatform()
}
