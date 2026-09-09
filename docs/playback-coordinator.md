# 播放生命周期提取（第二阶段）

PlaybackCoordinator 管理每次输出的 PlaybackIdentity(turnId, playbackId)，不管理对话当前轮，
不分析 ASR，也不参与仲裁。语义已准入后 VoiceEngine 才提交播放。

## 生命周期

prepare 分配独立播放 ID，使旧准备/播放失效；文本合成前就取得 ID。
合成或缓存结果返回时只允许该 ID 仍有效的请求开始播放。play 和 playStream 共用协调入口。
驱动返回 STARTED / COMPLETED / FAILED / INTERRUPTED，每次终局只采用一次，
无身份、旧身份和重复开始/结束事件都不映射到最新播放。
stop 先失效 ID，再中断流式播放消费者并停止驱动；不取消上游候选计算。

普通播放 STARTED 推进普通状态机；COMPLETED/FAILED 沿用现有结束策略进入延时聆听，
INTERRUPTED 不开启延时窗口。Realtime 使用独立播放 ID、空普通 turnId，
不会通过播放结束推进普通对话状态机或停止持续录音。

## Android 接线

AudioPlayer 增加携带身份的重载，保留旧实现用于兼容测试替身；生产实现必须传递身份。
TtsPlayer 的 MediaPlayer/AudioTrack 回调捕获固定身份，且校验驱动代次后才报告事件。
VoiceEngine 的 onTtsPlayEvent 交给协调器对账，不再用可变的最近轮次替迟到事件补身份。
旧的 playUtteranceId 暂只用于缓存遥测与已有准入判断，不再作为真实播放事件的归属依据。

## 范围与验证

本次提取播放生命周期；文本缓存/合成请求仍由 VoiceEngine 接线，不重写缓存协议。
不修改 VAD、仲裁、音频格式、音量策略或 AudioTrack 缓冲排空算法。
回归覆盖同轮不同播放、旧完成/失败事件、重复事件、过期合成、同步 stop 回调、
无身份事件，以及既有播放遥测跨轮回归。真机音频验收和安装待后续安排。
