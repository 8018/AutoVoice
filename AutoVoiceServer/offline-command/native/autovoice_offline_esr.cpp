/*
 * autovoice_offline_esr.cpp — 讯飞离线命令词识别 JNI 桥（Linux x86-64）
 *
 * 与官方 SDK sample（samples/cnenesr_sample/cnenesr_sample.cpp）逐符号对齐：
 *   init:      RegisterAbilityCallback → Configurator → AIKIT_Init → EngineInit(decNetType=fsa)
 *              → LoadData(cn_fsa.txt, index 0)
 *   recognize: UnLoadData + LoadData 重载（SDK 在 End 后清空引擎资源，不重载下次 Start
 *              报 10017）→ SpecifyDataSet(FSA, {0}) → Start → 320B 分帧 Write/Read → DataEnd
 *              → End → 取 plain 节点（GBK 原始字节；Linux 引擎 status=1，不可用 status==2 门限）
 *
 * Java 侧调用方 NativeOfflineCommandProvider 保证单线程串行（SDK 引擎非线程安全）。
 * 凭据（appId/apiKey/apiSecret）仅用于联网激活（authType=0）；licenseFile 非空走
 * authType=1（离线授权）。两者都不打印、不落盘。
 *
 * 编译：./build.sh（阿里云服务器）；产物 libautovoice_offline_esr.so。
 */

#include <jni.h>
#include <cstring>
#include <cstdarg>
#include <mutex>
#include <string>
#include <vector>

#include "aikit_biz_api.h"
#include "aikit_biz_config.h"
#include "aikit_constant.h"
#include "aikit_biz_builder.h"

using namespace AIKIT;

static const char* ABILITY = "e75f07b62";
static const char* FSA_KEY = "FSA";

// ---- 最近一次错误描述（Java lastError() 读取，init/recognize 失败排查） ----
static std::mutex g_errMutex;
static std::string g_lastError;

static void setLastError(const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    std::lock_guard<std::mutex> lock(g_errMutex);
    g_lastError = buf;
}

static void clearLastError() {
    std::lock_guard<std::mutex> lock(g_errMutex);
    g_lastError.clear();
}

// ---- SDK 回调（必填；结果走 AIKIT_Read 拉取，回调只留日志） ----
static void OnOutput(AIKIT_HANDLE* handle, const AIKIT_OutputData* output) {
    (void)handle;
    (void)output;
}

static void OnEvent(AIKIT_HANDLE* handle, AIKIT_EVENT eventType, const AIKIT_OutputEvent* eventValue) {
    (void)handle;
    (void)eventType;
    (void)eventValue;
}

static void OnError(AIKIT_HANDLE* handle, int32_t err, const char* desc) {
    (void)handle;
    setLastError("SDK callback error %d: %s", err, desc != nullptr ? desc : "");
}

// ---- 引擎状态 ----
static bool g_initialized = false;
static std::string g_fsaPath;                 // 每次 recognize 重载用
static AIKIT_ParamBuilder* g_startParam = nullptr;  // 识别参数（构建一次，复用）

/*
 * 初始化引擎；成功返回非 0 句柄（Java 侧仅判断 0/非 0），失败返回 0。
 * 任一步非 0 即中止并记错误（lastError() 可取描述）。
 */
