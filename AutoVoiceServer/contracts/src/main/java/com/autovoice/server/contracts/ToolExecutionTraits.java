package com.autovoice.server.contracts;

/**
 * 可信执行元数据,不由工具名称推断,也不由模型自报。
 *
 * <p>D04 扩展:{@code approvedOperation} 标记"已审核的非只读操作"(如选择器提交),
 * 在候选阶段允许执行;导航提交改由输出准入层承载后(D05)撤销此类批准。</p>
 */
public record ToolExecutionTraits(boolean readOnly, boolean parallelSafe, boolean cacheSuccess,
                                  boolean approvedOperation) {

    public static final ToolExecutionTraits UNKNOWN = new ToolExecutionTraits(false, false, false, false);
    public static final ToolExecutionTraits INDEPENDENT_QUERY = new ToolExecutionTraits(true, true, true, false);
    /** 已审核的非只读提交操作(D04 过渡批准;D05 落地后应移除)。 */
    public static final ToolExecutionTraits APPROVED_COMMIT = new ToolExecutionTraits(false, false, false, true);

    public ToolExecutionTraits(boolean readOnly, boolean parallelSafe, boolean cacheSuccess) {
        this(readOnly, parallelSafe, cacheSuccess, false);
    }

    public ToolExecutionTraits {
        if (!readOnly && (parallelSafe || cacheSuccess))
            throw new IllegalArgumentException("Only declared read-only tools may parallelize or cache");
    }
}
