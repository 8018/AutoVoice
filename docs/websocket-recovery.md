# WebSocket 断线恢复方案

## 问题

导航拉起高德或应用进入后台后，网络、NAT、系统调度都可能让 WebSocket 失效。旧实现只在首次
`connect()` 时重试，异步 `onFailure` / `onClosed` 不清理客户端保存的 socket；业务层又单独维护
`readyReceived`，因此下一句话可能写入死连接并被牺牲，且没有可靠的恢复路径。

## 实现

- `GatewayClient` 统一维护 `DISCONNECTED / CONNECTING / READY / CLOSING` 状态；只有当前连接的
  close/failure 能清理状态，旧连接的迟到回调不会击穿新连接。
- OkHttp 每 15 秒发送 WebSocket ping；Activity 回到前台时预热连接，每次音频和 TTS 发送前仍执行
  `ensureReady()`。
- `ConnectivityManager.activeNetwork` 仅保留为诊断提示，不再作为云端链的硬门禁。连接和发送结果才是
  可用性的权威判断。
- 当前话语在尚未拿到有效回复时自动重连重发一次，复用同一个 `segmentId` / `utteranceId`，并用
  `attempt=0/1` 标识尝试次数。单次握手最长 3 秒，整轮最多两次。
- 重连的 `hello` 回带最近一次由服务端签发的 `sessionId`，恢复同一个会话上下文，Skills/MCP 无需另建
  会话。
- 服务端按“设备（无鉴权时为 session）+ utteranceId”缓存已完成结果 2 分钟。重复话语直接重放结果，
  避免重复执行 ASR、模型、工具调用；S2S 流式结果重放时收敛为一个完整音频 `reply`。
- 新增 `ws_connect_start / ws_ready / ws_reconnect_start / ws_reconnect_ok /
  ws_reconnect_failed` 遥测事件，归属当前 utterance。

## 恢复边界

已经收到 `audio_reply_start` 的 S2S 回复不会从头自动重播，因为此时播放器可能已经输出音频；该场景的
中断交给播放器和下一轮用户交互处理。自动重发只覆盖“尚未交付有效回复”的当前话语，因此不会造成双播。

## 验收

1. 正常问答后拉起导航，返回 AutoVoice，第一句话应直接成功，无需先失败一次。
2. 服务端主动关闭连接后，客户端状态应变为 `DISCONNECTED`，下一次连接重新握手并回带旧 sessionId。
3. 同一 session + utteranceId 重发时，服务端 ASR/模型只执行一次，第二次直接重放缓存结果。
4. `activeNetwork` 短暂为 null 但网关可连接时，云端仍可胜出。
5. Android、服务端 Gradle 全量测试及 shared schema fixture 校验全部通过。
