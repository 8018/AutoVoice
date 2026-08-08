package com.autovoice.adapteriflytek

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

/**
 * 真实讯飞引擎（AIKit AEE，native + Android Context）无法在 JVM 单测中运行，
 * 这里只覆盖可 JVM 测试的部分：未配置时的明确报错、车控词表、FSA 命令词文件生成格式。
 * 真实引擎编译期由 :adapter-iflytek:assembleDebug 验证。
 */
class IflytekOfflineCommandAsrStageTest {

    @Test
    fun `recognize without configured credentials throws clear error`() {
        val stage = IflytekOfflineCommandAsrStage(appId = "", apiKey = "", apiSecret = "")
        val ex = assertThrows(IllegalStateException::class.java) {
            stage.recognize(byteArrayOf(0, 1, 2, 3))
        }
        assertTrue(ex.message!!.contains("讯飞离线命令词 SDK 未配置，请切换 local.asr=iflytek.fake-cmd"))
    }

    @Test
    fun `command word list covers car control commands`() {
        val words = IflytekOfflineCommandAsrStage.COMMAND_WORDS
        assertTrue(words.contains("打开空调"))
        assertTrue(words.contains("关闭空调"))
        assertTrue(words.contains("打开车窗"))
        assertTrue(words.contains("关闭车窗"))
        // 温度枚举范围包含 16..30 度
        assertTrue(words.contains("空调调到24度"))
    }

    @Test
    fun `fsa content matches demo format and gbk roundtrip`() {
        val content = IflytekOfflineCommandAsrStage.fsaContent()
        assertTrue(content.startsWith("#FSA 1.0;\r\n"), "FSA 头格式与 demo 资源一致")
        assertTrue(content.contains("\r\n<esr>:"), "命令词行前缀")
        assertTrue(content.contains("打开空调|关闭空调"), "命令词按 | 分隔")
        assertTrue(content.endsWith(";\r\n"), "文件以分号结尾")
        // 引擎侧 FSA 为 GBK 编码（与 SDK 归档 resource/CNENESR/fsa/cn_fsa.txt 一致），保证编码可逆
        val gbk = Charset.forName("GBK")
        val bytes = content.toByteArray(gbk)
        assertEquals(content, String(bytes, gbk))
    }
}