static jlong initEngine(const char* appId, const char* apiKey, const char* apiSecret,
                        const char* workDir, const char* resourceDir, const char* fsaPath,
                        const char* licenseFile) {
    clearLastError();
    int ret = 0;

    // 1) Configurator：app 信息 + 授权（联网激活 authType=0 / license authType=1）+ 日志。
    //    builder() 每次返回独立引用，license 分支必须写完整链。
    bool useLicense = (licenseFile != nullptr && licenseFile[0] != '\0');
    const std::string logPath = std::string(workDir == nullptr ? "/opt/autovoice/iflytek-offline/work" : workDir)
                                + "/aikit_esr.log";
    if (useLicense) {
        AIKIT_Configurator::builder()
            .app()
                .appID(appId == nullptr ? "" : appId)
                .apiSecret(apiSecret == nullptr ? "" : apiSecret)
                .apiKey(apiKey == nullptr ? "" : apiKey)
                .workDir(workDir == nullptr ? "/opt/autovoice/iflytek-offline/work" : workDir)
                .resDir(resourceDir == nullptr ? "" : resourceDir)
                .cfgFile("")
            .auth()
                .authType(1)
                .ability(ABILITY)
                .licenseFile(licenseFile);
    } else {
        AIKIT_Configurator::builder()
            .app()
                .appID(appId == nullptr ? "" : appId)
                .apiSecret(apiSecret == nullptr ? "" : apiSecret)
                .apiKey(apiKey == nullptr ? "" : apiKey)
                .workDir(workDir == nullptr ? "/opt/autovoice/iflytek-offline/work" : workDir)
                .resDir(resourceDir == nullptr ? "" : resourceDir)
                .cfgFile("")
            .auth()
                .authType(0)
                .ability(ABILITY);
    }
    AIKIT_Configurator::builder().log()
        .logLevel(LOG_LVL_INFO)
        .logMode(LOG_FILE)
        .logPath(logPath.c_str());

    // 2) 回调 + 全局 Init
    AIKIT_Callbacks cbs = {OnOutput, OnEvent, OnError};
    ret = AIKIT_RegisterAbilityCallback(ABILITY, cbs);
    if (ret != 0) {
        setLastError("RegisterAbilityCallback failed: %d", ret);
        return 0;
    }
    ret = AIKIT_Init();
    if (ret != 0) {
        setLastError("AIKIT_Init failed: %d", ret);
        return 0;
    }

    // 3) 引擎初始化：FSA 解码 + 中文
    AIKIT_ParamBuilder* engineParam = AIKIT_ParamBuilder::create();
    engineParam->clear();
    engineParam->param("decNetType", "fsa", strlen("fsa"));
    engineParam->param("punishCoefficient", 0.0);
    engineParam->param("wfst_addType", 0); // 0 中文 1 英文
    ret = AIKIT_EngineInit(ABILITY, engineParam->build());
    delete engineParam;
    if (ret != 0) {
        setLastError("AIKIT_EngineInit failed: %d", ret);
        return 0;
    }

    // 4) 装载 FSA 词表（GBK 编码文件，index 0）
    AIKIT_CustomBuilder* custom = AIKIT_CustomBuilder::create();
    custom->clear();
    custom->textPath(FSA_KEY, fsaPath == nullptr ? "" : fsaPath, 0);
    ret = AIKIT_LoadData(ABILITY, AIKIT_Builder::build(custom));
    delete custom;
    if (ret != 0) {
        setLastError("AIKIT_LoadData failed: %d", ret);
        return 0;
    }
    g_fsaPath = fsaPath == nullptr ? "" : fsaPath;

    // 5) 识别参数（每次 Start 复用；与官方 Linux sample（cnenesr_sample.cpp）逐项对齐。
    //    注意：Android 真机标定的 vadEnergyThreshold/postprocOn/vadLinkOn 等值不适用
    //    Linux 引擎（VAD 判全静音导致 no result），此处用 sample 默认值。）
    g_startParam = AIKIT_ParamBuilder::create();
    g_startParam->clear();
    g_startParam->param("languageType", 0);       // 0 中文 1 英文
    g_startParam->param("vadOn", true);           // 引擎内 VAD 开关
    g_startParam->param("vadEndGap", 75);         // VAD 子句间隔（10ms 单位）
    g_startParam->param("vadSpeechEnd", 80);      // 80 = 800ms 尾音判定
    g_startParam->param("beamThreshold", 20);     // 解码门限
    g_startParam->param("hisGramThreshold", 3000);
    g_startParam->param("postprocOn", false);     // 后处理开关（sample 默认关）
    g_startParam->param("vadResponsetime", 1000);
    g_startParam->param("vadLinkOn", false);      // VAD 链接（sample 默认关）

    g_initialized = true;
    return (jlong)1; // 非 0 即成功
}

