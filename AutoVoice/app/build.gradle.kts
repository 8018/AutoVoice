import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// 讯飞离线唤醒/命令词共享的 AIKit 授权凭据：从 local.properties（gitignored）注入。
// 未配置时为空字符串 → 讯飞生产候选不可用；仅显式配置 fake-cmd 才使用 Demo 结果。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun xfyunProp(key: String): String = "\"" + (localProps.getProperty(key, "")) + "\""
fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
fun gatewayAuthToken(): String = System.getenv("AUTOVOICE_GATEWAY_AUTH_TOKEN")
    ?: localProps.getProperty("gateway.authToken", "")

// 本地 debug 默认属于开发环境；CI 可为 main 明确生成连接生产的验收 APK。
// release 无条件绑定生产，不能被环境变量切到 dev。
fun debugOnlineConfigAsset(): String =
    if (System.getenv("AUTOVOICE_APP_ENV") == "prod") "demo-full.json" else "demo-dev.json"

android {
    namespace = "com.autovoice.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autovoice.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }

        // 真机 instrumented 测试（Task 48：Silero VAD 真机验证）
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 讯飞离线命令词凭据（Task 34 接线；空串时本地候选按未命中收口）
        buildConfigField("String", "XFYUN_APPID", xfyunProp("xfyun.appid"))
        buildConfigField("String", "XFYUN_API_KEY", xfyunProp("xfyun.apiKey"))
        buildConfigField("String", "XFYUN_API_SECRET", xfyunProp("xfyun.apiSecret"))
        // 接入令牌不进入配置资产；本地从 local.properties 注入，CI/发行从环境变量注入。
        buildConfigField("String", "GATEWAY_AUTH_TOKEN", quoted(gatewayAuthToken()))

    }

    flavorDimensions += "speechChannel"
    productFlavors {
        create("iflytek") { dimension = "speechChannel" }
        create("open") {
            dimension = "speechChannel"
            minSdk = 27
            applicationIdSuffix = ".open"
        }
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
            // 环境由构建类型绑定，手机端不提供服务器切换入口。
            buildConfigField("String", "ONLINE_CONFIG_ASSET", "\"${debugOnlineConfigAsset()}\"")
            // D03c:debug 保留明文 ws:// 例外(局域网/dev 栈联调)
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        getByName("release") {
            isMinifyEnabled = false
            buildConfigField("String", "ONLINE_CONFIG_ASSET", "\"demo-full.json\"")
            // D03c:发布构建禁用非必要明文,生产走 wss://
            manifestPlaceholders["usesCleartextTraffic"] = "false"
        }
    }

    sourceSets.getByName("test").resources.srcDir("../../shared/fixtures")

    testOptions {
        // 单测用 JUnit 5（与 voice-core / audio-frontend 保持一致）
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

val openReleaseVendorAar = rootProject.file("adapter-iflytek/libs/AIKit.aar")
val openReleaseForceStub = providers.gradleProperty("useIflytekStub").map { it == "true" }.orElse(false)
val verifyOpenReleaseInputs by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks trusted staged native inputs before Open Release APK or bundle packaging."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/native/native_tools.py", "verify-release-staging")
    doLast {
        check(!openReleaseForceStub.get() && openReleaseVendorAar.isFile) {
            "Open Release requires the real adapter-iflytek/libs/AIKit.aar and cannot use useIflytekStub=true"
        }
    }
}

tasks.matching { it.name == "packageOpenRelease" || it.name == "packageOpenReleaseBundle" }.configureEach {
    dependsOn(verifyOpenReleaseInputs)
}

dependencies {
    implementation(project(":voice-core"))
    implementation(project(":voice-engine"))
    implementation(project(":gateway-client"))
    implementation(project(":message-dispatch"))
    implementation(project(":business-core"))
    implementation(project(":voice-business"))
    implementation(project(":tts"))
    implementation(project(":audio-frontend"))
    "iflytekImplementation"(project(":audio-frontend-legacy"))
    "openImplementation"(project(":adapter-webrtc"))
    "openImplementation"(project(":adapter-firered"))
    implementation(project(":adapter-iflytek"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.core)
    // 装配 GatewayClient 需要 okhttp 类型；网关 decision 事件解析需要 gson（Task 20）
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.gson)
    implementation("io.noties.markwon:core:4.6.2")

    // compileOnly SDK interfaces do not propagate to the App JVM test runtime.
    if (providers.gradleProperty("useIflytekStub").orNull == "true" ||
        !rootProject.file("adapter-iflytek/libs/AIKit.aar").isFile) {
        testImplementation(project(":iflytek-sdk-stub"))
    }
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // 桥接对账测试：MockWebServer 假扮网关推送 reply/error（Task 20 fix round）
    testImplementation(libs.mockwebserver)
    // TelemetryClient JVM 单测使用 JSONObject 检查请求体；mockable android.jar 的 org.json 是桩。
    testImplementation(libs.orgjson)

    // 真机 instrumented 测试（Task 48：Silero VAD 真机验证，确定性 wav 输入不走麦克风）
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
