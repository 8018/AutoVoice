package com.autovoice.server.offlinecommand;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.SlotValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规则语义映射测试：领域别名 / 意图关键词 / 数字槽提取 / unknown 兜底。 */
class RuleNluTest {

    @Test
    void powerOnClimate() {
        Intent i = RuleNlu.understand("打开空调");
        assertEquals("climate", i.domain());
        assertEquals("power_on", i.intent());
        assertTrue(i.slots().isEmpty());
        assertEquals(1.0, i.confidence());
        assertEquals(RuleNlu.SOURCE, i.source());
    }

    @Test
    void powerOffClimate() {
        Intent i = RuleNlu.understand("关闭空调");
        assertEquals("climate", i.domain());
        assertEquals("power_off", i.intent());
    }

    @Test
    void powerOnWindowFallsBackToMisc() {
        // 能力分级（2026-08-15）：云端命令词只负责空调——车窗别名已删除，
        // "打开车窗" 兜底 misc/power_on（云端仲裁器视为非空调命中，走 LLM 优先路径）
        Intent i = RuleNlu.understand("打开车窗");
        assertEquals("misc", i.domain());
        assertEquals("power_on", i.intent());
    }

    @Test
    void setTemperatureExtractsNumber() {
        Intent i = RuleNlu.understand("空调调到24度");
        assertEquals("climate", i.domain());
        assertEquals("set_temperature", i.intent());
        SlotValue slot = i.slots().get(RuleNlu.TEMPERATURE_SLOT);
        assertEquals(24.0, slot.value());
    }

    @Test
    void setTemperatureDecimal() {
        Intent i = RuleNlu.understand("空调调至16.5度");
        assertEquals("set_temperature", i.intent());
        assertEquals(16.5, i.slots().get(RuleNlu.TEMPERATURE_SLOT).value());
    }

    @Test
    void domainAliasMissingFallsBackToMisc() {
        // 无领域别名但意图关键词命中 → 兜底领域 misc（与端侧规则一致）
        Intent i = RuleNlu.understand("打开电视");
        assertEquals("misc", i.domain());
        assertEquals("power_on", i.intent());
    }

    @Test
    void noKeywordMatchIsUnknown() {
        Intent i = RuleNlu.understand("我想听周杰伦的专辑");
        assertTrue(i.isUnknown());
        assertEquals(RuleNlu.SOURCE, i.source());
    }

    @Test
    void intentRuleOrderTemperatureBeforePower() {
        // 关键词"调到"命中 set_temperature；"打开"不在文本中，顺序不影响本例
        Intent i = RuleNlu.understand("空调调到20度");
        assertEquals("set_temperature", i.intent());
        assertEquals(20.0, i.slots().get(RuleNlu.TEMPERATURE_SLOT).value());
    }
}
