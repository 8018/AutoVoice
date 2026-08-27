package com.autovoice.server.agentloop;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SlotValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Converts a successful resolve_navigation result into an in-app selection action. */
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
     * Single-destination searches become a dialog action. Multi-stop searches keep using the
     * existing model loop until a later dialog design can select one candidate per stop.
     */
    public static Optional<Reply> from(List<AgentToolResult> results) {
        for (AgentToolResult result : results) {
            if (!RESOLVE_TOOL.equals(result.call().name()) || result.error()) continue;
            try {
                JsonNode destinations = JSON.readTree(result.content()).path("destinations");
                if (!destinations.isArray() || destinations.size() != 1) continue;
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
}
