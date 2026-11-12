package com.autovoice.localcommandrules
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class LocalCommandRulesTest {
    @Test fun `strict grammar supports only existing whole window and exit commands`() {
        for ((text,domain,intent) in listOf(Triple("打开车窗","window","power_on"),Triple("关闭车窗。","window","power_off"),
            Triple(" 打 开 车 窗！ ","window","power_on"),Triple("退出当前对话","conversation","exit_dialogue"))) {
            val result = LocalCommandRules.strict(text)
            assertEquals(domain,result.domain);assertEquals(intent,result.intent)
            assertEquals("rule.text",result.source);assertEquals(1.0,result.confidence);assertTrue(result.slots.isEmpty())
        }
        for (exit in LocalCommandRules.EXIT_COMMANDS) assertEquals("exit_dialogue",LocalCommandRules.strict(exit).intent)
    }
    @Test fun `free transcripts never substring trigger negated multiple incidental or ambiguous control`() {
        for (text in listOf("不要打开车窗","别关闭车窗","不退出对话","打开车窗然后关闭车窗","打开车窗和退出对话", "关闭车窗打开车窗",
            "他说打开车窗","说明书里有打开车窗这句话","“打开车窗”","\"退出对话\"","打开车窗吗","打开车窗？","打开车窗;", "打开，车窗",
            "退出导航后回家","打开空调","空调调到24度","打开车门","讲个笑话","请帮我打开车窗","")) {
            assertTrue(LocalCommandRules.strict(text).isUnknown(),text)
        }
    }
    @Test fun `legacy keeps historical substring precedence and exact exit containment`() {
        val on = LocalCommandRules.legacy("不要打开车窗然后关闭车窗")
        assertEquals("window",on.domain);assertEquals("power_on",on.intent);assertEquals("rule.nlu",on.source)
        assertEquals("1.0",on.schemaVersion);assertEquals(1.0,on.confidence);assertTrue(on.slots.isEmpty())
        assertEquals("power_off",LocalCommandRules.legacy("请关闭车窗").intent)
        assertEquals("exit_dialogue",LocalCommandRules.legacy(" 退出 对话。 ").intent)
        assertTrue(LocalCommandRules.legacy("退出导航后回家").isUnknown())
        assertTrue(LocalCommandRules.legacy("打开空调").isUnknown())
    }
}
