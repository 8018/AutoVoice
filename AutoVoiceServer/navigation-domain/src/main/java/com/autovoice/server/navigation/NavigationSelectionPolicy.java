package com.autovoice.server.navigation;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stateless transcript matching; storage, expiry and contract adaptation live elsewhere. */
final class NavigationSelectionPolicy {
    private static final Pattern ARABIC_ORDINAL = Pattern.compile("(\\d+)");
    private static final Pattern NEW_NAVIGATION_REQUEST = Pattern.compile(
            ".*(导航去|导航到|带我去|我要去|想去|前往|开车去|出发去).*");
    private static final Map<Character, Integer> CHINESE_NUMBERS = Map.of(
            '一', 1, '二', 2, '两', 2, '三', 3, '四', 4, '五', 5,
            '六', 6, '七', 7, '八', 8, '九', 9);

    Decision decide(PendingNavigationSelection selection, String transcript) {
        if (transcript == null || transcript.isBlank()) return Decision.noMatch();
        String compact = compact(transcript);
        if (compact.matches(".*(取消|算了|不去了|关闭).*")) return Decision.cancel();

        Integer ordinal = ordinal(compact);
        if (ordinal != null) {
            if (ordinal < 1 || ordinal > selection.candidates().size()) {
                return Decision.outOfRange(ordinal);
            }
            return Decision.select(selection.candidates().get(ordinal - 1));
        }

        String choice = compact.replace("选择", "").replace("选", "")
                .replace("导航到", "").replace("导航去", "").replace("去", "")
                .replace("这个", "").replace("那个", "");
        if (choice.length() < 2) return Decision.noMatch();

        List<NavigationCandidate> exactNames = selection.candidates().stream()
                .filter(candidate -> compact(candidate.poiname()).equals(choice)).toList();
        if (exactNames.size() == 1) return Decision.select(exactNames.getFirst());
        List<NavigationCandidate> exactAddresses = selection.candidates().stream()
                .filter(candidate -> !candidate.address().isBlank()
                        && compact(candidate.address()).equals(choice)).toList();
        if (exactAddresses.size() == 1) return Decision.select(exactAddresses.getFirst());
        List<NavigationCandidate> matches = selection.candidates().stream()
                .filter(candidate -> matches(candidate, choice)).toList();
        if (matches.size() == 1) return Decision.select(matches.getFirst());
        if (NEW_NAVIGATION_REQUEST.matcher(compact).matches()) return Decision.freshSearch();
        if (matches.size() > 1) return Decision.ambiguous();
        return Decision.noMatch();
    }

    boolean isOrdinalAnswer(String transcript) {
        return transcript != null && ordinal(compact(transcript)) != null;
    }

    private static boolean matches(NavigationCandidate candidate, String choice) {
        String name = compact(candidate.poiname());
        String address = compact(candidate.address());
        return name.contains(choice) || choice.contains(name)
                || (!address.isBlank() && (address.contains(choice) || choice.contains(address)));
    }

    private static Integer ordinal(String text) {
        Matcher arabic = ARABIC_ORDINAL.matcher(text);
        if (arabic.find() && (text.matches("\\d+[个家]?") || text.contains("第") || text.startsWith("选"))) {
            return Integer.parseInt(arabic.group(1));
        }
        for (int i = 0; i < text.length(); i++) {
            Integer value = CHINESE_NUMBERS.get(text.charAt(i));
            if (value != null && (text.contains("第") || text.contains("选")
                    || text.endsWith("个") || text.endsWith("家"))) return value;
        }
        return null;
    }

    private static String compact(String text) {
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。！？、,.!?;；:：()（）]", "");
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            Integer digit = CHINESE_NUMBERS.get(normalized.charAt(i));
            if (digit == null) out.append(normalized.charAt(i));
            else out.append(digit);
        }
        return out.toString();
    }

    enum Type { NO_MATCH, SELECT, CANCEL, OUT_OF_RANGE, AMBIGUOUS, FRESH_SEARCH }

    record Decision(Type type, NavigationCandidate candidate, Integer ordinal) {
        static Decision noMatch() { return new Decision(Type.NO_MATCH, null, null); }
        static Decision select(NavigationCandidate candidate) { return new Decision(Type.SELECT, candidate, null); }
        static Decision cancel() { return new Decision(Type.CANCEL, null, null); }
        static Decision outOfRange(int ordinal) { return new Decision(Type.OUT_OF_RANGE, null, ordinal); }
        static Decision ambiguous() { return new Decision(Type.AMBIGUOUS, null, null); }
        static Decision freshSearch() { return new Decision(Type.FRESH_SEARCH, null, null); }
    }
}
