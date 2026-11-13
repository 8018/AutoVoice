package com.autovoice.server.agentloop;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SlotValue;
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
     * Single-destination searches become a dialog action. Multi-stop searches keep every stop
     * in order so the device can resolve ambiguity and ask for confirmation before opening the map.
     */
    public static Optional<Reply> from(List<AgentToolResult> results) {
        for (AgentToolResult result : results) {
            if (!RESOLVE_TOOL.equals(result.call().name()) || result.error()) continue;
            Optional<Reply> reply = fromResolution(result.content());
            if (reply.isPresent()) return reply;
        }
        return Optional.empty();
    }

    /** Shared adapter for initial tool results and deterministic follow-up searches. */
    public static Optional<Reply> fromResolution(String content) {
        try {
            JsonNode resolution = JSON.readTree(content);
            JsonNode destinations = resolution.path("destinations");
            if (!destinations.isArray() || destinations.isEmpty()) {
                String message = resolution.path("message").asText("");
                return message.isBlank() ? Optional.empty() : Optional.of(Reply.ofText(message));
            }
            if (destinations.size() > 1) return multiStopReply(destinations);
            for (JsonNode destination : destinations) {
                if (destination.path("candidates").isArray() && destination.path("candidates").isEmpty()) {
                    String message = resolution.path("message").asText("");
                    if (message.isBlank()) {
                        message = "未找到“" + destination.path("query").asText("")
                                + "”的可靠地点，请说更具体的地点名称。";
                    }
                    return Optional.of(Reply.ofText(message));
                }
            }
            JsonNode destination = destinations.get(0);
            JsonNode candidates = destination.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) return Optional.empty();
            String query = destination.path("query").asText("");
            var displayed = JSON.createArrayNode();
            List<String> descriptions = new ArrayList<>();
            for (JsonNode candidate : candidates) {
                if (displayed.size() == 3) break;
                displayed.add(candidate);
                String name = candidate.path("poiname").asText();
                String address = candidate.path("address").asText();
                descriptions.add("第" + displayed.size() + "个，" + name
                        + (address.isBlank() ? "" : "，" + address));
            }
            Map<String, SlotValue> slots = new LinkedHashMap<>();
            slots.put(SLOT_QUERY, SlotValue.stringValue(query));
            slots.put(SLOT_CANDIDATES, SlotValue.stringValue(displayed.toString()));
            Intent intent = Intent.of("1.0", DOMAIN, CHOOSE_INTENT, slots, 1.0,
                    "navigation.resolve", content);
            String notice = resolution.path("availabilityNotice").asText("");
            String prompt = (notice.isBlank() ? "" : notice)
                    + String.join("；", descriptions) + "。请说第几个或具体地址名称。";
            return Optional.of(Reply.ofAction(intent, prompt));
        } catch (Exception ignored) {
            // Malformed third-party tool output remains in the normal model/tool loop.
            return Optional.empty();
        }
    }

    private static Optional<Reply> multiStopReply(JsonNode destinations) {
        if (destinations.size() > 5) {
            return Optional.of(Reply.ofText("一次最多安排5个地点，请减少地点后重试。"));
        }
        var stops = JSON.createArrayNode();
        for (JsonNode destination : destinations) {
            String query = destination.path("query").asText("");
            if (query.isBlank() || query.length() > 256) return Optional.empty();
            var stop = stops.addObject().put("query", query);
            String savedPlace = destination.path("savedPlace").asText("");
            if (savedPlace.equals("home") || savedPlace.equals("work")) {
                stop.put("savedPlace", savedPlace);
                continue;
            }
            JsonNode candidates = destination.path("candidates");
            if (!candidates.isArray() || candidates.size() > 3) return Optional.empty();
            var copied = stop.putArray("candidates");
            for (JsonNode candidate : candidates) {
                String name = candidate.path("poiname").asText("");
                JsonNode lat = candidate.path("lat");
                JsonNode lon = candidate.path("lon");
                if (name.isBlank() || !lat.isNumber() || !lon.isNumber()
                        || !Double.isFinite(lat.asDouble()) || !Double.isFinite(lon.asDouble())
                        || Math.abs(lat.asDouble()) > 90 || Math.abs(lon.asDouble()) > 180) {
                    return Optional.empty();
                }
                var copy = copied.addObject().put("poiname", name)
                        .put("lat", lat.asDouble()).put("lon", lon.asDouble());
                String address = candidate.path("address").asText("");
                if (!address.isBlank()) copy.put("address", address);
            }
        }
        Map<String, SlotValue> slots = new LinkedHashMap<>();
        slots.put("stops", SlotValue.stringValue(stops.toString()));
        Intent intent = Intent.of("1.0", DOMAIN, "draft_trip", slots, 1.0,
                "navigation.resolve.multi_stop", null);
        return Optional.of(Reply.ofAction(intent, "请查看行程草稿"));
    }
}
