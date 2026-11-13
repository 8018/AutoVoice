package com.autovoice.server.navigation;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stateless transcript matching; storage, expiry and contract adaptation live elsewhere. */
final class NavigationSelectionPolicy {
    private static final Pattern ARABIC_ORDINAL = Pattern.compile("^(?:(?:我)?(?:选择?|要|就|去|就去)|导航[到去])?(?:第)?(\\d+)(?:个|家|号)?(?:吧|就行)?$");
    private static final Pattern NEW_NAVIGATION_REQUEST = Pattern.compile(
            ".*(导航去|导航到|带我去|我要去|想去|前往|开车去|出发去).*");
    private static final Pattern REPLACEMENT = Pattern.compile(
            "^(?:不去了|不去.+?(?:了)?|不要去.+?)(?:改|还是)?(?:导航[到去]|去|前往).{2,}$");
    private static final Pattern CORRECTION = Pattern.compile(
            "^(?:不是|不在).+?(?:而?是|改到|改成)(.+)$");
    private static final Pattern AREA = Pattern.compile("^(?:在|搜|找|只看)?([\\p{IsHan}]{2,12}(?:市|新区|区|县))(?:的|范围内)?$");
    private static final Pattern NEGATION = Pattern.compile(
            "^(?:不要|不是|不选|别去|别选|不去|都不是|都不要|都不对).*");
    private static final Map<Character, Integer> CHINESE_NUMBERS = Map.of(
            '一', 1, '二', 2, '两', 2, '三', 3, '四', 4, '五', 5,
            '六', 6, '七', 7, '八', 8, '九', 9);

    Decision decide(PendingNavigationSelection selection, String transcript) {
        if (transcript == null || transcript.isBlank()) return Decision.noMatch();
        String compact = compact(transcript);
        if (REPLACEMENT.matcher(compact).matches()) return Decision.freshSearch();
        if (compact.matches(".*(?:换[一1]?批|再找一些|还有别的|换几个).*")
                || compact.matches("^(?:这些)?(?:都不是|都不要|都不对)$")) {
            return Decision.nextBatch();
        }
        Matcher correction = CORRECTION.matcher(compact);
        if (correction.matches()) return Decision.refine(correction.group(1));
        if (compact.matches("^(?:算了)?(?:取消(?:导航|搜索|查找)?|不去了|不找了|不搜了|算了|关闭(?:搜索|列表))$")) {
            return Decision.cancel();
        }
        if (NEGATION.matcher(compact).matches()) return Decision.rejected();

        Integer ordinal = ordinal(compact);
        if (ordinal != null) {
            if (ordinal < 1 || ordinal > selection.candidates().size()) {
                return Decision.outOfRange(ordinal);
            }
            return Decision.select(selection.candidates().get(ordinal - 1));
        }

        String choice = normalizeDigits(compact.replaceFirst("^(?:我选|选择|选|导航到|导航去|去)", "")
                .replaceFirst("(?:的?(?:这个|那个)|的)$", ""));
        if (choice.length() < 2) return Decision.noMatch();

        List<NavigationCandidate> exactNames = selection.candidates().stream()
                .filter(candidate -> candidateText(candidate.poiname()).equals(choice)).toList();
        if (exactNames.size() == 1) return Decision.select(exactNames.getFirst());
        List<NavigationCandidate> exactAddresses = selection.candidates().stream()
                .filter(candidate -> !candidate.address().isBlank()
                        && candidateText(candidate.address()).equals(choice)).toList();
        if (exactAddresses.size() == 1) return Decision.select(exactAddresses.getFirst());
        List<NavigationCandidate> matches = selection.candidates().stream()
                .filter(candidate -> matches(candidate, choice)).toList();
        if (matches.size() == 1) return Decision.select(matches.getFirst());
        if (NEW_NAVIGATION_REQUEST.matcher(compact).matches()
                || compact.matches("^(?:去|找|改去).{2,}")) return Decision.freshSearch();
        if (matches.size() > 1) return Decision.ambiguous();
        Matcher area = AREA.matcher(compact);
        if (area.matches()) return Decision.refine(area.group(1));
        if (compact.matches("^(?:我选|选|选择).+|.*(?:这个|那个)$")) return Decision.ambiguous();
        return Decision.noMatch();
    }

    boolean isOrdinalAnswer(String transcript) {
        return transcript != null && ordinal(compact(transcript)) != null;
    }

    private static boolean matches(NavigationCandidate candidate, String choice) {
        String name = candidateText(candidate.poiname());
        String address = candidateText(candidate.address());
        return name.contains(choice) || (!address.isBlank() && address.contains(choice));
    }

    private static Integer ordinal(String text) {
        Matcher arabic = ARABIC_ORDINAL.matcher(normalizeDigits(text));
        if (!arabic.matches()) return null;
        try {
            return Integer.parseInt(arabic.group(1));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static String compact(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。！？、,.!?;；:：()（）]", "");
    }

    private static String candidateText(String text) {
        return normalizeDigits(compact(text));
    }

    private static String normalizeDigits(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            Integer digit = CHINESE_NUMBERS.get(text.charAt(i));
            if (digit == null) out.append(text.charAt(i));
            else out.append(digit);
        }
        return out.toString();
    }

    enum Type { NO_MATCH, SELECT, CANCEL, OUT_OF_RANGE, AMBIGUOUS, FRESH_SEARCH, REJECTED, NEXT_BATCH, REFINE }

    record Decision(Type type, NavigationCandidate candidate, Integer ordinal, String qualifier) {
        static Decision noMatch() { return new Decision(Type.NO_MATCH, null, null, null); }
        static Decision select(NavigationCandidate candidate) { return new Decision(Type.SELECT, candidate, null, null); }
        static Decision cancel() { return new Decision(Type.CANCEL, null, null, null); }
        static Decision outOfRange(int ordinal) { return new Decision(Type.OUT_OF_RANGE, null, ordinal, null); }
        static Decision ambiguous() { return new Decision(Type.AMBIGUOUS, null, null, null); }
        static Decision freshSearch() { return new Decision(Type.FRESH_SEARCH, null, null, null); }
        static Decision rejected() { return new Decision(Type.REJECTED, null, null, null); }
        static Decision nextBatch() { return new Decision(Type.NEXT_BATCH, null, null, null); }
        static Decision refine(String area) { return new Decision(Type.REFINE, null, null, area); }
    }
}
