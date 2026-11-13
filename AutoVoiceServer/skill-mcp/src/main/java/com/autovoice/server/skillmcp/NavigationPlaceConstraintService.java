package com.autovoice.server.skillmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class NavigationPlaceConstraintService {
    private static final Set<String> SUPPORTED_KEYS = Set.of("brand", "category", "alias", "feature", "openNow");
    static final String UNVERIFIED = "地图未能核验全部地点条件。请补充地点名称，或说明可以放宽哪些条件。";

    private NavigationPlaceConstraintService() {
    }

    record Constraints(String brand, String category, String alias, String feature, boolean openNow) {
        static final Constraints NONE = new Constraints("", "", "", "", false);

        static Constraints parse(JsonNode value) {
            if (value.isMissingNode()) return NONE;
            if (!value.isObject()) throw new IllegalArgumentException("地点条件必须是对象");
            var keys = value.fieldNames();
            while (keys.hasNext()) {
                if (!SUPPORTED_KEYS.contains(keys.next())) throw new IllegalArgumentException("无法核验指定地点条件");
            }
            JsonNode open = value.path("openNow");
            if (!open.isMissingNode() && !open.isBoolean()) {
                throw new IllegalArgumentException("当前营业条件必须是布尔值");
            }
            return new Constraints(field(value, "brand"), field(value, "category"),
                    field(value, "alias"), field(value, "feature"), open.asBoolean());
        }

        private static String field(JsonNode value, String key) {
            JsonNode field = value.path(key);
            if (field.isMissingNode()) return "";
            if (!field.isTextual() || field.asText().isBlank() || field.asText().length() > 80) {
                throw new IllegalArgumentException("地点条件必须是简短的文字");
            }
            return field.asText().strip();
        }

        boolean active() {
            return !brand.isBlank() || !category.isBlank() || !alias.isBlank() || !feature.isBlank() || openNow;
        }

        List<String> unsupported() {
            List<String> unknown = new ArrayList<>();
            if (openNow) unknown.add("当前营业");
            if (!feature.isBlank()) unknown.add(feature);
            return unknown;
        }

        boolean matchesBrand(String name) {
            return brand.isBlank() || normalize(name).contains(normalize(brand));
        }

        boolean matches(String name, String providerCategory, String providerAlias) {
            boolean categoryMatches = category.isBlank() || Arrays.stream(providerCategory.split(";"))
                    .map(NavigationPlaceConstraintService::normalize).anyMatch(normalize(category)::equals);
            return matchesBrand(name) && categoryMatches
                    && (alias.isBlank() || normalize(alias).equals(normalize(providerAlias)));
        }

        String keywords(String query) {
            if (!alias.isBlank() && !normalize(query).contains(normalize(alias))) query += " " + alias;
            if (brand.isBlank() || normalize(query).contains(normalize(brand))) return query;
            return brand + " " + query;
        }

        void addEvidence(ObjectNode candidate, String name, String providerCategory, String providerAlias, Instant retrievedAt) {
            var evidence = candidate.putArray("conditionEvidence");
            List<String> summary = new ArrayList<>();
            if (!brand.isBlank()) {
                evidence.addObject().put("condition", "brand").put("field", "name")
                        .put("source", "amap_poi").put("value", name).put("retrievedAt", retrievedAt.toString());
                summary.add("名称含“" + brand + "”");
            }
            if (!category.isBlank()) {
                evidence.addObject().put("condition", "category").put("field", "type")
                        .put("source", "amap_poi").put("value", providerCategory)
                        .put("retrievedAt", retrievedAt.toString());
                summary.add("地图分类为“" + category + "”");
            }
            if (!alias.isBlank()) {
                evidence.addObject().put("condition", "alias").put("field", "alias")
                        .put("source", "amap_poi").put("value", providerAlias).put("retrievedAt", retrievedAt.toString());
                summary.add("登记别名为“" + alias + "”");
            }
            if (!summary.isEmpty()) candidate.put("matchSummary", "高德" + String.join("，", summary));
        }
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
