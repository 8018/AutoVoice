package com.autovoice.adapteriflytek

import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.SlotValue

/**
 * 规则语义映射：离线命令词文本 → Canonical [Intent]。
 *
 * 规则表集中定义在 [DOMAIN_ALIASES]（领域别名）与 [INTENT_RULES]（意图规则），
 * 新增命令/领域只加表项，不改匹配逻辑。意图规则按声明顺序匹配，首个命中的生效。
 */
object RuleNluProvider {

    /** 本 NLU 的 source 标识（brief 约定：未命中 → [Intent.unknown]("rule.nlu")）。 */
    const val SOURCE = "rule.nlu"

    /** temperature 槽位名（与 shared/contracts 的 slots 命名对齐）。 */
    const val TEMPERATURE_SLOT = "temperature"

    /**
     * 领域别名表：命令文本包含别名 → 领域。
     * 能力分级（2026-08-15）：端侧命令词只负责车窗；空调归云端命令词。
     * 例："车窗"→window（车窗域）。
     */
    val DOMAIN_ALIASES: Map<String, String> = linkedMapOf(
        "车窗" to "window",
    )

    /**
     * 意图规则表：命中任一关键词即匹配该意图。
     * 能力分级：端侧只留车窗开关（power_on/power_off）；空调调温归云端命令词。
     */
    val INTENT_RULES: List<IntentRule> = listOf(
        IntentRule(intent = "power_on", keywords = setOf("打开")),
        IntentRule(intent = "power_off", keywords = setOf("关闭")),
    )

    /** 意图规则：任一关键词命中即匹配。 */
    data class IntentRule(
        val intent: String,
        val keywords: Set<String>,
        val extractNumber: Boolean = false,
    )

    /** 提取命令文本中的首个数字（支持小数）。 */
    private val NUMBER_REGEX = Regex("""\d+(\.\d+)?""")

    /**
     * 命令文本 → [Intent]。
     * 领域：首个命中的领域别名；意图：首个命中的意图规则；均未命中 → [Intent.unknown]([SOURCE])。
     */
    fun understand(command: String): Intent {
        val domain = DOMAIN_ALIASES.entries
            .firstOrNull { (alias, _) -> command.contains(alias) }
            ?.value ?: return Intent.unknown(SOURCE)

        val rule = INTENT_RULES.firstOrNull { r -> r.keywords.any { command.contains(it) } }
            ?: return Intent.unknown(SOURCE)

        val slots = if (rule.extractNumber) {
            val number = NUMBER_REGEX.find(command)?.value?.toDoubleOrNull()
            if (number != null) mapOf(TEMPERATURE_SLOT to SlotValue.Number(number)) else emptyMap()
        } else {
            emptyMap()
        }

        return Intent(
            schemaVersion = "1.0",
            domain = domain,
            intent = rule.intent,
            slots = slots,
            confidence = 1.0,
            source = SOURCE,
        )
    }
}
