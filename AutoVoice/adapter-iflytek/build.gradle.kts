plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.autovoice.adapteriflytek"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        ndk {
            // 与讯飞 demo 对齐：离线命令词 so（arm64-v8a）即可
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
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
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    // 讯飞离线命令词 AIKit AEE SDK（aar 内含 jni so），经 settings 的 flatDir 仓库解析；
    // 本地文件不入库（见 .gitignore）。库模块不能用 files() 直引 aar（AGP 打包限制）
    val vendorAar = file("libs/AIKit.aar")
    val forceStub = providers.gradleProperty("useIflytekStub").orNull == "true"
    if (vendorAar.isFile && !forceStub) {
        implementation(mapOf("name" to "AIKit", "ext" to "aar"))
    } else {
        // GitHub Actions 无法取得厂商私有 AAR。替身只提供编译期 API，任何运行调用都会失败；
        // 正式/设备构建必须放入 libs/AIKit.aar，不能把替身打进 APK。
        compileOnly(project(":iflytek-sdk-stub"))
        testImplementation(project(":iflytek-sdk-stub"))
    }
    testImplementation(libs.junit)
}

// 真机包必须同时包含离线命令词与离线唤醒两个 AEE native 插件。CI 强制 stub，跳过此校验。
val verifyIflytekVendorAar by tasks.registering {
    doLast {
        val vendorAar = file("libs/AIKit.aar")
        val forceStub = providers.gradleProperty("useIflytekStub").orNull == "true"
        if (!vendorAar.isFile || forceStub) return@doLast
        val abilityPlugins = zipTree(vendorAar).matching {
            include("jni/arm64-v8a/lib*_aee.so")
        }.files
        check(abilityPlugins.size >= 2) {
            "libs/AIKit.aar 只包含 ${abilityPlugins.size} 个能力插件；请运行 " +
                "tools/prepare-iflytek-aikit.sh 合并离线命令词与离线唤醒 SDK"
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyIflytekVendorAar) }
