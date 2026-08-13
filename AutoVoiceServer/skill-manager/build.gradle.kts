plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.squareup.okhttp3:okhttp")
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson2)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.mockwebserver)
}

tasks.test { useJUnitPlatform() }

tasks.bootJar { mainClass.set("com.autovoice.server.skillmanager.SkillManagerApplication") }