/*
 * Write + Read 一轮；识别文本在 key 含 "plain" 的节点（GBK 原始字节）。
 * - 关键：Linux 引擎 plain 节点 status=1 而非 2（DataEnd 后随整链输出，
 *   调试确认 len=8 即 "打开空调" GBK 4 字×2B），此前 status==2 门限把它
 *   跳过、out 落成第一个 status==2 的 vad SpeechAutoFinish 尾标记 JSON
 *   → Java 侧 GBK 解码出 {"sc":"0","ws":[...SpeechAutoFinish...]} 判 unknown。
 * - 策略：plain 节点出现即采用（不限 status）；否则回退首个 status==2 节点
 *   （历史行为，纯静音等无词场景 out 保持空 → Java 侧 Optional.empty）。
 */
static int collectResult(AIKIT_HANDLE* srHandle, AIKIT_DataBuilder* dataBuilder, std::vector<char>* out) {
    AIKIT_InputData* input = AIKIT_Builder::build(dataBuilder);
    int ret = AIKIT_Write(srHandle, input);
    if (ret != 0) {
        setLastError("AIKIT_Write failed: %d", ret);
        return ret;
    }
    AIKIT_OutputData* output = nullptr;
    ret = AIKIT_Read(srHandle, &output);
    if (ret != 0) {
        setLastError("AIKIT_Read failed: %d", ret);
        return ret;
    }
    if (output == nullptr || output->node == nullptr) {
        return 0;
    }
    AIKIT_BaseData* node = output->node;
    while (node != nullptr) {
        if (node->value != nullptr && node->len > 0) {
            const char* key = node->key == nullptr ? "" : node->key;
            if (strstr(key, "plain") != nullptr) {
                out->assign(static_cast<char*>(node->value),
                            static_cast<char*>(node->value) + node->len);
                return 0;
            }
            if (node->status == 2 && out->empty()) {
                out->assign(static_cast<char*>(node->value),
                            static_cast<char*>(node->value) + node->len);
            }
        }
        node = node->next;
    }
    return 0;
}

/*
 * 识别一段 PCM（S16LE / 16kHz / 单声道）；成功返回 GBK 字节 vector，失败或无结果返回 false。
 */
static bool recognizeImpl(jlong handle, const char* pcm, jsize len, std::vector<char>* out) {
    (void)handle;
    clearLastError();
    if (!g_initialized) {
        setLastError("engine not initialized");
        return false;
    }
    int ret = 0;

    // 1) 重载 FSA：SDK 在 End 后清空引擎资源，不重载下次 Start 报 10017
    AIKIT_UnLoadData(ABILITY, FSA_KEY, 0); // 首次调用无数据可卸，忽略返回值
    AIKIT_CustomBuilder* custom = AIKIT_CustomBuilder::create();
    custom->clear();
    custom->textPath(FSA_KEY, g_fsaPath.c_str(), 0);
    ret = AIKIT_LoadData(ABILITY, AIKIT_Builder::build(custom));
    delete custom;
    if (ret != 0) {
        setLastError("AIKIT_LoadData (reload) failed: %d", ret);
        return false;
    }

    // 2) 指定数据集合 + Start
    int idx[1] = {0};
    ret = AIKIT_SpecifyDataSet(ABILITY, FSA_KEY, idx, 1);
    if (ret != 0) {
        setLastError("AIKIT_SpecifyDataSet failed: %d", ret);
        return false;
    }
    AIKIT_HANDLE* srHandle = nullptr;
    ret = AIKIT_Start(ABILITY, AIKIT_Builder::build(g_startParam), nullptr, &srHandle);
    if (ret != 0) {
        setLastError("AIKIT_Start failed: %d", ret);
        return false;
    }

    // 3) 320B 分帧写入（首帧 DataBegin，后续 DataContinue），每帧跟 Read 收结果
    AIKIT_DataBuilder* dataBuilder = AIKIT_DataBuilder::create();
    AIKIT_DataStatus status = AIKIT_DataBegin;
    jsize off = 0;
    bool ok = true;
    while (off < len) {
        jsize chunk = (len - off < 320) ? (len - off) : 320;
        dataBuilder->clear();
        AiAudio* audio = AiAudio::get("audio")
                             ->data(const_cast<char*>(pcm + off), chunk)
                             ->status(status)
                             ->valid();
        dataBuilder->payload(audio);
        status = AIKIT_DataContinue;
        if (collectResult(srHandle, dataBuilder, out) != 0) {
            ok = false;
            break;
        }
        off += chunk;
    }

    // 4) DataEnd 空帧（必须：触发尾段解码）
    if (ok) {
        dataBuilder->clear();
        AiAudio* audio = AiAudio::get("audio")
                             ->data(nullptr, 0)
                             ->status(AIKIT_DataEnd)
                             ->valid();
        dataBuilder->payload(audio);
        if (collectResult(srHandle, dataBuilder, out) != 0) {
            ok = false;
        }
    }

    ret = AIKIT_End(srHandle); // 无论成败都要 End（清理引擎资源）
    delete dataBuilder;
    if (ret != 0) {
        setLastError("AIKIT_End failed: %d", ret);
        ok = false;
    }
    if (!ok || out->empty()) {
        return false; // 无结果：Java 侧记 Offline no result
    }
    return true;
}

