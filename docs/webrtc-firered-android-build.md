# WebRTC 与 FireRed Android 编译和集成说明

日期：2026-11-08。目标：8295、Android `arm64-v8a`，首版 CPU 推理。配套 [渠道架构设计](superpowers/specs/2026-11-08-open-speech-channel-design.md)。

2026-11-12 状态。双渠道、Open 请求准入和 PCM 参考已接通。WebRTC GN/check/Ninja 及 sherpa API27 编译在 Linux x86_64 WSL 完成。arm64 模拟器覆盖 API33 的本地 App 合成音频识别与 API30/API33 的 PCM 播放。物理 8295、授权唤醒、真实麦克风、双讲、全车混音参考和生产发行信任仍待验收。

| WSL 开发归档 | 已观察 SHA256 |
| --- | --- |
| WebRTC `8838a7c28655afbf9336063b71a51429e7d72774` | `10b7cffc1b4c65fdb42b767fff2bdb588c7dec843f74e95c966ea37517cca55b` |
| sherpa `v2.0.1`，ORT `1.28.2` | `006c2a53fc4bb078c59e98f1a743e5930b09dfb429e7d9181275566582366c37` |

这些是开发暂存回执记录的摘要。发行需要受信锁定摘要和 `stage-native-artifacts.sh` 的 release 校验。当前 lock 仍未验证。`nativeDevRoot` 仅用于 Debug。直接 `assembleOpenRelease` 可能在库缺失时产出不完整 APK，发行操作应先通过严格暂存并运行 `open-real` APK 布局检查。布局检查只验证成员及 ELF 架构，发行签名、来源信任和实机验收各有独立证据。

## Open App Debug 模型安装与验证

在仓库根目录从已验证资源准备固定版本目录。构建使用真实 AIKit AAR 和已暂存的开发 native 库。

```sh
python3 AutoVoice/tools/native/prepare_firered_resources.py \
  --model-source /path/to/model-cache --attribution-source /path/to/attribution \
  --output-root /tmp/autovoice-model
cd AutoVoice
./gradlew :app:assembleOpenDebug :app:assembleOpenDebugAndroidTest \
  -PnativeDevRoot=/path/to/verified-development-staging --offline
APP=app/build/outputs/apk/open/debug/app-open-debug.apk
TEST=app/build/outputs/apk/androidTest/open/debug/app-open-debug-androidTest.apk
python3 tools/native/verify_apk.py open-real "$APP"
SERIAL=emulator-5554 # 换成指定的开发设备序列号
adb -s "$SERIAL" install -r "$APP"
adb -s "$SERIAL" install -r "$TEST"
adb -s "$SERIAL" shell am instrument -w -r -e class \
  'com.autovoice.app.audio.OpenModelProvisioningTest#prepareCatalogPaths' \
  com.autovoice.app.open.test/androidx.test.runner.AndroidJUnitRunner
```

测试方法以目标 App UID 创建固定目录和六个空占位文件，已有文件保留。确认输出 `catalogFiles=6` 和 `modelCatalog`，用该输出确定目标路径。在 Android 13 arm64 模拟器上，未提权的 adb shell（UID 2000）逐个写入 App 预建的文件并校验成功。以下路径以当次探针为例。

```sh
MODEL=sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25
REV=423be1acfdc5d8aaa5a485fb6263fe4ea8570b06
SOURCE="/tmp/autovoice-model/$MODEL/$REV"
DEST="/storage/emulated/0/Android/data/com.autovoice.app.open/files/speech-models/$MODEL/$REV"
adb -s "$SERIAL" shell am force-stop com.autovoice.app.open
for file in manifest.json model.int8.onnx tokens.txt licenses/FireRedASR2S-LICENSE \
    licenses/original-model-card.md licenses/source-attribution.json; do
  adb -s "$SERIAL" push "$SOURCE/$file" "$DEST/$file" || exit 1
  local_hash=$(shasum -a 256 "$SOURCE/$file" | cut -d ' ' -f 1)
  device_hash=$(adb -s "$SERIAL" shell sha256sum "$DEST/$file" | cut -d ' ' -f 1)
  test "$local_hash" = "$device_hash" || exit 1
done
adb -s "$SERIAL" shell am start -n com.autovoice.app.open/com.autovoice.app.MainActivity
```

设备路径与 shell 权限因 OEM 和 Android 用户而异。失败时保留输出并使用该设备批准的 App UID 资源导入方式。生产安装由有资源权限的交付流程预置同一版本目录，确保所有者为 App、目录可遍历且仅授予必要读取权限。测试 APK 只用于调试，模型、tokens、manifest 和许可证独立安装。

启动后在组件状态页检查 frontend、local.asr 和 local.nlu，保留结构化的缺失、摘要错误、native 加载或模型初始化状态。离线模式 cloud 应为 `DISABLED`。真实 App 探针基于 Debug 派生包，仅修改离线 `testAudio` 配置并加入公开合成 PCM。基线及当前 `MainActivity` 没有手动录音按钮，探针在主线程调用 `MainViewModel.startRecording()` / `stopRecording()`。API33 中 WebRTC、ASR 和 NLU 就绪，识别「打开车窗」，端侧结果打开模拟车窗。首版离线规则为车窗开关和退出，NLU 端口可替换。当前权重文件 775,861,420 字节是磁盘大小。车机冷启动、峰值 PSS/RSS 和热稳态仍需在 8295 测量。

