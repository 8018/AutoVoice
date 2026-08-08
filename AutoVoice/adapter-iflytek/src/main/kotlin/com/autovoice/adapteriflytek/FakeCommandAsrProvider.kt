package com.autovoice.adapteriflytek

/**
 * 模拟命令词识别：与真实 [IflytekOfflineCommandAsrStage] 同一边界
 * （`recognize(pcm: ByteArray): String?` → 命令词文本）。
 * 配置 `local.asr=iflytek.fake-cmd` 时使用（见 app 端 demo-full.json），
 * 用于无 SDK 授权/无真机时的本地链路演示。
 */
object FakeCommandAsrProvider {

    /** 固定返回的命令词（brief 明文）。 */
    const val FIXED_COMMAND = "打开空调"

    /**
     * 模拟识别：
     * - 非空 PCM → 固定返回 [FIXED_COMMAND]（"打开空调"）；
     * - 空输入 → null，模拟真实引擎 VAD 未检出语音的行为（与真实 ASR 同一输出边界）。
     */
    fun recognize(pcm: ByteArray): String? =
        if (pcm.isEmpty()) null else FIXED_COMMAND
}
