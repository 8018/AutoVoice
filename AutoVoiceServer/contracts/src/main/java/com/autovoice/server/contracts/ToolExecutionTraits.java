package com.autovoice.server.contracts;

/** Trusted execution metadata, not inferred from tool names or supplied by the model. */
public record ToolExecutionTraits(boolean readOnly, boolean parallelSafe, boolean cacheSuccess) {
    public static final ToolExecutionTraits UNKNOWN = new ToolExecutionTraits(false, false, false);
    public static final ToolExecutionTraits INDEPENDENT_QUERY = new ToolExecutionTraits(true, true, true);

    public ToolExecutionTraits {
        if (!readOnly && (parallelSafe || cacheSuccess))
            throw new IllegalArgumentException("Only declared read-only tools may parallelize or cache");
    }
}