播放探针可在 API30/API33 上运行同一个真实 Debug 测试类：

```sh
adb -s "$SERIAL" shell am instrument -w -r -e class \
  com.autovoice.app.audio.OpenPlaybackPlatformTest \
  com.autovoice.app.open.test/androidx.test.runner.AndroidJUnitRunner
```

两个版本均通过 WAV、AAC、48 kHz 双声道 WAV 与 100 ms 短流，验证真实输出与参考坐标、播放完成及流语义。Open 保持设备打断能力不可用，直到真实声学和全车参考验收。

## 1 编译内容和产物

| 内容 | 编译方式 | 交付产物 |
| --- | --- | --- |
| WebRTC APM 与经典 VAD | Linux 上用 depot_tools + GN/Ninja，将 APM/VAD 和本项目 JNI 在同一 GN 图内链接 | `libautovoice_frontend_jni.so`、构建清单和许可证 |
| FireRed Android 推理运行时 | 编译 sherpa-onnx 的 Android JNI，首版动态链接其指定的 ONNX Runtime | `libsherpa-onnx-jni.so`、`libonnxruntime.so` 和同版本 Kotlin API |
| FireRedASR2 CTC 模型 | 下载已经导出并量化的 ONNX 模型；首版不训练、不重新导出 | `model.int8.onnx`、`tokens.txt`、资源校验清单及许可证 |
| App 集成 | Android Gradle product flavors | Iflytek/Open 两种渠道的 APK/AAB |

