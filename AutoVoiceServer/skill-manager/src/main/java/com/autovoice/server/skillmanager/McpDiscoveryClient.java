package com.autovoice.server.skillmanager;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 平台侧工具发现：连接一次 MCP server，list_tools 返回工具清单（不落库）。 */
public final class McpDiscoveryClient {

    private final long connectTimeoutMs;

    public McpDiscoveryClient(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public List<ToolInfo> discover(String mcpUrl, String authHeader, String authValue) throws IOException {
        // SDK 2.0.0 实测签名（同 skill-mcp 的 McpToolSession）：builder 只收 String（无 URI 重载）；
        // httpRequestCustomizer 是 5 参 lambda（多一个 McpTransportContext）
        HttpClientStreamableHttpTransport.Builder tb = HttpClientStreamableHttpTransport
                .builder(mcpUrl)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs));
        if (authHeader != null && !authHeader.isBlank()) {
            String header = authHeader;
            String value = authValue == null ? "" : authValue;
            tb.httpRequestCustomizer((HttpRequest.Builder b, String method, URI endpoint,
                                      String body, McpTransportContext ctx) -> b.header(header, value));
        }
        McpSyncClient c = null;
        try {
            // transport 构建 + initialize + list_tools 统一在 try 内：SDK 失败抛 RuntimeException，
            // 全部转 IOException（方法契约），并确保关闭客户端
            c = McpClient.sync(tb.build())
                    .requestTimeout(Duration.ofMillis(connectTimeoutMs))
                    .clientInfo(new McpSchema.Implementation("autovoice-skill-manager", "1.0"))
                    .build();
            c.initialize();
            ListToolsResult listed = c.listTools();
            List<ToolInfo> out = new ArrayList<>();
            for (Tool t : listed.tools()) {
                out.add(new ToolInfo(t.name(), t.description() == null ? "" : t.description()));
            }
            return out;
        } catch (RuntimeException e) {
            throw new IOException("mcp discover failed: " + e.getMessage(), e);
        } finally {
            if (c != null) {
                try {
                    c.closeGracefully();
                } catch (RuntimeException ignored) {
                    // 关闭失败不覆盖原始异常
                }
            }
        }
    }
}
