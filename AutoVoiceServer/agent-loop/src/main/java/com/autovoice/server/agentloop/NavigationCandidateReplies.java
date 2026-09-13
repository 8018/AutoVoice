package com.autovoice.server.agentloop;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SlotValue;
import com.autovoice.server.contracts.SpeakTexts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Converts a successful navigation resolution into the appropriate deterministic client action. */
public final class NavigationCandidateReplies {
    public static final String RESOLVE_TOOL = "resolve_navigation";
    public static final String DOMAIN = "navigation";
    public static final String CHOOSE_INTENT = "choose_destination";
    public static final String SLOT_QUERY = "query";
    public static final String SLOT_CANDIDATES = "candidates";
    private static final ObjectMapper JSON = new ObjectMapper();

    private NavigationCandidateReplies() {
    }

    /**
     * Single-destination searches become a dialog action. Multi-stop searches take the best
     * candidate of every stop and open one route-planning preview, avoiding an ambiguous second
     * model turn and preserving the spoken stop order.
     */
    public static Optional<Reply> from(List<AgentToolResult> results) {
        for (AgentToolResult result : results) {
            if (!RESOLVE_TOOL.equals(result.call().name()) || result.error()) continue;
            try {
                JsonNode destinations = JSON.readTree(result.content()).path("destinations");
                if (!destinations.isArray() || destinations.isEmpty()) continue;
                if (destinations.size() > 1) {
                    Optional<Reply> multiStop = multiStopReply(destinations, result.content());
                    if (multiStop.isPresent()) return multiStop;
                    continue;
                }
                JsonNode destination = destinations.get(0);
                JsonNode candidates = destination.path("candidates");
                if (!candidates.isArray() || candidates.isEmpty()) continue;
                String query = destination.path("query").asText("");
                Map<String, SlotValue> slots = new LinkedHashMap<>();
                slots.put(SLOT_QUERY, SlotValue.stringValue(query));
                slots.put(SLOT_CANDIDATES, SlotValue.stringValue(JSON.writeValueAsString(candidates)));
                Intent intent = Intent.of("1.0", DOMAIN, CHOOSE_INTENT, slots, 1.0,
                        "navigation.resolve", result.content());
                String prompt = "找到" + candidates.size() + "个“" + query
                        + "”，请说第几个或具体地址名称";
                return Optional.of(Reply.ofAction(intent, prompt));
            } catch (Exception ignored) {
                // Malformed third-party tool output remains in the normal model/tool loop.
            }
        }
        return Optional.empty();
    }

    private static Optional<Reply> multiStopReply(JsonNode destinations, String raw) throws Exception {
        List<JsonNode> selected = new ArrayList<>();
        for (JsonNode destination : destinations) {
            JsonNode candidates = destination.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) return Optional.empty();
            JsonNode candidate = candidates.get(0);
            if (candidate.path("poiname").asText("").isBlank()
                    || !candidate.path("lat").isNumber() || !candidate.path("lon").isNumber()) {
                return Optional.empty();
            }
            selected.add(candidate);
        }

        JsonNode terminal = selected.get(selected.size() - 1);
        com.fasterxml.jackson.databind.node.ArrayNode waypoints = JSON.createArrayNode();
        for (int i = 0; i < selected.size() - 1; i++) {
            JsonNode candidate = selected.get(i);
            waypoints.addObject()
                    .put("poiname", candidate.path("poiname").asText())
                    .put("lat", candidate.path("lat").asDouble())
                    .put("lon", candidate.path("lon").asDouble());
        }

        Map<String, SlotValue> slots = new LinkedHashMap<>();
        slots.put(SpeakTexts.SLOT_POINAME, SlotValue.stringValue(terminal.path("poiname").asText()));
        slots.put("lat", SlotValue.number(terminal.path("lat").asDouble()));
        slots.put("lon", SlotValue.number(terminal.path("lon").asDouble()));
        slots.put(SpeakTexts.SLOT_WAYPOINTS,
                SlotValue.stringValue(JSON.writeValueAsString(waypoints)));
        Intent intent = Intent.of("1.0", DOMAIN, "navigate", slots, 1.0,
                "navigation.resolve.multi_stop", raw);
        return Optional.of(Reply.ofAction(intent, SpeakTexts.speak(intent)));
    }
}
