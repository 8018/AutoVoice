# offline-command/native — 讯飞离线命令词 JNI 桥（Linux x86-64）

服务端「传统链路」= 讯飞离线命令词识别（原生 SDK），只部署在阿里云
（47.94.4.204）。本目录是 Java 与 SDK 之间的 JNI 桥，与官方 sample
（`samples/cnenesr_sample/cnenesr_sample.cpp`）调用序列逐符号对齐。

| 文件 | 说明 |
|---|---|
| `autovoice_offline_esr.cpp` | JNI 桥源码（C++11，extern "C" 导出 3 个方法） |
| `build.sh` | 服务器编译脚本（产物 `<SDK_DIR>/libautovoice_offline_esr.so`） |
| `README.md` | 本文档 |

## JNI 方法（与 NativeOfflineCommandProvider 声明一致）

| Java（NativeOfflineCommandProvider） | C++ 导出 |
|---|---|
| `nativeInit(appId, apiKey, apiSecret, workDir, resourceDir, fsaPath, licenseFile): long` | `Java_..._nativeInit` — 初始化引擎，非 0 句柄 = 成功 |
| `nativeRecognize(handle, pcm): byte[]` | `Java_..._nativeRecognize` — 识别一段 PCM（S16LE/16kHz/单声道），返回 **GBK 原始字节**，无结果 → NULL |
| `lastError(): String` | `Java_..._lastError` — 最近一次错误描述（init 失败排查） |

## 关键流程（与 SDK sample 对齐）

- **init**：`RegisterAbilityCallback` → `Configurator`（app 凭据 / auth / log）
  → `AIKIT_Init` → `EngineInit(decNetType=fsa, wfst_addType=0)` →
  `LoadData(cn_fsa.txt, index 0)`。
- **recognize**（每次调用完整生命周期）：
  1. **重载 FSA**（`UnLoadData` + `LoadData`）——SDK 在 `AIKIT_End` 后清空引擎
     资源，不重载下一次 `Start` 报 **10017**；
  2. `SpecifyDataSet(FSA, {0})` → `AIKIT_Start`（设备端参数：`vadOn`、
     `vadEndGap=60`、`vadSpeechEnd=80`、`vadEnergyThreshold=9` 等）；
  3. 320B 分帧 `Write`（首帧 `DataBegin`，后续 `DataContinue`），每帧跟 `Read`
     收集输出节点；
  4. 空帧 `DataEnd` → `AIKIT_End`；
  5. 取 `status==2` 节点（优先 key 含 `plain`）的 `value` **拷贝**为 GBK 字节返回。
- **凭据**：licenseFile 非空 → `authType=1`（离线授权，不出网）；为空 →
  `authType=0`（联网激活，复用 `XFYUN_APPID/API_KEY/API_SECRET`，需服务器
  出网 443）。凭据不打印、不入库。
- 引擎非线程安全：Java 侧 `NativeOfflineCommandProvider` 单线程串行调用；
  native 侧对 `lastError` 有互斥保护。

## 编译（服务器）

```bash
# 前置：gcc-c++ + java-21-openjdk-headless
dnf install -y gcc-c++ java-21-openjdk-headless
cd /opt/autovoice/iflytek-offline/native
./build.sh          # 产物 ../libautovoice_offline_esr.so
```

`build.sh` 已内置 `-I"$JAVA_HOME/include"`、`-L<SDK>/libs -laikit -lpthread -ldl`、
`-Wl,-rpath,<SDK>/libs`（运行时无需手动 export LD_LIBRARY_PATH；systemd unit
的 `Environment=LD_LIBRARY_PATH=...` 保留作双保险）。

## 部署与启用

1. SDK 上传、JNI 桥编译：见 `AutoVoiceServer/deploy/README.md`「首次部署步骤」。
2. `/etc/autovoice/.env`：`AUTOVOICE_OFFLINE_ENABLED=true`（license 模式再加
   `AUTOVOICE_OFFLINE_LICENSE_FILE=...`）→ `systemctl restart autovoice-gateway`。
3. 验证日志：
   - `Offline SDK init ok (license: online-activation)`（或 `license: file`）；
   - 每句命令词：`Offline ASR ok: "打开空调"` 或 `Offline no result`；
   - decision 三态：`offline_won` / `llm_reply` / `safety_timeout`。

## 冒烟测试（服务器，可选）

SDK 自带 `bin/resource/testAudio/cn_test.pcm`（16kHz 单声道 PCM，内容为
`cn_fsa.txt` 中的命令词）。把该文件放到 `/opt/autovoice/iflytek-offline/`，
修改 `cnenesr_sample.cpp` 填上 appid/apiKey/apiSecret 后按 SDK readme 编译执行，
应识别出词表内文本（首跑联网激活需出网 443）。

## 排障

| 现象 | 排查 |
|---|---|
| `Offline SDK init failed`（Java 日志 + `lastError`） | 看 `<workDir>/aikit_esr.log` 与 `<workDir>/aikit/aeeLog.txt`；核对 appID/凭据、resource 目录、出网（联网激活） |
| `AIKIT_LoadData failed: 18301` | FSA 装载失败：`cn_fsa.txt` 路径/GBK 编码、文件权限 |
| `AIKIT_Start failed: 10017` | 引擎资源被清空：确认走的是「重载 FSA」流程（本桥已内置） |
| 识别总是空结果 | VAD 参数过严（`vadEnergyThreshold=9`）：先试 `cn_test.pcm` 冒烟确认引擎本身正常，再调阈值 |
| 结果乱码 | GBK 解码错位：确认 Java 侧 `Charset.forName("GBK")` 与 FSA 文件编码一致 |

> 错误码来源：`include/aikit_err.h`；SDK 自身日志：`<workDir>/aikit_esr.log`
> （logPath 由本桥配置）。
