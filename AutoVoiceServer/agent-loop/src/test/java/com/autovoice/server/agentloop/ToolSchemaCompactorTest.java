package com.autovoice.server.agentloop;

import com.autovoice.server.contracts.FunctionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSchemaCompactorTest {
    @Test
    void removesDecorationButKeepsValidationFields() {
        FunctionTool compact = ToolSchemaCompactor.compact(new FunctionTool("search", " many\n spaces ",
                "{\"$schema\":\"x\",\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\",\"default\":\"x\"}},\"required\":[\"q\"]}"));
        assertFalse(compact.parametersJson().contains("$schema"));
        assertFalse(compact.parametersJson().contains("default"));
        assertTrue(compact.parametersJson().contains("required"));
        assertTrue(compact.description().equals("many spaces"));
    }
}
