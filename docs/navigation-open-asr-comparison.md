# 开源离线识别对比基准

本批按用户批准的方案，先对比中文流式 Zipformer 和 SenseVoice Small，再决定是否替换讯飞离线识别。讯飞唤醒、云端识别以及现有回声修复保持原状。当前接入是独立 Android 测试组件，不是主应用识别器切换。对比结论是优先选择 SenseVoice Small＋Silero 语音活动检测；真实麦克风、播报打断以及主应用替换尚未验收。

## 固定输入和模型

- v1 共 29 条：10 条候选选择/翻页/重播、4 条导航指令、9 条地点或完整导航句子、1 条否定表达、2 条无语音、2 条合成加噪、1 条合成播报叠加。
- `docs/qa/navigation-asr-cases-v1.json` 是业务定义，`navigation-asr-fixtures-v1.json` 是固定语音清单。公开合成语音保存在 `tools/qa/fixtures/navigation-asr-v1/audio/`，没有用户录音或私有地点。
- `navigation-asr-models-v1.json` 固定下载版本、文件大小、SHA-256 与许可来源。模型不入库。
- Zipformer：`sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20`，量化 encoder/joiner，浮点 decoder，模型卡 Apache-2.0。选用它是因为模型权重许可明确；先前调研的 2025 中文包未找到足够明确的权重许可，未将其用于交付候选。
- SenseVoice：`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`，原始权重来自 FunAudioLLM SenseVoiceSmall。模型使用单独 FunASR Model Open Source License Agreement v1.1，许可文本与原模型名保留于 `docs/qa/asr-model-licenses/`；不将框架 MIT/Apache 许可套用给权重。

## 修复轮次：显式语音检测

原始模型分别测试两次：Zipformer 均 25/29；SenseVoice 均 27/29，但把静音/噪声识别成“我”。没有修改预期或过滤这个文字，而是增加独立的 `silero` 模式。

该模式复用项目已有 Silero 模型和 `VoiceActivityGate`，阈值 0.5，两个连续热帧（每帧 512 samples / 32 ms，最后不足一帧补零）后允许识别；64-sample 历史上下文随帧携带，每条用例重置。未检测到语音时完全跳过 ASR，检测到语音后把原始整句 PCM 原样交给模型。VAD 本身不做回声消除。

模型和 MIT 许可固定在 `navigation-asr-vad-v1.json`，许可保留于 `asr-model-licenses/silero-MIT.txt`。原始与 VAD 模式分开记录；缺失/矛盾的模式元数据会判失败，不能把 VAD 改善伪装成原始模型改善。

## 测量和通过条件

同一台手机、相同 PCM16 单声道 16 kHz 文件、CPU 两线程、贪心解码；依次载入和释放两个模型。流式 Zipformer 以 100 ms 数据块无等待喂入，并追加 500 ms 静音清空右上下文。SenseVoice 按句识别，中文模式、文本规范化开启。

保存每条转写、精确匹配、字符错误率、解码耗时、RTF，及各组 P50/P95。标点、空白和“第2个/第二个”可归一，否定词和其他文字不能删除。默认逐条要求匹配，RTF ≤ 1、解码 ≤ 15 秒；噪声和叠加用例的失败也明确保留，不删掉以取得绿色结果。统计的是 ASR 转写，不等于业务动作已经执行正确。

解码耗时从创建语音流到取得最终结果并释放流，包含流式静音清空计算；不包含读取文件、模型加载、实时录音、端点等待、网络或业务执行。模型加载另计。首个非空结果字段仅在原始音频分块阶段产生，若只在清空阶段得到文字，则字段缺省，不能解释成零延迟。PSS 是整个测试进程的内存快照，不是模型独占内存或峰值。

原始结果记录实际目标 APK、测试 APK 和两项原生库的摘要，以及运行时版本。评分时的 Git 版本/源码指纹另行记录，不能冒充手机执行版本。评分文件内嵌原始结果，保留所有测量信息。

**这不是回声消除验收。** 数字叠加只测试模型收到混合音频后的行为；不能模拟扬声器、房间声学和真实用户双讲。主包替换仍需真实播报时“第二个”打断、纯播报不误触发、拒识/超时和业务动作检查。单次固定顺序运行也不足以证明模型稳定胜出；候选选择应重复测量并检查热机和顺序影响。

## 重跑方法

1. 按已有构建方法生成 Open Debug 测试 APK。当前开发原生库由 `-PnativeDevRoot=/private/tmp/navigation-open-device-native` 显式提供，不能当作生产原生库供应链验收。
2. 在手机上安装匹配的 Open 目标包及测试包；已安装且原生库相容时只更新测试组件。不要用 stub 构建覆盖用户当前讯飞主包。安装可能使手机无线调试换端口，完成后重新核对连接。
3. 准备固定资源（以下路径仅为示例）：

```sh
python3 tools/qa/navigation-asr-provision.py /private/tmp/autovoice-open-asr-comparison/device
```

