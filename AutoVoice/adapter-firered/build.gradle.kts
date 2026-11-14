plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.autovoice.adapterfirered"
    compileSdk = 34
    defaultConfig { minSdk = 27;consumerProguardFiles("consumer-rules.pro");testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildTypes { getByName("debug") { enableUnitTestCoverage = true } }
    testOptions { unitTests.all { it.useJUnitPlatform() } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies {
    implementation(project(":voice-core"))
    implementation(project(":audio-frontend"))
    implementation(libs.gson)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.lifecycle.common)
    androidTestImplementation(libs.annotation.experimental)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(project(":voice-engine"))
    androidTestImplementation(libs.coroutines.core)
}

// Unverified native binaries are explicit debug inputs, never production source-set contents.
providers.gradleProperty("nativeDevRoot").orNull?.let { value ->
    val devRoot = rootProject.file(value).canonicalFile
    require(!devRoot.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
        "nativeDevRoot must be outside the production project"
    }
    val libs = devRoot.resolve("adapter-firered/src/main/jniLibs")
    require(libs.resolve("arm64-v8a/libsherpa-onnx-jni.so").isFile) { "nativeDevRoot has no staged FireRed library" }
    require(libs.resolve("arm64-v8a/libonnxruntime.so").isFile) { "nativeDevRoot has no staged ORT library" }
    android.sourceSets.getByName("debug").jniLibs.srcDir(libs)
    gradle.taskGraph.whenReady {
        require(allTasks.none { it.name.contains("Release",ignoreCase = true) }) {
            "nativeDevRoot is debug-only; Release must use verified production artifacts"
        }
    }
}