FireRed 权重不是需要用 NDK 编译的源码。原 FireRed 仓库提供 Python 等推理路径；本项目 Android 端选用 sherpa-onnx 对 FireRedASR2 CTC 的转换和适配。[FireRed 转换模型](https://k2-fsa.github.io/sherpa/onnx/FireRedAsr/pretrained.html)

## 2 构建机和版本规约

WebRTC Android 编译统一放在 Linux x86_64 构建机；使用其 DEPS 管理的工具链，不强行套用本机 Android Studio NDK。sherpa 官方脚本可在 Linux/macOS 运行，本文统一给出 Linux 命令。Mac 日常开发直接获取校验后的 native 制品，再编译 App。[WebRTC Android 构建](https://webrtc.github.io/webrtc-org/native-code/android/)、[sherpa Android 构建](https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html)

| 项目 | 本方案起始配置 | 约束 |
| --- | --- | --- |
| App | 现有 Java 21、AGP 8.5.2、Kotlin 2.0.20、compileSdk 34；讯飞/公共/legacy minSdk 26，Open/其适配器 minSdk 27 | 本次不顺带升级 Gradle/AGP |
| WebRTC | 起始候选提交 `8838a7c28655afbf9336063b71a51429e7d72774` | 已按锁定参数真实编译并完成 API33 独立 JNI 探针；8295、声学质量及发行认证仍未完成 |
| sherpa-onnx | `v2.0.1`，提交 `af2d8d81dbfa61c4cea4c568da78f3982edca005` | Kotlin API 和 native 必须同一提交；禁止跟随 master 自动升级 |
| sherpa NDK | 工程建议从 r27c `27.2.12479018` 开始 | 控制端已用此版本完成 sherpa API27 编译，已通过独立上游 Android 推理探针，8295/发行认证仍未完成 |
| sherpa ONNX Runtime | 上述版本脚本默认指定 `1.28.2` | 脚本默认下载该版本 Android 预编译库；首版没有从源码重编 ORT，不套用项目旧 Silero 的 1.19.0 |
| ABI 与 API | `arm64-v8a`；Open Android API 27（讯飞保持26） | ELF 实际依赖的 API 不得高于 App minSdk |
| 模型 | `sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25` | 模型、tokens 和运行时组合通过实机后一起锁定 |

上游依据：[WebRTC 候选提交](https://webrtc.googlesource.com/src/+/8838a7c28655afbf9336063b71a51429e7d72774)、[sherpa v2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1)、[同版本 Android 脚本](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/build-android-arm64-v8a.sh)。

所有命令中的 `/work` 和 `/opt/android-sdk` 为构建机示例目录，应替换成自己的目录。命令不是要求在当前用户电脑安装或修改系统工具链。

## 2.1 仓库工具、候选锁与发行边界

工具位于 `AutoVoice/tools/native/`，仅依赖 Bash 与 Python 3.10+ 标准库。三个入口与
`native-lock.json` 一起维护，不安装系统依赖或远程配置构建机：

```sh
# 从仓库根执行；默认只打印JSON计划，不创建checkout/output、不下载、不写jniLibs。
AutoVoice/tools/native/build-webrtc-android.sh
AutoVoice/tools/native/build-firered-android.sh
python3 -m unittest discover -s AutoVoice/tools/native/tests -v
bash -n AutoVoice/tools/native/build-webrtc-android.sh
bash -n AutoVoice/tools/native/build-firered-android.sh
bash -n AutoVoice/tools/native/stage-native-artifacts.sh
```

锁固定上述两个源码提交、arm64/Open API 27、GN 参数、sherpa 开关和 NDK 版本。
`depot_tools` 固定为实际观测到的候选提交 `289a37bda3d848cfe16e6c1c599741ed3890ab55`，
WebRTC 组合已真实构建并通过独立 Android JNI/API 烟测；设备声学效果及整个产品组合尚未认证。lock 中可信发行制品及独立验收摘要保持 `null`；
不存在虚构的“已认证 hash”。构建成功也只能输出 **unverified** 包，不能自动把 lock 改成 verified。

完成依赖准备后，Linux 构建机可显式执行以下入口。`--fetch` 才允许新建固定提交的源码 checkout；
已有固定且干净的独立 checkout 可省略该参数。`--licenses-dir` 是实际准备的依赖许可证清单，
不需要为实验编译增加人工审批；法律清单完整性及可信来源属于最终制品评审。

```sh
AutoVoice/tools/native/build-webrtc-android.sh --execute --fetch \
  --source-dir /work/autovoice-native/webrtc/src \
  --depot-tools /work/autovoice-native/depot_tools \
  --licenses-dir /work/autovoice-native/licenses/webrtc \
  --output /work/autovoice-native/artifacts/webrtc-run-001

AutoVoice/tools/native/build-firered-android.sh --execute --fetch \
  --source-dir /work/autovoice-native/sherpa-onnx \
  --ndk /opt/android-sdk/ndk/27.2.12479018 \
  --licenses-dir /work/autovoice-native/licenses/firered \
  --output /work/autovoice-native/artifacts/firered-run-001
```

WebRTC 入口消费 Task4 已提供的 `adapter-webrtc/native/BUILD.gn`、JNI/session 源码及
`webrtc-root.patch`；可显式传 `--project-native-dir` / `--gn-patch` 指向受控输入。
脚本先检查固定 depot_tools revision、源码/工具/许可证，再运行 checkout/hook 和 GN。
它禁用 depot_tools 自动更新，使用 DEPS 管理的 Android 工具链，不套用本机 NDK。
GN 使用 `--check --fail-on-unused-args`、`gn check` 和指定单一目标；运行 `.so` 路径来自 `gn desc outputs`。
sherpa 使用固定提交的原 Android 脚本，关闭 QNN/RKNN/TTS/diarization 等功能，使用动态 ORT。
上游脚本当前固定 `make -j4`；这是已记录的 recipe，不会因宿主环境变量偷偷改变。
外部 SHERPA/CMake/编译环境覆盖会被清理，再设置锁定开关，防止误用本地其他 ORT/QNN 配置。

输出目录保存 `build-plan.json`、`build.log`、`commands.json`、`result.json`；成功时另有
未认证 ZIP、`artifact-manifest.json` 和实测 `observed-checksums.json`。
manifest 记录源码提交、实际 compiler/host/工具版本、DEPS 与 depot_tools、项目源码/补丁摘要，
以及 sherpa 的实际 NDK、ORT 与同提交完整 Kotlin API 清单。ZIP 条目顺序/时间固定；
上游 native 编译及跨机器路径差异尚未验证，不宣称位级可复现。

**重试与调试：** wrapper 拒绝复用已有固定 build/out 目录和已存在的 output，避免把污染缓存冒充新构建。
失败时保留 build/out 和日志，并仅尝试恢复该次调用自己写入的根 BUILD.gn/复制源码；若这些文件已被改动则保留供人工检查。
安全的重新构建方式是使用新的独立 checkout 与新的 `--output`，不删除旧日志，不执行粗暴的
`git reset` 或清理其他人的目录。需要在旧 checkout 手工 Ninja 调试时，先按该次 plan 重新放入相同的
受控 native 源码/根 GN 补丁（用 `git apply --check` 并核对记录的摘要），再使用已记录的 GN/Ninja 参数；
此类实验输出仍为 unverified，不能直接进入发行路径。

### 制品格式与 staging

native 包采用 ZIP，不接收任意 tar/解压目录。格式为：

```text
manifest.json
jni/arm64-v8a/libautovoice_frontend_jni.so             # webrtc 包
# 或 firered 包的 libsherpa-onnx-jni.so + libonnxruntime.so
licenses/<license files>                             # 必需，逐文件摘要
api/kotlin/<complete matching upstream API>           # firered 必需，逐文件摘要
symbols/arm64-v8a/<allowlisted-library>.debug          # 可选，仅归档/校验，不安装
provenance/build-plan.json / commands.json             # 构建溯源
provenance/gclient-revinfo.txt                         # WebRTC DEPS实际revision清单
```

manifest 的 `component`、`source_revision`、`abi`、`android_api`、`recipe`、`toolchain`、
`files` 与 `auxiliary` 都会验证。`files` 为固定库路径到 SHA-256 的映射，`auxiliary` 记录许可证、API、
已列出的溯源文件及可选符号的摘要。仅这三种运行库可以写入各自 adapter，绝不写公共 App：
WebRTC 的 `libautovoice_frontend_jni.so`，FireRed 的 `libsherpa-onnx-jni.so` 和 `libonnxruntime.so`。
符号白名单只允许相应库的 `.debug` 路径，不复制到 jniLibs。完整 Kotlin API 保留在同版本包内，Task5 已接入 23 个逐文件摘要匹配且未修改的上游 API。

开发验证必须显式指定模式、从可信传递渠道获得的预期 ZIP 摘要和生产工程外的目录：

```sh
AutoVoice/tools/native/stage-native-artifacts.sh --mode dev \
  --archive /work/artifacts/webrtc-android-arm64-v8a-unverified.zip \
  --expected-sha256 '<observed-and-checked-archive-sha256>' \
  --dev-output /tmp/autovoice-native-dev
```

该路径会标记 **UNVERIFIED DEVELOPMENT**，不能写生产 jniLibs，也不会改 lock 状态。
只计算下载文件自身 hash 并不等于验证来源；dev 明确不提供发行认证。

认证发行仅使用受信任版本控制中的 lock，要求 `artifact.verification=verified`，且 ZIP、manifest、
逐库、实际 toolchain 与独立验收报告摘要均已由真实结果记录和评审。未填摘要时默认拒绝：

```sh
AutoVoice/tools/native/stage-native-artifacts.sh --archive /work/verified/webrtc.zip
AutoVoice/tools/native/stage-native-artifacts.sh --archive /work/verified/firered.zip
```

staging 不调用 extractall：它逐项检查 ZIP 路径、重复条目、symlink/特殊文件、解压总量限制、
固定库/辅助文件白名单与逐项摘要，再只读取已核对库。ELF 校验要求 little-endian ELF64 AArch64 shared object，
拒绝非空 DT_RPATH，以及除精确 `$ORIGIN` 外的 DT_RUNPATH；拒绝未声明 DT_NEEDED 和重复 ORT；若有 Android API note 则要求不高于 Open minSdk 27。
允许的平台依赖是 `libc/libm/libdl/liblog/libandroid/libz`，其余依赖必须由当前固定运行库集合闭包满足。
若真实输出依赖 `libc++_shared.so` 或其他额外库，工具明确失败；必须先更新并评审依赖白名单/锁/测试及许可证，不能漏库或 pickFirst。
它还检查 Open 两 adapter、公共 frontend 和 App source set 中的重复 ORT，不扫描/改写 legacy 渠道的独立 ORT 依赖。

所有验证通过后，以 adapter 的 ABI 目录为单位替换；写入失败会恢复原目录。来源收据在 `.native/receipts`，不混入运行库目录。
API/NDK 元数据、ELF header/note 和摘要检查不证明导入符号的实际最低 API、16KB 页面或设备行为；这些仍需真实 readelf/link/装载与设备验收，
不能仅靠工具测试把 candidate 标为 verified。制品目录、receipts、jniLibs 与 Python 缓存均已 gitignore。

**API floor 用户裁定（2026-11-08）：** 实查固定 ORT 1.28.2 的 Android ELF note 为27，用户明确批准仅 Open 提升到 Android 8.1/API27；讯飞、公共模块与 legacy 仍为26。锁、GN、sherpa recipe 与 Open flavor/adapter 均同步27，不用 manifest 标签掩盖26构建。控制端已按27重编并核对两库 note=27；独立 Android 模型探针已通过，产品 FireRed 已接线并提供 Android 测试入口，8295 验收仍未完成，runtime artifact 的 trusted hashes 继续留空/unverified。

## 3 WebRTC 前端编译

### 3.1 获取 Android checkout

Linux 构建机先按上游 prerequisites 准备 git、Python、编译依赖及 depot_tools 所需环境。WebRTC checkout 和 Android 依赖体积较大，应使用独立制品构建任务，不放进每次 App assemble。

```bash
mkdir -p /work/autovoice-native
cd /work/autovoice-native
git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git
export PATH="/work/autovoice-native/depot_tools:$PATH"
mkdir -p webrtc
cd webrtc
fetch --nohooks webrtc_android
gclient sync --revision src@8838a7c28655afbf9336063b71a51429e7d72774
cd src
git rev-parse HEAD
```

检查输出必须为指定 WebRTC 提交；把 depot_tools 实际提交和 `gclient revinfo` 一起归档。首次获取允许选择已说明的起始提交，后续构建必须从已归档 lock 读取版本。完成依赖准备及 hooks 后再应用本项目 GN/JNI 补丁，不能在改过的 checkout 上随意更新 main。

### 3.2 添加本项目 GN 与 JNI 目标

Task4 已新增 `AutoVoice/adapter-webrtc/native/`，存放 `BUILD.gn`、`frontend_jni.cc`、`frontend_session.cc`、头文件及导出符号规约。构建工具将该目录复制到 WebRTC checkout 的 `src/autovoice_frontend/`，并以受控补丁在根 `BUILD.gn` 的默认 group 中加入对该目标的依赖，使 GN 能发现它。

核心 GN 目标规约如下。这是已在固定 checkout 通过检查与编译的目标；实际文件还包含本库精确 JNI 导出配置：

```gn
import("//webrtc.gni")

config("autovoice_jni_exports") {
  inputs = [ "frontend_jni.exports" ]
  ldflags = [ "-Wl,--version-script=" + rebase_path("frontend_jni.exports", root_build_dir) ]
}

rtc_shared_library("autovoice_frontend_jni") {
  java_targets = []
  # Handwritten standard JNI has no generated JNI_OnLoad registration.
  suppressed_configs = [ "//build/config/android:hide_all_but_jni_onload" ]
  configs += [ "//third_party/jni_zero:jni_include_dir", ":autovoice_jni_exports" ]
  sources = [ "frontend_jni.cc", "frontend_session.cc", "frontend_session.h" ]
  deps = [
    "//api:scoped_refptr",
    "//api/audio:audio_processing",
    "//api/audio:builtin_audio_processing_builder",
    "//api/environment:environment_factory",
    "//common_audio:common_audio_c",
  ]
  allow_poison = [ "environment_construction" ]
}
```

APM 使用 `api/audio/builtin_audio_processing_builder.h` 和 `api/audio/audio_processing.h`；经典 VAD 使用 `common_audio/vad/include/webrtc_vad.h`，其 C 实现在 `common_audio:common_audio_c`，不要把 APM 内部的 VAD helper 当成公开经典 VAD API。上游 JNI/shared-library 模板会保留 WebRTC 的编译配置和递归依赖，不能只把一个 `libaudio_processing.a` 拿到另一个 CMake 工程里并期待依赖自动齐全。[APM 构建目标](https://webrtc.googlesource.com/src/+/refs/heads/main/api/audio/BUILD.gn)、[VAD 所属目标](https://webrtc.googlesource.com/src/+/refs/heads/main/common_audio/BUILD.gn)、[GN 模板](https://webrtc.googlesource.com/src/+/refs/heads/main/webrtc.gni)

根默认 group 新增的依赖为 `"//autovoice_frontend:autovoice_frontend_jni"`。只构建该目标及其依赖，不构建默认的完整 WebRTC SDK、PeerConnection、摄像头或第二套录音器。初次构建必须核对候选提交的实际 GN label，目标变更须更新补丁和版本清单，不关闭 GN 的依赖检查绕过问题。

本库抑制上游 Android 默认 `hide_all_but_jni_onload`，改用专属 `frontend_jni.exports`，只导出五个标准 `Java_com_autovoice_adapterwebrtc_WebRtcNative_*` 入口；其余符号继续隐藏。仅 SOLINK 成功不足以证明 JNI 可用，必须检查实际 `nm -D` 并在 Android 调用五个入口。

本项目采用手写标准 JNI：Kotlin `external` 声明由 Gradle 编译，native 导出 `JNIEXPORT` 标记的 `Java_com_autovoice_adapterwebrtc_WebRtcNative_*` 方法，不通过 GN 生成 Java 绑定。`java_targets=[]` 是 Android `rtc_shared_library` 包装层需要的显式空列表；省略会在其内部 JNI 目标生成时读取未定义变量。`<jni.h>` 来自 WebRTC Android 工具链的 NDK sysroot，GN 的 `jni_include_dir` 配置补足其 JNI 生成头搜索路径；不引用构建机桌面 JDK 头来代替 Android JNI。[JNI 构建模板](https://raw.githubusercontent.com/chromium/chromium/main/third_party/jni_zero/jni_zero.gni)

JNI 实现必须具备 create/processCapture/processRender/resetVad/release 能力：每次 capture/render 输入固定 10ms，capture 为 16k mono 160 samples；VAD 返回二值结果；capture 携带延迟信息。使用 `ShortArray`/直接缓冲和 opaque handle，在 JNI 内持有 APM/VAD，不跨 `.so` 暴露 WebRTC C++ 对象或 STL 容器。导出 JNI 必需符号，其余符号收口；所有 native 异常在边界转换成适配器错误。R8/ProGuard 保留 `WebRtcNative` 的包名、类名和 native 方法名，避免 release 包加载成功但找不到入口。

### 3.3 生成和编译

在 WebRTC `src/` 内执行；确保前述 JNI 代码和 GN 补丁已就绪：

```bash
gn gen out/AutoVoiceArm64 --check --fail-on-unused-args --args='
target_os="android"
target_cpu="arm64"
is_debug=false
is_component_build=false
symbol_level=1
rtc_include_tests=false
rtc_build_examples=false
rtc_build_tools=false
rtc_enable_protobuf=false
android_ndk_api_level=27
default_min_sdk_version=27
use_siso=false
enable_jni_multiplexing=false
use_hashed_jni_names=false
'
gn check out/AutoVoiceArm64 //autovoice_frontend:autovoice_frontend_jni
autoninja -C out/AutoVoiceArm64 autovoice_frontend_jni
gn desc out/AutoVoiceArm64 //autovoice_frontend:autovoice_frontend_jni outputs
```

`use_siso=false` 明确使用本文的 Ninja 路径；不要把上游默认 Siso 输出和手工 Ninja 命令混用。API 设置来自上游 Android GN 参数，生成时的 unused-args 校验用于发现版本差异，不允许静默忽略。[上游 GN Android 默认值](https://webrtc.googlesource.com/src/+/refs/heads/main/.gn)

以 `gn desc ... outputs` 返回的实际 `.so` 路径为准。交付运行用的 `libautovoice_frontend_jni.so`；`lib.unstripped` 等符号副本只做诊断归档。记录该库的 DT_NEEDED，若依赖额外运行库则一并交付并检查兼容性，不能只复制文件名看起来正确的主库。

### 3.4 APM 配置与声音验证

首版配置采用 AEC3、NS 和高通滤波的明确组合，AGC 先保持关闭，由设备测试决定是否启用；不同时默认开启系统和软件 AEC/NS。AEC 是否可用取决于 render 参考完整性和设备验收，不取决于库是否编译成功。

`ProcessReverseStream` 接收真实播放参考，`ProcessStream` 接收录音；32ms 到 10ms 的跨块重组、有效样本数和时间戳在适配器内完成。初次只做静音输入、确定性语音和人工回声数据测试；随后才验收真实扬声器回声、双讲和多路车机音频。

## 4 FireRed Android 运行时编译

### 4.1 准备 NDK 和源码

Linux 构建机安装 CMake、make、wget、unzip、JDK 和 Android command-line tools；使用自己的 SDK 目录：

```bash
export ANDROID_SDK_ROOT=/opt/android-sdk
sdkmanager --sdk_root="$ANDROID_SDK_ROOT" "ndk;27.2.12479018"
export ANDROID_NDK="$ANDROID_SDK_ROOT/ndk/27.2.12479018"
cd /work/autovoice-native
git clone --branch v2.0.1 --depth 1 https://github.com/k2-fsa/sherpa-onnx.git
cd sherpa-onnx
git rev-parse HEAD
```

提交必须为 `af2d8d81dbfa61c4cea4c568da78f3982edca005`。本方案使用独立 checkout，不复用已被其他模型/QNN 配置污染的 CMake build 目录。

### 4.2 编译 arm64 JNI

```bash
export BUILD_SHARED_LIBS=ON
export SHERPA_ONNX_ANDROID_PLATFORM=android-27
export SHERPA_ONNX_ENABLE_JNI=ON
export SHERPA_ONNX_ENABLE_C_API=OFF
export SHERPA_ONNX_ENABLE_BINARY=OFF
export SHERPA_ONNX_ENABLE_TTS=OFF
export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
export SHERPA_ONNX_ENABLE_QNN=OFF
export SHERPA_ONNX_ENABLE_RKNN=OFF
export SHERPA_ONNX_ONNXRUNTIME_VERSION=1.28.2
./build-android-arm64-v8a.sh
ls -l build-android-arm64-v8a/install/lib/
```

脚本从源码编译 sherpa/JNI，默认获取相应 ORT Android 预编译库；这不是“所有依赖均从源码编译”。产物中至少包含 `libsherpa-onnx-jni.so` 和 `libonnxruntime.so`，二者都要打包。若以后选择静态 ORT，必须作为独立构建配置重新验证，不能漏掉动态模式需要的 ORT。[v2.0.1 脚本及开关](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/build-android-arm64-v8a.sh)

首版关闭 QNN，不能只改 `SHERPA_ONNX_ENABLE_QNN=ON` 就声称 FireRed 已在 8295 NPU 上运行。需要单独完成模型后端支持、转换、驱动和设备权限验证。CPU 基线是本次交付条件。

### 4.3 Kotlin API 和 JNI 一起接入

从同一源码提交的 `sherpa-onnx/kotlin-api/` 获取完整且相互一致的 Kotlin API，保留包名 `com.k2fsa.sherpa.onnx` 和许可证，放入 `adapter-firered` 的第三方源码区并标记来源。不能从另一分支随意复制 `OfflineRecognizer.kt`，也不能只复制一个类而遗漏它依赖的配置/stream 类型。

目标接口为 `OfflineRecognizer`、`OfflineFireRedAsrCtcModelConfig`、`createStream()`、`acceptWaveform()`、`decode()`、`getResult()` 和显式 `release()`。模型配置设置 `fireRedAsrCtc.model`、`tokens`、`provider="cpu"`、`decodingMethod="greedy_search"`；线程数初始 1，再按 8295 测试比较 2/4。[固定版本 Kotlin API](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/sherpa-onnx/kotlin-api/OfflineRecognizer.kt)

采集 PCM16 必须先按小端读取为有符号 short，再除以 32768 转为 float；输入 16k mono。每个已分段输入创建独立 stream，识别后在 finally 释放；recognizer 在模型生命周期内复用。不能把 ByteArray 字节值直接当成波形，也不能反复为每段加载 740MB 模型。

## 5 FireRed 模型准备

模型采用已经导出的 CTC INT8 文件，本次不运行原 FireRed 的 PyTorch/TensorRT 导出流程：

```bash
mkdir -p /work/autovoice-native/models
cd /work/autovoice-native/models
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25.tar.bz2
tar -xjf sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25.tar.bz2
ls -lh sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25/model.int8.onnx
ls -lh sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25/tokens.txt
sha256sum sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25/model.int8.onnx
sha256sum sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25/tokens.txt
```

首次从上游制品取得资源后，记录经评审采用的实际文件摘要，后续下载及设备安装必须与锁定摘要比较。只计算刚下载文件的 hash 并不能自动证明它与预期版本一致；Task5 的 `adapter-firered/src/main/resources/firered-model-catalog.json` 固定已取得模型/tokens/许可证的实际摘要，准备工具与运行时共用此清单。[模型目录和文件说明](https://k2-fsa.github.io/sherpa/onnx/FireRedAsr/pretrained.html)

生产资源包包含模型、tokens、model manifest、许可证；测试 wav 进入验收语料，避免无意打进正式资源。模型在 App 可读的版本化目录预装，并通过 `ModelResourceLoader` 的完整校验后原子激活。正式 APK 不内嵌这份大模型；设备预置/开发资源安装步骤应提供给模型管理工具，运行期不从未知路径任意搜索模型。

验证时先在 8295 加载固定 wav 得到非空 final，再接麦克风。仅有 `.so` 加载成功不能证明模型兼容；仅跑通上游桌面命令也不能证明 Android 实机可用。

## 6 Android 模块打包

项目采用以下产物目录；JNI `.so` 不直接放进公共 `app/src/main/jniLibs`，防止两渠道一起携带全部算法：

```text
AutoVoice/
  adapter-webrtc/
    native/                               # Task4 已实现 GN 和 JNI 源码
    src/main/jniLibs/arm64-v8a/
      libautovoice_frontend_jni.so
  adapter-firered/
    src/main/kotlin/com/k2fsa/sherpa/onnx/  # 同提交上游 API
    src/main/jniLibs/arm64-v8a/
      libsherpa-onnx-jni.so
      libonnxruntime.so
```

二进制由制品下载/安装步骤放到这些受忽略目录，源码仓库只保存 JNI、构建脚本、来源信息、校验清单及许可证。普通 PR 下载带摘要的固定制品，不每次从 main 重编；更新 native 库才触发单独构建和设备验收。

App 的依赖规约为：

```kotlin
implementation(project(":adapter-iflytek")) // 两渠道共用唤醒
add("iflytekImplementation", project(":audio-frontend-legacy"))
add("openImplementation", project(":adapter-webrtc"))
add("openImplementation", project(":adapter-firered"))
```

`adapter-webrtc`、`adapter-firered` 都是 Android library，namespace 分别为 `com.autovoice.adapterwebrtc`、`com.autovoice.adapterfirered`，compileSdk 34；讯飞/公共/legacy minSdk 26，Open/其适配器 minSdk 27、ABI `arm64-v8a`，沿用项目 Kotlin/JVM 21。C++ bridge 在 WebRTC GN 内完成链接，Gradle 对这个模块只包装预构建 JNI，不二次使用 RNNoise 的 `ANDROID_STL=none` 配置链接它。

Open 不再直接/间接引入 Silero 的 `com.microsoft.onnxruntime:onnxruntime-android:1.19.0`；由 sherpa 配套的 ORT 提供 native runtime。发现同名 `libonnxruntime.so` 时先修依赖图，不使用 `pickFirst` 随机消除冲突；Kotlin/Java API、JNI 和 ORT 必须是测试过的一组。

## 7 构建与制品检查

完成设计中的模块和 flavors 后，在 `AutoVoice/` 执行：

```bash
./gradlew :app:assembleIflytekDebug :app:assembleOpenDebug -PuseIflytekStub=true
./gradlew :app:testIflytekDebugUnitTest :app:testOpenDebugUnitTest
./gradlew :app:lintIflytekDebug :app:lintOpenDebug
```

首行是 CI 可编译检查，stub 包不能用于真机讯飞唤醒验收。设备构建必须准备真实 AIKit AAR、唤醒资源与凭据，并去掉 `useIflytekStub=true`。实际任务名和制品目录随前述 flavor 名固定，更新部署脚本时一并核对。

Linux 可使用 NDK 工具检查 ELF：

```bash
export AUTOVOICE_READELF="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
"$AUTOVOICE_READELF" -h AutoVoice/adapter-webrtc/src/main/jniLibs/arm64-v8a/libautovoice_frontend_jni.so
"$AUTOVOICE_READELF" -d AutoVoice/adapter-webrtc/src/main/jniLibs/arm64-v8a/libautovoice_frontend_jni.so
"$AUTOVOICE_READELF" -d AutoVoice/adapter-firered/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so
"$AUTOVOICE_READELF" -d AutoVoice/adapter-firered/src/main/jniLibs/arm64-v8a/libonnxruntime.so
```

以上 ELF 检查从仓库根目录执行。确认 AArch64、运行库依赖齐全、无构建机绝对路径或测试库。检查实际设备页面大小和全部 JNI 的 segment/zip 对齐，若设备使用 16KB page，需要所有 native 依赖同时满足；不能只重编一个 bridge 就当作整包兼容。

APK 检查规则：两包允许包含讯飞 AAR；讯飞包不含 WebRTC/sherpa JNI、FireRed 模型；Open 包不含 RNNoise JNI、Silero 模型/旧 ORT，且只含一份正确的 `libonnxruntime.so`。通过调用计数或结构测试证明 Open 不初始化讯飞命令识别，不能仅凭 AAR 内存在命令插件判断运行路由。

native 制品清单记录：WebRTC/sherpa 源码提交、depot_tools/DEPS、所有补丁摘要、GN/CMake 选项、实际编译器和 NDK、ORT 版本、ABI/API、产物 SHA-256、许可证清单。模型清单单独记录模型与 tokens 摘要；不把模型训练版本和运行时编译版本混成一个版本号。

## 8 8295 验收顺序

1. 两渠道各启动一次，检查真实唤醒的授权、布防、命中及后台恢复；Open 的讯飞命令初始化计数为零。
2. Open 加载三份 native 库及模型，断网识别固定 wav；记录加载时长和峰值内存。
3. 接入实时麦克风，验证跨 32ms/10ms 边界的语音、首尾短词、长静音、尾帧和连续多轮，样本不缺失、不重复。
4. 对同一录音比较 FireRed CTC 与原讯飞本地命令成功率；记录识别结束延迟、RTF、CPU、热稳态和 PSS。
5. 接播放参考，验证回声、双讲、音量、路由、音乐/电话/导航混音；参考不完整时明确关闭播报期开放打断。
6. 验证联网候选竞速、离线本地命令、识别关闭、下电、队列满、损坏模型和迟到结果；业务不重复执行，旧结果不能重启交互。

以上均形成设备记录后才能把起始构建组合标为通过；文档中的版本选择与命令核对不替代实际编译及真机结果。

## 9 当前执行记录与环境限制

Task4 worker 在专用 Ubuntu26.04/WSL2 x86_64 完成固定源码 hooks、GN `--check --fail-on-unused-args`、`gn check` 和1602任务的实际 Ninja 构建。使用 DEPS 的 Clang24/Android 工具链，未把 sherpa 的 r27c 强套给 WebRTC。真实产物867144 bytes，SHA256 `d05f2718af6342274fc99a5a1b721bb28df826dada1eeb6db9426296da7c48df`；这是开发观测值，非发行认证。

已用 NDK llvm-readelf/nm 实查 ELF64/AArch64、Android note27、三个 LOAD 均0x4000、DT_NEEDED 仅 libc/libdl/libm/liblog、无 RPATH/RUNPATH、精确五个 JNI 导出。之前一次上游默认导出表隐藏了所有手写入口；该无效库未用于开发 staging，失败日志保留。最终 wrapper 完整构建和开发包校验通过，库进入工程外 devRoot，可信 lock 仍未改写。

控制端在独立无网络权限的 arm64/API33 APK 调用同一 JNI：官方语音510帧、435个 speech 帧；真实 capture/render/三个独立 reset/release、部分有效尾帧返回未知判定、受控错误和释放后拒绝均通过。此证据只证明实际 JNI/APM/classic VAD 可调用；不等于产品 Android 时间轴/播放参考整合、车内 AEC 质量、双讲或8295验收。

当时控制端也独立完成 sherpa API27 编译及 Android 模型探针。随后 Task5 实现产品 FireRed adapter/文本规则，Task6 接通播放、采集和云端协同。当前验收范围见本文开头。Mac 本地主要运行 JVM/Gradle，native 实际编译在上述 Linux 主机。

### Task4 公共输出与用途边界

`AudioFrontendEngine.process` 返回 `FrontendBatch(processedBlocks, vadObservations)`。PCM块带 startSample/validSamples/格式；观测带真实样本位置、有效长度、用途、speechDetected、可选 probability 和有序 events。Open 不制造 Silero 概率，512→160跨块 FIFO 保持所有有效样本；正常输入完成用 `finishBatch` 补齐DSP后截回有效尾部，尾部不补造VAD静音，随后再取分段。取消/设备关闭丢弃未提交尾部，不自动提交业务。

Legacy 继续一次512-sample Silero概率推理，再按原顺序运行480-sample RNNoise。包装坐标是兼容元数据，Legacy 消费者保留原事件、处理后 PCM 和时序。旧 `AudioFrontendFrame` 构造别名只保留源码兼容；它的单事件视图不允许静默丢弃 Open 多事件。

一个设备前端仅一个 APM，其配置/输入/关闭由有界单线程 backend 拥有；三份 classic VAD 与三份样本门控分属 main/followup/barge。resetVad/startTurn只重置对应检测/分段，不重置 APM。Legacy仍是原主VAD＋共享aux两实例。`attachReporting`/`attachAuxiliaryReporting`只重绑诊断，不重建引擎。

Open 接受自己的 aggressiveness0..3（候选默认2），运行时拒绝显式 legacy vad.threshold，包括显式0.5。Schema、Open资产和解析器一致；legacy比较处理参数时忽略概率字段存在标记等不消费的元数据，避免无参数变化重建。Open首版门限沿用资产64ms/960ms但按有效样本计算；库候选预卷100ms、最大段10s、默认每轮保留上限30s（溢出显式失败），均未声称经过车内噪声调优。

`RenderReferenceSink`接收单调播放代次、16k mono PCM16、样本位置和呈现时间，队列有界；旧代次、失序/过期或溢出撤销AEC参考健康。参考在capture处理线程消费一次；无播放为idle，不报声学故障。Task4 完成库端协议，Task6 接通 AudioTrack 参考和持续采集。辅助桥和设备打断分别门控，native VAD READY 本身不决定设备打断可用性。

### 开发 JNI 与发行分离

先使用严格工具生成开发 receipt，再显式打 Debug 包：

```sh
AutoVoice/tools/native/stage-native-artifacts.sh --mode dev \
  --archive /work/webrtc-android-arm64-v8a-unverified.zip \
  --expected-sha256 '<实际经核对的archive摘要>' \
  --dev-output /private/tmp/autovoice-native-dev-20261108

cd AutoVoice
./gradlew :app:assembleOpenDebug -PnativeDevRoot=/private/tmp/autovoice-native-dev-20261108
```

该属性分别将 `adapter-webrtc/src/main/jniLibs` 与 `adapter-firered/src/main/jniLibs` 的工程外开发目录接到相应 Debug source set；不复制进生产源码。Release任务图明确拒绝 nativeDevRoot；完整跨Debug/Release测试不用该属性。默认生产制品仍只能沿可信校验/staging路径交付。Iflytek包不因该属性增加 WebRTC，Open不携带legacy RNNoise/ORT。Task5 已在同一 devRoot 接入独立严格验证的 FireRed/ORT；trusted lock 仍保持 unverified。

## Task5 资源、准入和规则边界

见 [FireRed adapter 与真实 Android 测试说明](../AutoVoice/adapter-firered/README.md)。离线资源准备工具使用同一固定 catalog，完整校验后原子生成工程外版本目录；运行时再次校验。初始 CPU 单线程、单原生所有者、1 执行中 + 2 等待请求、每段最多 30 秒。关闭/超时令旧实例输出失效；运行中 JNI 完成后才释放。Task5 初版桥为关闭状态，Task6 已绑定 Coordinator 的真实请求事实。模型 READY 与完整本地路径可用性分别报告。

规则实际依赖为 `adapter-iflytek → local-command-rules → voice-core` 与 `voice-engine → local-command-rules`，避免厂商 adapter 依赖整套 engine/gateway。旧入口保持原历史行为；Open 仅整句支持既有车窗开关/退出，否定、多意图、引用偶提和其他文本 unknown。
