package com.autovoice.server.offlinecommand;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.SlotValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则语义映射：离线命令词文本 → Canonical {@link Intent}（服务端移植版，与端侧
 * adapter-iflytek/RuleNluProvider.kt 的规则表逐字对齐）。
 *
 * <p>规则表集中定义在 {@link #DOMAIN_ALIASES}（领域别名）与 {@link #INTENT_RULES}
 * （意图规则），新增命令/领域只加表项，不改匹配逻辑。意图规则按声明顺序匹配，
 * 首个命中的生效。</p>
 */
public final class RuleNlu {

    /** 本 NLU 的 source 标识（与端侧一致：未命中 → {@link Intent#unknown(String)}）。 */
    public static final String SOURCE = "rule.nlu";

    /** temperature 槽位名（与 shared/contracts 的 slots 命名对齐）。 */
    public static final String TEMPERATURE_SLOT = "temperature";

    /**
     * 领域别名表：命令文本包含别名 → 领域（插入序保序，与端侧 linkedMapOf 对齐）。
     * 能力分级（2026-08-15）：云端命令词只负责空调；车窗归端侧命令词。
     * 例："空调"→climate（空调域，含打开/关闭/调温）。
     */
    public static final Map<String, String> DOMAIN_ALIASES = aliases();

    /**
     * 意图规则表：任一关键词命中即匹配该意图；extractNumber 为 true 时
     * 用正则提取首个数字 → temperature 槽位。顺序敏感（set_temperature 在前）。
     */
    private static final List<IntentRule> INTENT_RULES = List.of(
            new IntentRule("set_temperature", Set.of("调到", "调至"), true),
            new IntentRule("power_on", Set.of("打开"), false),
            new IntentRule("power_off", Set.of("关闭"), false));

    /** 提取命令文本中的首个数字（支持小数）。 */
    private static final Pattern NUMBER_REGEX = Pattern.compile("\\d+(\\.\\d+)?");

    /** 意图命中但无领域别名时的兜底领域（当前词表内命令均含领域别名）。 */
    private static final String DEFAULT_DOMAIN = "misc";

    private RuleNlu() {
    }

    private static LinkedHashMap<String, String> aliases() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        map.put("空调", "climate");
        return map;
    }

    /** 意图规则：任一关键词命中即匹配。 */
    private record IntentRule(String intent, Set<String> keywords, boolean extractNumber) {
    }

    /**
     * 命令文本 → {@link Intent}。
     * 领域：首个命中的领域别名；意图：首个命中的意图规则；均未命中 → {@link Intent#unknown(String)}。
     */
    public static Intent understand(String command) {
        String domain = DOMAIN_ALIASES.entrySet().stream()
                .filter(e -> command.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(DEFAULT_DOMAIN);

        IntentRule rule = INTENT_RULES.stream()
                .filter(r -> r.keywords().stream().anyMatch(command::contains))
                .findFirst()
                .orElse(null);
        if (rule == null) {
            return Intent.unknown(SOURCE);
        }

        Map<String, SlotValue> slots = Map.of();
        if (rule.extractNumber()) {
            Matcher m = NUMBER_REGEX.matcher(command);
            if (m.find()) {
                Double number = Double.parseDouble(m.group());
                slots = Map.of(TEMPERATURE_SLOT, SlotValue.number(number));
            }
        }

        return Intent.of("1.0", domain, rule.intent(), slots, 1.0, SOURCE, null);
    }
}