// ---- JNI 导出（方法名与 NativeOfflineCommandProvider 的三个 native 声明一致） ----

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_autovoice_server_offlinecommand_NativeOfflineCommandProvider_nativeInit(
    JNIEnv* env, jclass, jstring appId, jstring apiKey, jstring apiSecret,
    jstring workDir, jstring resourceDir, jstring fsaPath, jstring licenseFile) {
    const char* cAppId = appId ? env->GetStringUTFChars(appId, nullptr) : nullptr;
    const char* cApiKey = apiKey ? env->GetStringUTFChars(apiKey, nullptr) : nullptr;
    const char* cApiSecret = apiSecret ? env->GetStringUTFChars(apiSecret, nullptr) : nullptr;
    const char* cWorkDir = workDir ? env->GetStringUTFChars(workDir, nullptr) : nullptr;
    const char* cResDir = resourceDir ? env->GetStringUTFChars(resourceDir, nullptr) : nullptr;
    const char* cFsaPath = fsaPath ? env->GetStringUTFChars(fsaPath, nullptr) : nullptr;
    const char* cLicense = licenseFile ? env->GetStringUTFChars(licenseFile, nullptr) : nullptr;

    jlong result = initEngine(cAppId, cApiKey, cApiSecret, cWorkDir, cResDir, cFsaPath, cLicense);

    if (cAppId) env->ReleaseStringUTFChars(appId, cAppId);
    if (cApiKey) env->ReleaseStringUTFChars(apiKey, cApiKey);
    if (cApiSecret) env->ReleaseStringUTFChars(apiSecret, cApiSecret);
    if (cWorkDir) env->ReleaseStringUTFChars(workDir, cWorkDir);
    if (cResDir) env->ReleaseStringUTFChars(resourceDir, cResDir);
    if (cFsaPath) env->ReleaseStringUTFChars(fsaPath, cFsaPath);
    if (cLicense) env->ReleaseStringUTFChars(licenseFile, cLicense);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_autovoice_server_offlinecommand_NativeOfflineCommandProvider_nativeRecognize(
    JNIEnv* env, jclass, jlong handle, jbyteArray pcm) {
    jsize len = env->GetArrayLength(pcm);
    jbyte* data = env->GetByteArrayElements(pcm, nullptr);

    std::vector<char> result;
    bool ok = (data != nullptr) && recognizeImpl(handle, reinterpret_cast<const char*>(data), len, &result);

    if (data != nullptr) {
        env->ReleaseByteArrayElements(pcm, data, JNI_ABORT);
    }
    if (!ok || result.empty()) {
        return nullptr; // 无结果 → NULL（Java 侧 Optional.empty）
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(result.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(result.size()),
                            reinterpret_cast<const jbyte*>(result.data()));
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_autovoice_server_offlinecommand_NativeOfflineCommandProvider_lastError(
    JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_errMutex);
    return env->NewStringUTF(g_lastError.c_str());
}

} // extern "C"
