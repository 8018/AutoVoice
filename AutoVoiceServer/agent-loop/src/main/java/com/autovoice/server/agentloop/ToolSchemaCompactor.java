package com.autovoice.server.agentloop;

import com.autovoice.server.contracts.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Removes schema decoration that does not affect function-call validation. */
public final class ToolSchemaCompactor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DROP = Set.of("title", "$schema", "examples", "example", "default");

    private ToolSchemaCompactor() {
    }

    public static FunctionTool compact(FunctionTool tool) {
        try {
            JsonNode schema = JSON.readTree(tool.parametersJson());
            prune(schema);
            String description = oneLine(tool.description());
            return new FunctionTool(tool.name(), description, JSON.writeValueAsString(schema));
        } catch (Exception ignored) {
            return new FunctionTool(tool.name(), oneLine(tool.description()), tool.parametersJson());
        }
    }

    public static List<FunctionTool> compact(List<FunctionTool> tools) {
        return tools.stream().map(ToolSchemaCompactor::compact).toList();
    }

    private static void prune(JsonNode node) {
        if (node instanceof ObjectNode object) {
            DROP.forEach(object::remove);
            Iterator<JsonNode> children = object.elements();
            while (children.hasNext()) prune(children.next());
        } else if (node.isArray()) {
            node.forEach(ToolSchemaCompactor::prune);
        }
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
