# 测试覆盖率基线

更新日期：2026-09-10

## 统计口径

- JVM 和 Android 使用 JaCoCo 行覆盖率。
- Web 使用 Vitest V8 的 lines/statements/branches/functions 覆盖率。
- Android `app`、`audio-frontend` 与 `adapter-iflytek` 使用 Debug JVM 单测生成
  `jacocoDebugUnitTestReport`；真机 `androidTest` 不计入该报告。
- CI 中的门槛是“防回退基线”，不代表所有模块已经达到目标覆盖率。

## 当前基线

| 范围 | 当前行覆盖率 | CI 最低值 |
| --- | ---: | ---: |
| 服务端 JVM 模块加权合计 | 81.68% | 75% |
| Android `voice-core` + `gateway-client` | 89.29% | 70% |
| Android `app` | 28.64% | 25% |
| Android `audio-frontend` | 71.48% | 70% |
| Android `adapter-iflytek` | 24.68% | 20% |
| Telemetry Web | 93.28% | 80% |
| Skill Manager Web | 63.29% | 60% |

Web 门槛还会同时检查 statements、branches 和 functions。两个 Web 已覆盖
历史加载/筛选/明细、SSE 去重与关闭、登录失败、Skill 编辑/工具发现/勾选保存、
禁用以及系统提示词更新。

## CI 行为

1. Android job 执行 `test`、`lint`、Debug APK 构建和 `androidUnitTestCoverage`。
2. 每个 Android 模块上传 HTML/XML 报告，低于上表基线则 CI 失败。
3. Classic 和 Omni 服务端 job 分别对当前编译变体的 JaCoCo 报告加权，低于 75% 失败。
4. Web `coverage` 命令直接执行 Vitest thresholds，不再只生成报告。

## 仍需补齐的部分

- `app` 含 Android 音频、生命周期和 Compose 界面，普通 JVM 单测无法覆盖全部路径。
  后续应把纯逻辑继续提取到无 Android 依赖类，并将 `app` 基线逐步提高到 40%。
- `adapter-iflytek` 的 SDK 回调、授权和 native 能力只能使用真实 AAR 和真机完整验证。
- 麦克风/AEC/VAD 连续音频、TTS `AudioTrack`、高德 App 拉起及后台切换属于设备端集成验收，
  不应用 JVM 覆盖率数字代替。
