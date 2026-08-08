plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.autovoice.adapterlocal"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        externalNativeBuild {
            cmake {
                // 纯 C，无 C++ runtime 依赖；优化开 O3（rnnoise 是 DSP/RNN 数值计算）
                arguments += "-DANDROID_STL=none"
                cFlags += "-O3"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions {
        // 单测用 JUnit 5（与 voice-core 保持一致）
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":voice-core"))
    implementation(libs.coroutines.core)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    // JVM 版 onnxruntime（与 onnxruntime-android 同包名 ai.onnxruntime）：
    // 用于 JVM 单测里对 silero_vad.onnx 做真实推理冒烟验证
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.19.0")
}