4. 指定当前设备地址和刚构建的测试 APK。工具会校验清单、顺序传输缺少的文件、移除旧结果、运行识别、拉取并评分：

```sh
python3 tools/qa/navigation-asr-device.py \
  --adb /Users/michaelliu/Library/Android/sdk/platform-tools/adb \
  --serial PHONE_IP:PORT \
  --assets /private/tmp/autovoice-open-asr-comparison/device \
  --test-apk AutoVoice/app/build/outputs/apk/androidTest/open/debug/app-open-debug-androidTest.apk
```

在同一命令追加 `--pipeline silero` 测试语音检测加识别；追加 `--flush-ms 1000` 做 Zipformer 收尾诊断。默认仍为 raw/500，原基线不覆盖。

5. 结果保存在 `docs/qa/navigation-asr-runs/`。缺资源、断连、原生崩溃、不完整结果、缺少任一模型/用例、APK 不匹配、转写错误或超预算均不能算通过。工具不安装应用、不执行导航、不录音。
6. 同批运行 `node --test tools/qa/tests/*.test.mjs`、相关 Android 检查和 AGENTS.md 的全部 dev 云端门槛。硬件未运行时标记未运行。

`navigation-asr-prepare.mjs` 用于生成下一版候选公开语音，不用于覆盖本版。新增业务应保留原用例，建立新版本案例/清单和评分入口，报告各组变化；生成后审核音频、保存其校验值再成为基准。

## 真机结果与验收状态

手机 PHB110；native sherpa-onnx 2.0.1、ONNX Runtime 1.28.2。完整原始/评分记录见 `docs/qa/navigation-asr-runs/`，文件名使用 UTC（北京时间为 11 月 14 日）。

| 测试轮次 | Zipformer | SenseVoice | SenseVoice 文件处理 P50 / P95 |
|---|---:|---:|---:|
| raw 第一次 | 25/29 | 27/29 | 56.4 / 88.2 ms |
| raw 重复 | 25/29 | 27/29 | 57.2 / 89.1 ms |
| Silero＋ASR，500 ms 收尾 | 25/29 | **29/29** | 54.5 / 73.3 ms |
| Silero＋ASR，1000 ms 收尾诊断 | 27/29 | **29/29** | 54.8 / 73.1 ms |
| Silero＋ASR，500 ms 同参数复测（断线后取回） | 25/29 | **29/29** | 54.0 / 77.5 ms |

1000 ms 收尾修复了 Zipformer 的“下一页”“都不是”漏字，但“上一页”仍被识别为“上一夜”，“结束导航”仍被识别为“接触导航”。SenseVoice 两次原始测试的两项错误仅为无语音误识别，加入真实语音检测后三次完整测试均 29/29。三次 gated 测试中只有一次的 Zipformer 收尾参数不同，SenseVoice 参数相同。比较套件仍保留 overall passed=false，因为 Zipformer 未全部通过；不能宣称两个模型全部通过。

第三次 gated 重复在取回结果时无线调试断开，原失败证据保留。用户提供新端口 39475 后取回同一开始时间的完整报告，核对执行 APK、两模型的全部 29 条结果和输入摘要一致，再用当前评分器重新判定；恢复的记录以 `recovered-raw/score.json` 保存。系统 AdbDebuggingManager 日志明确记录 `Detected wifi network change. Disabling adbwifi.`；这解释了开关被系统关闭，但没有进一步证据区分 Wi-Fi 漫游、双 Wi-Fi 切换或短暂掉线。重新开启后端口变化，原端口不能继续使用。

- 评分/基准离线测试 **38/38**，手机输入/分帧校验 **11/11**。
- Android 应用 Open **261/261**、讯飞 **255/255**；Open lint 和测试 APK 构建通过。
- 最终 dev 运行版本 `b8a9f275b7792a4e0ff68b20741ea313654e7c47` 已重新核实；文本 **33/33**、路线 **3/3**、合成语音 **3/3**、只读统计 **3/3**。
- 最终文本平均 **1226 ms**、P95 **2567 ms**；充电站/参照地名场景分别 **2567/2968 ms**，超过单场景 2500 ms 指标；整体和各版本组门槛通过，仍保留这些延迟提醒。
- 没有部署服务器，也没有把当前讯飞主包改成开源识别。手机安装的是比较测试组件，现有 Open 目标包未替换；尚未通过真实说话、车内噪声、实际扬声器回声和双讲验收。

## 下一交付门槛

将胜出的 SenseVoice＋Silero 接入应用识别生命周期后，必须继续测录音到结果的端到端耗时、断网、连续轮次、取消/过期回调，并在人声与扬声器同时发声时验证“第二个”能正确打断。公开合成文件 29/29 不足以批准主包替换；唤醒和云端识别仍沿用当前可用实现。

来源：[sherpa Android](https://github.com/k2-fsa/sherpa-onnx)、[Zipformer 模型卡](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20)、[SenseVoice Android 模型](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html)、[SenseVoice 权重许可说明](https://github.com/QwenAudio/SenseVoice#license)。
