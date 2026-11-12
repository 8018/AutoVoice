plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.autovoice.adapterwebrtc"
    compileSdk = 34
    defaultConfig { minSdk = 27;consumerProguardFiles("consumer-rules.pro") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildTypes { getByName("debug") { enableUnitTestCoverage = true } }
    testOptions { unitTests.all { it.useJUnitPlatform() } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies {
    implementation(project(":audio-frontend"))
    implementation(project(":voice-core"))
    testImplementation(libs.junit)
}

// Unverified native binaries are explicit debug inputs, never production source-set contents.
providers.gradleProperty("nativeDevRoot").orNull?.let { value ->
    val devRoot = rootProject.file(value).canonicalFile
    require(!devRoot.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
        "nativeDevRoot must be outside the production project"
    }
    val libs = devRoot.resolve("adapter-webrtc/src/main/jniLibs")
    require(libs.resolve("arm64-v8a/libautovoice_frontend_jni.so").isFile) { "nativeDevRoot has no staged WebRTC library" }
    android.sourceSets.getByName("debug").jniLibs.srcDir(libs)
    gradle.taskGraph.whenReady {
        require(allTasks.none { it.name.contains("Release",ignoreCase = true) }) {
            "nativeDevRoot is debug-only; Release must use verified production artifacts"
        }
    }
}
