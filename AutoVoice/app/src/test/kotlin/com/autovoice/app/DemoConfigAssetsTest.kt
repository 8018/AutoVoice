package com.autovoice.app

import com.autovoice.voicecore.DemoConfig
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Task 21 配置资产漂移守卫：`src/main/assets/demo-full.json` / `demo-offline.json`
 * 必须能按 [DemoConfig.fromJson] 解析，且关键字段符合双模式约定（与 config.schema.json
 * 对齐，不重复契约文件，仅在配置漂移时红灯）。纯 JVM：Gradle 测试任务工作目录 = 模块目录，
 * 相对路径即模块内路径（与 voice-core 的 fixtures 读取模式同理）。
 */
class DemoConfigAssetsTest {

    private fun readAsset(name: String): String {
        val file = File("src/main/assets/$name")
        assertTrue(
            file.isFile,
            "缺失配置资产 src/main/assets/$name（工作目录=${System.getProperty("user.dir")}）",
        )
        return file.readText()
    }

    @Test
    fun `demo-full parses to full-mode cloud-first config`() {
        val cfg = DemoConfig.fromJson(readAsset("demo-full.json"))
        assertEquals("full", cfg.mode)
        assertTrue(cfg.cloud.enabled, "demo-full 云端优先：cloud.enabled 必须为 true")
        // A1：waitMs 3000（用户定：端云仲裁 3s，云端优先，3s 未回用端侧；pending 占位
        // 会在窗口内到达并延长等待，LLM 长循环不再静默超时，窗口无需放宽）
        assertEquals(3000L, cfg.cloud.waitMs)
        assertTrue(
            cfg.cloud.gatewayUrl.isNotBlank(),
            "demo-full 的 gatewayUrl 不得为空（占位符 ws://10.0.2.2:8080/ws，真机演示时改网关地址）",
        )
        // Task 34 接线后：服务已开通 + 凭据已注入（local.properties），真实离线命令词
        assertEquals("iflytek.offline", cfg.local.asr)
        assertEquals("rule.nlu", cfg.local.nlu)
        assertEquals("rnnoise", cfg.ecnr)
        assertFalse(cfg.mock.executor)
    }

    @Test
    fun `demo-offline parses to offline-mode local-only config`() {
        val cfg = DemoConfig.fromJson(readAsset("demo-offline.json"))
        assertEquals("offline", cfg.mode)
        assertFalse(cfg.cloud.enabled, "demo-offline 仅本地：cloud.enabled 必须为 false")
        // A1：waitMs 3000（与 demo-full 同步；offline 模式不用但保持配置一致）
        assertEquals(3000L, cfg.cloud.waitMs)
        assertEquals("", cfg.cloud.gatewayUrl)
        // Task 34 接线后：服务已开通 + 凭据已注入（local.properties），真实离线命令词
        assertEquals("iflytek.offline", cfg.local.asr)
        assertEquals("rule.nlu", cfg.local.nlu)
        assertEquals("rnnoise", cfg.ecnr)
        assertFalse(cfg.mock.executor)
    }
}
