# 端云退出当前对话：实现与验收

## 语义与边界

- 本地命令 NLU、云端命令 NLU、在线 ASR 后的规则 NLU、业务 LLM 均可产出 `conversation/exit_dialogue`。
- 本地退出与车窗一样立即进入端侧 FIFO。云端返回退出也可胜出；不人为等本地，不取消并发识别生产者。
- 仲裁不判断当前轮。会话准入通过后，业务入口清理导航选择、停止播报并复位为 DORMANT，恢复唤醒监听。
- 退出只结束语音对话，不关闭 App，不退出正在运行的导航应用。锁定闲聊域维持原 `exit_chat` 路径。
- ASR 文本做全句规范化匹配，不用“包含退出”识别，避免把“退出导航后回家”当作结束对话。

## 部署注意

云端 Java NLU 映射不等于原生 ASR 自动增加命令词。Linux FSA 是独立资源，不随 jar 发布。
仓库提供 `AutoVoiceServer/deploy/offline-command-fsa.utf8.txt`（空调 + 退出完整词表）。部署时需：

1. 确认目标环境 `AUTOVOICE_OFFLINE_FSA_PATH`，备份当前词表，不覆盖 SDK 原始资源。
2. 将 UTF-8 词表转换为 GBK 到新的版本化文件，设置服务用户可读权限，再更新该环境的 FSA 路径。
3. 在批准的发布窗口重启对应网关/离线进程并验证实际命中。不要在未授权时修改生产。

Android 在每次加载 FSA 前按代码词表校验/更新文件。若真机仍返回 unknown，需同时采集原生 SDK
初始化与识别日志、实际加载的 FSA 内容和错误码。旧文件的 UID 或修改时间本身不能证明权限故障。

## 自动回归

- 全句退出及空格、标点规范化；业务指令包含“退出”不被规则误拦。
- Classic / Hybrid 在线 ASR 命中退出时不调用 LLM，不启动闲聊。
- DeepSeek / Qwen `exit_dialogue` 工具解析为终局语义，不分发外部工具。
- 云端离线退出可胜出且不取消 LLM；端侧已有本地立即胜出测试。
- 云端退出也复位 DORMANT；复位后不播放确认音、不因迟到结果恢复聆听。
- 延时聆听检测回调执行时复查状态，退出后的排队回调不重新打开 capture；正常聆听仍可打开。

## 真机验收（待连接手机并部署云端后执行）

2026-09-27 本地验证：服务端 `test` 全量通过；Omni `test -x :app:test :app:bootJar`
通过（与 CI 的 Omni 矩阵一致）；Android `test lint :app:assembleDebug androidUnitTestCoverage`
使用 SDK stub 通过。另已构建 `AUTOVOICE_APP_ENV=dev` 的真实 SDK debug APK，未安装。
覆盖率门禁均通过：服务端 83.70%，voice-core + gateway-client 汇总 88.06%，app 36.65%，
audio-frontend 73.08%，adapter-iflytek 28.71%。这些不代表真机 SDK 或实际模型识别已验收。

1. 普通对话中说“退出/退出对话/结束当前会话”：检查 `exit_dialogue`、胜出路由、DORMANT；只保留唤醒监听。
2. 本地拒识时说自然退出表述，验证云端 NLU 或 LLM 胜出后同样退出。
3. 在导航选择期间退出：弹窗消失，不拉起导航；再唤醒能正常交互。
4. 退出瞬间持续说话：不得被旧延时聆听回调拉回录音。重新说唤醒词应正常录音。
5. 说“退出导航后回家”：不得误触发对话退出。

历史日志只确认部分轮次本地返回 unknown、云端返回普通回复，未确认词表权限问题是根因。
本轮修复的排队回调缺少状态复查是代码层风险，不等于已复现用户那次真机故障。
