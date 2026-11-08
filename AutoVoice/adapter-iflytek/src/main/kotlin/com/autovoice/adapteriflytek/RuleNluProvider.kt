package com.autovoice.adapteriflytek

import com.autovoice.localcommandrules.LocalCommandRules
import com.autovoice.voicecore.Intent

/** Historical vendor entrypoint/types; rule policy is owned once by the small pure leaf module. */
object RuleNluProvider {
    const val SOURCE = LocalCommandRules.SOURCE
    const val TEMPERATURE_SLOT = LocalCommandRules.TEMPERATURE_SLOT
    val DOMAIN_ALIASES = LocalCommandRules.DOMAIN_ALIASES
    val EXIT_COMMANDS = LocalCommandRules.EXIT_COMMANDS
    data class IntentRule(val intent: String,val keywords: Set<String>,val extractNumber: Boolean = false)
    val INTENT_RULES: List<IntentRule> = LocalCommandRules.INTENT_RULES.map { IntentRule(it.intent,it.keywords,it.extractNumber) }
    fun understand(command: String): Intent = LocalCommandRules.legacy(command)
}
