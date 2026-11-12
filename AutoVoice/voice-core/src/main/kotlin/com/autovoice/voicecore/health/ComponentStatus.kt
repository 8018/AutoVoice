package com.autovoice.voicecore.health

enum class ComponentScope { PROCESS, ASSEMBLY }
enum class Lifecycle { NOT_STARTED, INITIALIZING, READY, FAILED, BLOCKED, DISABLED, RELEASED }
enum class Health { UNKNOWN, HEALTHY, DEGRADED, UNAVAILABLE }
enum class CapabilityAvailability { AVAILABLE, LIMITED, UNAVAILABLE, PENDING, DISABLED }

enum class ErrorCode(val message: String, val action: String) {
    CONFIG_INVALID("配置无法加载", "请检查配置后重新启动"),
    PROVIDER_MISMATCH("配置与当前渠道不匹配", "请安装匹配渠道的配置"),
    MIC_PERMISSION_DENIED("尚未获得麦克风权限", "请允许麦克风访问"),
    CAPTURE_OPEN_FAILED("麦克风暂不可用", "请检查设备和权限后重试"),
    NATIVE_LIBRARY_MISSING("运行库尚未就绪", "请安装对应运行库"),
    NATIVE_ABI_MISMATCH("运行库与设备架构不匹配", "请安装兼容的运行库"),
    NATIVE_API_MISMATCH("运行库与系统版本不匹配", "请使用兼容的系统或运行库"),
    NATIVE_SYMBOL_MISMATCH("运行库接口不匹配", "请安装同版本运行库"),
    MODEL_MISSING("模型资源尚未就绪", "请安装对应模型资源"),
    MODEL_HASH_MISMATCH("模型校验失败", "请重新安装正确的模型资源"),
    TOKENS_MISMATCH("模型词表不匹配", "请安装同版本模型和词表"),
    MODEL_LOAD_FAILED("模型加载失败", "请检查模型资源后重试"),
    SDK_CREDENTIALS_MISSING("语音服务授权配置缺失", "请配置授权信息后重新启动"),
    SDK_AUTH_FAILED("语音服务授权失败", "请检查授权条件后重新启动"),
    INIT_TIMEOUT("语音服务准备超时", "请重新启动后重试"),
    RESOURCE_MISSING("组件资源缺失", "请安装所需资源"),
    IMPLEMENTATION_UNAVAILABLE("该组件尚未就绪", "请使用其他可用功能"),
    RENDER_REFERENCE_STALE("播放参考暂不可用", "普通话术打断已暂停"),
    RENDER_QUEUE_OVERFLOW("播放参考处理繁忙", "普通话术打断已暂停"),
    COMPONENT_RUNTIME_FAILED("组件暂不可用", "请重试或重新启动"),
    RESOURCE_EXHAUSTED("设备资源不足", "请释放资源后重试"),
}

data class ComponentError(val code: ErrorCode, val message: String = code.message,
    val vendorCode: Int? = null, val retryable: Boolean = false, val suggestion: String = code.action) {
    fun sanitized() = copy(message = code.message, suggestion = code.action)
}

data class ComponentDescriptor(val componentId: String, val provider: String, val channel: String,
    val scope: ComponentScope = ComponentScope.ASSEMBLY)
data class ComponentToken(val componentId: String, val generation: Long, val attemptId: String)
data class ComponentUpdate(val lifecycle: Lifecycle? = null, val health: Health? = null, val stage: String? = null,
    val error: ComponentError? = null, val blockedBy: Set<String> = emptySet(), val versions: Map<String, String> = emptyMap()) {
    companion object {
        fun ready() = ComponentUpdate(Lifecycle.READY, Health.HEALTHY)
        fun failed(error: ComponentError) = ComponentUpdate(Lifecycle.FAILED, Health.UNAVAILABLE, error = error)
        fun health(health: Health, error: ComponentError? = null) = ComponentUpdate(health = health, error = error)
        fun disabled() = ComponentUpdate(Lifecycle.DISABLED, Health.UNKNOWN)
        fun blocked(vararg ids: String) = ComponentUpdate(Lifecycle.BLOCKED, Health.UNAVAILABLE, blockedBy = ids.toSet())
        fun stage(stage: String) = ComponentUpdate(stage = stage)
    }
}

data class ComponentStatusEvent(val descriptor: ComponentDescriptor, val token: ComponentToken,
    val sequence: Long, val update: ComponentUpdate, val startedAt: Long?, val updatedAt: Long,
    val durationMs: Long?, val reporterId: String, val rootCauseId: String? = null)
data class ComponentStatus(val descriptor: ComponentDescriptor, val generation: Long, val attemptId: String = "",
    val sequence: Long = 0, val lifecycle: Lifecycle = Lifecycle.NOT_STARTED, val health: Health = Health.UNKNOWN,
    val stage: String = "not_started", val startedAt: Long? = null, val updatedAt: Long = 0,
    val durationMs: Long? = null, val error: ComponentError? = null, val blockedBy: Set<String> = emptySet(),
    val rootCauseId: String? = null, val versions: Map<String, String> = emptyMap()) {
    val componentId get() = descriptor.componentId
}
data class ComponentHealthSnapshot(val appSessionId: String = "", val generation: Long = 0, val channel: String = "",
    val components: Map<String, ComponentStatus> = emptyMap(), val recentEvents: List<ComponentStatusEvent> = emptyList(),
    val mergedHealthEvents: Long = 0, val droppedLogEvents: Long = 0)

fun Throwable.rethrowCancellationOrFatal() {
    if (this is java.util.concurrent.CancellationException || this is VirtualMachineError || this is ThreadDeath) throw this
}
fun initializationError(error: Throwable, fallback: ErrorCode = ErrorCode.COMPONENT_RUNTIME_FAILED): ComponentError {
    error.rethrowCancellationOrFatal()
    val code = when (error) {
        is java.util.concurrent.RejectedExecutionException -> ErrorCode.RESOURCE_EXHAUSTED
        is LinkageError -> when {
            error.message?.contains("symbol", ignoreCase = true) == true || error.message?.contains("No implementation found") == true -> ErrorCode.NATIVE_SYMBOL_MISMATCH
            error.message?.contains("ELF", ignoreCase = true) == true || error.message?.contains("ABI", ignoreCase = true) == true -> ErrorCode.NATIVE_ABI_MISMATCH
            else -> ErrorCode.NATIVE_LIBRARY_MISSING
        }
        else -> fallback
    }
    return ComponentError(code)
}
