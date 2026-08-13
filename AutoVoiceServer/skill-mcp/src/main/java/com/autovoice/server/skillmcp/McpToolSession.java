package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 单个 skill 的 MCP 会话：SDK streamable HTTP 客户端（认证头经 httpRequestCustomizer
 * 每请求注入）+ list_tools 自动发现 + toolsJson 勾选过滤。
 */
public final class McpToolSession implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillConfig config;
    private final McpSyncClient client;
    private final Map<String, FunctionTool> tools;

    McpToolSession(SkillConfig config, McpSyncClient client, Map<String, FunctionTool> tools) {
        this.config = config;
        this.client = client;
        this.tools = tools;
    }

    /** 连接 + initialize + list_tools + 勾选过滤；连接/初始化失败抛 IOException（registry 跳过该 skill）。 */
    public static McpToolSession connect(SkillConfig config, long connectTimeoutMs) throws IOException {
        McpSyncClient c = null;
        try {
            // SDK 2.0.0 的 HttpClientStreamableHttpTransport.builder 只有 String 重载（无 URI 版本）
            HttpClientStreamableHttpTransport.Builder tb = HttpClientStreamableHttpTransport
                    .builder(config.mcpUrl())
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs));
            if (!config.authHeader().isBlank()) {
                String header = config.authHeader();
                String value = config.authValue();
                // 认证头必须每请求注入（httpRequestCustomizer），不能用已弃用的 customizeRequest()
                // 2.0.0 的自定义器签名为 customize(builder, method, endpoint, body, context)，比计划多一个 context 参数
                tb.httpRequestCustomizer((HttpRequest.Builder b, String method, URI endpoint, String body,
                                          McpTransportContext ctx) -> b.header(header, value));
            }
            c = McpClient.sync(tb.build())
                    .requestTimeout(Duration.ofMillis(connectTimeoutMs))
                    .clientInfo(new McpSchema.Implementation("autovoice-gateway", "1.0"))
                    .build();
            c.initialize();
        } catch (RuntimeException e) {
            // 连接/初始化失败（连接拒绝、超时、非法 URL 等）：SDK 抛 RuntimeException，
            // 统一转 IOException 并关闭会话 —— registry 据此跳过该 skill，一个坏 skill 不拖垮全部注入
            if (c != null) {
                try {
                    c.closeGracefully();
                } catch (RuntimeException ignored) {
                    // 关闭失败不致命，不覆盖原始错误
                }
            }
            throw new IOException("mcp connect/initialize failed for " + config.id() + ": " + e.getMessage(), e);
        }
        ListToolsResult listed;
        try {
            listed = c.listTools();
        } catch (RuntimeException e) {
            c.closeGracefully();
            throw new IOException("mcp list_tools failed for " + config.id() + ": " + e.getMessage(), e);
        }
        Map<String, Boolean> chosen = parseToolsJson(config.toolsJson());
        Map<String, FunctionTool> tools = new LinkedHashMap<>();
        for (Tool t : listed.tools()) {
            if (chosen.getOrDefault(t.name(), true)) { // 勾选清单为空 = 全选
                // 2.0.0 的 Tool.inputSchema() 返回 Map<String,Object>（非 JsonNode/字符串），
                // writeValueAsString(Map) 同样产出合法 JSON 文本
                String schema = t.inputSchema() == null
                        ? "{\"type\":\"object\"}"
                        : MAPPER.writeValueAsString(t.inputSchema());
                tools.put(t.name(), new FunctionTool(t.name(),
                        t.description() == null ? "" : t.description(), schema));
            }
        }
        return new McpToolSession(config, c, tools);
    }

    /** toolsJson 解析为 {name: enabled}；空白/非法 JSON 视为空表（= 全选）。 */
    private static Map<String, Boolean> parseToolsJson(String toolsJson) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (toolsJson == null || toolsJson.isBlank()) {
            return out;
        }
        try {
            var arr = MAPPER.readTree(toolsJson);
            if (arr.isArray()) {
                for (var node : arr) {
                    out.put(node.path("name").asText(""), node.path("enabled").asBoolean(false));
                }
            }
        } catch (IOException e) {
            return out; // 非法勾选清单不致命：全选
        }
        return out;
    }

    public String skillId() {
        return config.id();
    }

    public Map<String, FunctionTool> tools() {
        return tools;
    }

    /** 调用工具，返回文本结果（content 中 TextContent 拼接）；isError=true 抛 McpToolException。 */
    public String callTool(String name, String argumentsJson) throws McpToolException {
        CallToolRequest req;
        try {
            // 2.0.0 的 CallToolRequest 构造器为 (String, Map<String,Object>) 或
            // (McpJsonMapper, String, String)，没有 JsonNode/Object 版本；
            // 按 brief 回退指示：readTree 校验 JSON 合法性后 convertValue 转 Map 传入
            req = new CallToolRequest(name,
                    MAPPER.convertValue(MAPPER.readTree(argumentsJson),
                            new TypeReference<Map<String, Object>>() {}));
        } catch (IOException e) {
            throw new McpToolException("tool call arguments invalid: " + argumentsJson);
        } catch (IllegalArgumentException e) {
            // 合法 JSON 但非对象（如 [1,2]）：convertValue 转 Map 失败，
            // 给出干净错误文本而非 Jackson 内部消息
            throw new McpToolException("tool call arguments must be a JSON object: " + argumentsJson);
        }
        CallToolResult res;
        try {
            res = client.callTool(req);
        } catch (RuntimeException e) {
            // SDK 层调用失败（超时、传输中断等）也统一转 McpToolException：message 作为 tool_result 回 LLM
            throw new McpToolException("tool " + name + " call failed: " + e.getMessage());
        }
        String text = res.content() == null ? "" : res.content().stream()
                .filter(TextContent.class::isInstance)
                .map(c -> ((TextContent) c).text())
                .collect(Collectors.joining("\n"));
        if (res.isError()) {
            throw new McpToolException("tool " + name + " failed: " + text);
        }
        return text;
    }

    @Override
    public void close() {
        try {
            client.closeGracefully();
        } catch (RuntimeException ignored) {
            // 关闭失败不致命
        }
    }
}
