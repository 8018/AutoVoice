plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.autovoice.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autovoice.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        // 调试构建开关（BuildConfig.DEBUG）——弱网调试 hook 仅 debug 暴露（Task 20 裁定）
        buildConfig = true
    }

    testOptions {
        // 单测用 JUnit 5（与 voice-core / adapter-local 保持一致）
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
    implementation(project(":gateway-client"))
    implementation(project(":adapter-local"))
    implementation(project(":adapter-iflytek"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.core)
    // 装配 GatewayClient 需要 okhttp 类型；网关 decision 事件解析需要 gson（Task 20）
    implementation(libs.okhttp)
    implementation(libs.gson)

    testImplementation(libs.junit)
}
