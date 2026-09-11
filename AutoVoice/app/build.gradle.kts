import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// 讯飞离线唤醒/命令词共享的 AIKit 授权凭据：从 local.properties（gitignored）注入。
// 未配置时为空字符串 → 讯飞生产候选不可用；仅显式配置 fake-cmd 才使用 Demo 结果。
val xfyunProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun xfyunProp(key: String): String = "\"" + (xfyunProps.getProperty(key, "")) + "\""

// 构建环境（dev 分支工作流）：dev 分支编译的 APK 默认连 dev 网关，其余默认连生产。
// CI 通过 AUTOVOICE_APP_ENV 显式指定（push dev → dev / push main → prod）；
// 本地构建未指定时按当前 git 分支推断。
fun defaultDemoMode(): String {
    val fromEnv = System.getenv("AUTOVOICE_APP_ENV")
    if (fromEnv == "dev") return "demo-dev"
    if (fromEnv == "prod") return "demo-full"
    val branch = runCatching {
        ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
            .directory(rootProject.projectDir)
            .start()
            .inputStream
            .bufferedReader()
            .use { it.readText().trim() }
    }.getOrDefault("")
    return if (branch == "dev") "demo-dev" else "demo-full"
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

        // 真机 instrumented 测试（Task 48：Silero VAD 真机验证）
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 讯飞离线命令词凭据（Task 34 接线；空串时本地候选按未命中收口）
        buildConfigField("String", "XFYUN_APPID", xfyunProp("xfyun.appid"))
        buildConfigField("String", "XFYUN_API_KEY", xfyunProp("xfyun.apiKey"))
        buildConfigField("String", "XFYUN_API_SECRET", xfyunProp("xfyun.apiSecret"))

        // 首次启动默认模式：dev 分支构建 → demo-dev；其余 → demo-full（设置区仍可切换）
        buildConfigField("String", "DEFAULT_DEMO_MODE", "\"" + defaultDemoMode() + "\"")
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

    buildTypes {
        getByName("debug") {
            enableUnitTestCoverage = true
        }
    }

    sourceSets.getByName("test").resources.srcDir("../../shared/fixtures")

    testOptions {
        // 单测用 JUnit 5（与 voice-core / adapter-local 保持一致）
        unitTests.all { it.useJUnitPlatform() }
        // android.jar 方法默认抛 "not mocked"；bridge 的 Log.d 弃帧路径在 JVM 单测里必须可用
        unitTests.isReturnDefaultValues = true
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
    testImplementation(libs.coroutines.test)
    // 桥接对账测试：MockWebServer 假扮网关推送 reply/error（Task 20 fix round）
    testImplementation(libs.mockwebserver)
    // TelemetryClient JVM 单测（T6）：真实 org.json（mockable android.jar 的 JSONObject 是桩，
    // put 返回 null）；testImplementation 仅单测生效，真机运行时仍用系统自带 org.json
    testImplementation(libs.orgjson)

    // 真机 instrumented 测试（Task 48：Silero VAD 真机验证，确定性 wav 输入不走麦克风）
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
