package com.autovoice.server.agentloop;

import com.autovoice.server.contracts.SessionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Bind navigation to the request's device fix, never to coordinates invented by the model. */
public final class NavigationRequestContext {
    private static final ObjectMapper JSON = new ObjectMapper();
    private NavigationRequestContext() { }

    public static boolean requiresResolution(String tool,
            java.util.List<com.autovoice.server.contracts.FunctionTool> enabled) {
        return "navigate".equals(tool) && enabled.stream()
                .anyMatch(t -> NavigationCandidateReplies.RESOLVE_TOOL.equals(t.name()));
    }

    public static String bind(String tool, String arguments, SessionContext context) {
        if (!NavigationCandidateReplies.RESOLVE_TOOL.equals(tool)) return arguments;
        try {
            if (!(JSON.readTree(arguments) instanceof ObjectNode args)) {
                throw new IllegalArgumentException("navigation arguments must be an object");
            }
            args.remove("location");
            Object lat = context == null ? null : context.attrs().get("latitude");
            Object lon = context == null ? null : context.attrs().get("longitude");
            if (lat instanceof Number a && lon instanceof Number b
                    && Double.isFinite(a.doubleValue()) && Double.isFinite(b.doubleValue())
                    && Math.abs(a.doubleValue()) <= 90 && Math.abs(b.doubleValue()) <= 180) {
                args.put("location", b.doubleValue() + "," + a.doubleValue());
            }
            return JSON.writeValueAsString(args);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("invalid navigation arguments", e);
        }
    }
}
