# Skill 管理平台 + MCP Host 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 独立 skill-manager 平台（Spring Boot + SQLite + React UI，端口 8083）管理第三方 MCP server 封装（skill），网关（app.jar）作为 MCP host 拉取启用 skill，把 MCP 工具与 car_control 一起注入 DeepSeek function calling，多轮执行（5s 时间预算），让语音指令能调用第三方能力（如高德 MCP）。

**Architecture:** 两个新模块 + 一个改造。`skill-manager`（独立 Spring Boot 应用，8083，系统内新增 systemd 服务）持有 SQLite skill 表与管理/拉取 API，写操作后 webhook 推送网关；`skill-mcp`（纯 Java 库模块，打进 app.jar）实现 SkillPlatformClient（HTTP 拉取）、McpToolSession（官方 MCP Java SDK streamable HTTP 客户端，list_tools 自动发现 + toolsJson 勾选过滤）、McpSkillRegistry（内存快照 + 启动拉取 + webhook 触发重拉 + 10min 兜底轮询 + 平台不可达保留旧快照）、McpToolExecutor（call_tool 路由）；`DeepSeekLlmProvider` 重构为注入式 tools + 多轮循环（≤3 次 LLM 调用，时间预算 5s，car_control 仍为终局 action）。仲裁零改动（LLM 候选 = 黑盒）。前端 skill-manager-web 镜像 telemetry-web 模式（Vite 5 + React 18 + TS strict，手工 `npm run build` 输出进 `static/skill-manager`）。

**Tech Stack:** Java 21 toolchain、Spring Boot 3.3.4、SQLite (xerial 3.45.3.0)、okhttp 4.12.0、Jackson 2.17.2、JUnit 5.10.2、mockwebserver 4.12.0、MCP 官方 Java SDK `io.modelcontextprotocol.sdk:mcp-core:2.0.0` + `mcp-json-jackson2:2.0.0`、Vite 5 + React 18 + TypeScript。

## Global Constraints

- 模块必须注册进 `AutoVoiceServer/settings.gradle.kts`；所有模块 Java toolchain 21；测试 `useJUnitPlatform()`；版本一律走 `gradle/libs.versions.toml`（sqlite 沿用 telemetry 模块硬编码 `org.xerial:sqlite-jdbc:3.45.3.0` 的写法，`mcp-core`/`mcp-json-jackson2` 用新加的 `mcp` version ref）。
- 依赖坐标：`io.modelcontextprotocol.sdk:mcp-core:2.0.0`、`io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.0`（**用 jackson2 flavor，不要用聚合包 `mcp`** —— 项目是 Jackson 2，聚合包带 Jackson 3 会引入第二套 `tools.jackson` 类型）。
- 端口：skill-manager 8083（env `SKILL_MANAGER_PORT`）；网关 8080 不动。
- 时间常量（写死常量，不进配置）：LLM 多轮时间预算 **5000ms**（`DEFAULT_TOOL_LOOP_BUDGET_MS`）、**最多 3 次 LLM 调用**（`MAX_LLM_ROUNDS`）、轮询 **600000ms**（config `SKILL_MANAGER_POLL_MS`）、MCP 连接/请求超时 **5000ms**。
- 分级注入：启用工具总数 **≤8 全量 direct 注入**；**>8 仅告警日志，首版仍 direct**（selector 只留接口扩展点，不实现）。
- 内部 token：header 名 `X-Skill-Service-Token`，env `SKILL_SERVICE_TOKEN`，常数时间比较（`MessageDigest.isEqual`）；管理口令 env `SKILL_MANAGER_ADMIN_TOKEN`，登录态 HttpOnly cookie `skill_admin`（值为 `SHA-256(ADMIN_TOKEN)` 的十六进制）。
- 脱敏：管理端 GET 返回的 `authValue` 一律 `"****"`（非空时）；PUT 时 `authValue` 留空 = 保留旧值；**网关拉取（`GET /api/skills?enabled=true`）返回明文 authValue**（同机内网 + service token 保护）。
- 凭据策略：任何真实 token/key **不得**写入 git（demo-full.json 真实 authToken 保持工作区未提交状态，计划任务不得 `git add` 它）；测试一律用假值。
- 错误降级：平台不可达 → 网关保留上次成功快照并 warn；单个 MCP server 连接失败 → 该 skill 工具不注入并 warn，其余 skill 不受影响；MCP 工具调用失败 → 错误文本作为 tool_result 回 LLM 续轮。
- 代码风格：与现有代码一致 —— 中文注释、类级 Javadoc、短连接 JDBC 模式（镜像 `SqliteTelemetryStore`）、`record` DTO、`@ConfigurationProperties` constructor binding、测试用 MockWebServer。
- 现有测试必须保持全绿：`DeepSeekLlmProvider` 保留 4 参构造器（默认 car_control tools + 默认 executor null），`LlmProvider` 接口不变，`RaceArbiter`/`SegmentPipeline`/`VoiceGatewayHandler` 不改。
- skill-manager 平台**不依赖** app/gateway 模块（独立 bootJar）；app 依赖 `:skill-mcp` 但**不得**依赖 `:skill-manager`。
- 应用入口扫描包：app 的 `scanBasePackages` 保持 `{"com.autovoice.server.app", "com.autovoice.server.telemetry"}`，skill-mcp 的类一律通过 AppConfig `@Bean` 装配（不扫包）；skill-manager 自己扫 `com.autovoice.server.skillmanager`。

---

### Task 1: contracts 工具契约（FunctionTool / ToolProvider / ToolExecutor）

**Files:**
- Create: `AutoVoiceServer/contracts/src/main/java/com/autovoice/server/contracts/FunctionTool.java`
- Create: `AutoVoiceServer/contracts/src/main/java/com/autovoice/server/contracts/ToolProvider.java`
- Create: `AutoVoiceServer/contracts/src/main/java/com/autovoice/server/contracts/ToolExecutor.java`
- Test: `AutoVoiceServer/contracts/src/test/java/com/autovoice/server/contracts/FunctionToolTest.java`

**Interfaces:**
- Produces（后续所有任务的依赖，签名固定）:
  ```java
  public record FunctionTool(String name, String description, String parametersJson) {}
  public interface ToolProvider { List<FunctionTool> enabledTools(); }
  public interface ToolExecutor { String execute(String toolName, String argumentsJson); }
  ```
  `FunctionTool.parametersJson` 是 OpenAI 兼容 `parameters` 对象的 JSON 文本（`{"type":"object","properties":...}`）。

- [ ] **Step 1: 写失败测试**

`FunctionToolTest.java`：
```java
package com.autovoice.server.contracts;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class FunctionToolTest {

    @Test
    void recordAccessors() {
        FunctionTool t = new FunctionTool("car_control", "执行车载控制指令",
                "{\"type\":\"object\"}");
        assertEquals("car_control", t.name());
        assertEquals("执行车载控制指令", t.description());
        assertEquals("{\"type\":\"object\"}", t.parametersJson());
    }

    @Test
    void providerAndExecutorCompile() {
        ToolProvider p = () -> List.of(new FunctionTool("a", "", "{}"));
        assertEquals(1, p.enabledTools().size());
        ToolExecutor e = (name, args) -> "ok:" + name;
        assertEquals("ok:a", e.execute("a", "{}"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :contracts:test --tests com.autovoice.server.contracts.FunctionToolTest`
Expected: FAIL（编译错：找不到 FunctionTool）。

- [ ] **Step 3: 写实现**

```java
package com.autovoice.server.contracts;

/**
 * OpenAI 兼容 function calling 工具定义（tool schema 的"可执行形式"）。
 * parametersJson 是 tools 数组中 parameters 对象的 JSON 文本。
 */
public record FunctionTool(String name, String description, String parametersJson) {
}
```

```java
package com.autovoice.server.contracts;

import java.util.List;

/** 提供当前应注入 LLM 的启用的工具列表（car_control + MCP 工具合并）。 */
public interface ToolProvider {
    List<FunctionTool> enabledTools();
}
```

```java
package com.autovoice.server.contracts;

/**
 * 执行一次工具调用，返回文本结果（将作为 tool_result 回 LLM 续轮）。
 * 抛 RuntimeException 表示工具失败（错误文本由调用方兜底回 LLM）。
 */
public interface ToolExecutor {
    String execute(String toolName, String argumentsJson);
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :contracts:test --tests com.autovoice.server.contracts.FunctionToolTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git -C /Users/michaelliu/code/AutoVoice add AutoVoiceServer/contracts/src/main/java/com/autovoice/server/contracts/FunctionTool.java AutoVoiceServer/contracts/src/main/java/com/autovoice/server/contracts/ToolProvider.java AutoVoiceServer/contracts/src/main/java/com/autovoice/server/contracts/ToolExecutor.java AutoVoiceServer/contracts/src/test/java/com/autovoice/server/contracts/FunctionToolTest.java
git -C /Users/michaelliu/code/AutoVoice commit -m "feat(contracts): FunctionTool/ToolProvider/ToolExecutor 工具契约"
```

---

### Task 2: skill-mcp 模块脚手架 + 分级注入策略

**Files:**
- Modify: `AutoVoiceServer/settings.gradle.kts`（include 加 `:skill-mcp`、`:skill-manager`——skill-manager 的构建文件在 Task 6 创建，这里只加 include 会导致 Task 6 前 `settings` 生效但模块无 build 文件；**因此本任务只加 `:skill-mcp`，Task 6 再加 `:skill-manager`**）
- Modify: `AutoVoiceServer/gradle/libs.versions.toml`
- Create: `AutoVoiceServer/skill-mcp/build.gradle.kts`
- Create: `AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/ToolInjector.java`
- Create: `AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/DirectToolInjector.java`
- Create: `AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/ToolInjectors.java`
- Test: `AutoVoiceServer/skill-mcp/src/test/java/com/autovoice/server/skillmcp/ToolInjectorTest.java`

**Interfaces:**
- Consumes: `FunctionTool`（Task 1）。
- Produces:
  ```java
  public interface ToolInjector { List<FunctionTool> inject(List<FunctionTool> all); }
  public final class DirectToolInjector implements ToolInjector { ... }
  public final class ToolInjectors {
      static final int DIRECT_LIMIT = 8;
      public static ToolInjector forCount(int toolCount) { ... }  // ≤8 direct；>8 direct + warn（selector 预留）
  }
  ```

- [ ] **Step 1: 注册模块与依赖**

`settings.gradle.kts` 的 include 列表加一行（保持现有 11 个模块后面）：
```kotlin
include(":skill-mcp")
```

`gradle/libs.versions.toml` 加（版本节）：
```toml
mcp = "2.0.0"
```
加（libraries 节）：
```toml
mcp-core = { module = "io.modelcontextprotocol.sdk:mcp-core", version.ref = "mcp" }
mcp-json-jackson2 = { module = "io.modelcontextprotocol.sdk:mcp-json-jackson2", version.ref = "mcp" }
```

- [ ] **Step 2: 写 build 文件**

`skill-mcp/build.gradle.kts`（镜像 gateway 模块结构：java-library + spring-dependency-management）：
```kotlin
plugins {
    java-library
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}") }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    api(project(":contracts"))
    implementation("com.squareup.okhttp3:okhttp")
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson2)
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(libs.mockwebserver)
    testImplementation("org.slf4j:slf4j-api")
}

tasks.test { useJUnitPlatform() }
```
（说明：okhttp/jackson/slf4j 由 spring-boot-dependencies BOM 管版本，镜像 gateway 模块现有写法；确认 `libs.versions.springBoot.get()` / `libs.versions.junit.get()` 的访问方式与现有模块一致——如现有写法不同则照抄现有模块的写法。）

- [ ] **Step 3: 写失败测试**

`ToolInjectorTest.java`：
```java
package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.autovoice.server.contracts.FunctionTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

class ToolInjectorTest {

    private static List<FunctionTool> tools(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> new FunctionTool("tool_" + i, "t" + i, "{}"))
                .collect(Collectors.toList());
    }

    @Test
    void eightOrFewerDirectAll() {
        List<FunctionTool> all = tools(8);
        List<FunctionTool> out = ToolInjectors.forCount(all.size()).inject(all);
        assertEquals(all, out);
    }

    @Test
    void moreThanEightStillDirectInV1() {
        List<FunctionTool> all = tools(9);
        List<FunctionTool> out = ToolInjectors.forCount(all.size()).inject(all);
        assertEquals(all, out); // v1：>8 只告警，仍全量注入（selector 为扩展点）
    }

    @Test
    void emptyIsDirect() {
        assertTrue(ToolInjectors.forCount(0).inject(List.of()).isEmpty());
    }
}
```

- [ ] **Step 4: 运行确认失败**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :skill-mcp:test --tests com.autovoice.server.skillmcp.ToolInjectorTest`
Expected: FAIL（编译错：找不到 ToolInjectors）。

- [ ] **Step 5: 写实现**

```java
package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;

import java.util.List;

/**
 * 工具注入策略：决定哪些 MCP 工具 schema 进 LLM 的 tools 数组。
 * 扩展点：首版只有 direct（≤8 全量）；>8 的 selector（mcp_tools_get/mcp_tools_execute
 * 两个 meta 工具）留待后续实现。
 */
public interface ToolInjector {
    List<FunctionTool> inject(List<FunctionTool> all);
}
```

```java
package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;

import java.util.List;

/** direct 策略：全部工具原样注入（不复制引用）。 */
public final class DirectToolInjector implements ToolInjector {
    @Override
    public List<FunctionTool> inject(List<FunctionTool> all) {
        return all;
    }
}
```

```java
package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;

import java.util.List;

/** 注入策略工厂：按启用工具总数选择策略（spec：≤8 direct，>8 selector 预留）。 */
public final class ToolInjectors {

    /** 全量 direct 注入的上限（超过应走 selector，首版未实现）。 */
    static final int DIRECT_LIMIT = 8;

    private ToolInjectors() {}

    /** 首版实现：无论数量都 direct；>DIRECT_LIMIT 时告警日志提示 selector 未实现。 */
    public static ToolInjector forCount(int toolCount) {
        if (toolCount > DIRECT_LIMIT) {
            org.slf4j.LoggerFactory.getLogger(ToolInjectors.class)
                    .warn("启用工具 {} 个超过 direct 上限 {}，selector 策略未实现，仍全量注入", toolCount, DIRECT_LIMIT);
        }
        return new DirectToolInjector();
    }
}
```

- [ ] **Step 6: 运行确认通过**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :skill-mcp:test`
Expected: PASS（3 个用例）。

- [ ] **Step 7: 提交**

```bash
git -C /Users/michaelliu/code/AutoVoice add AutoVoiceServer/settings.gradle.kts AutoVoiceServer/gradle/libs.versions.toml AutoVoiceServer/skill-mcp
git -C /Users/michaelliu/code/AutoVoice commit -m "feat(skill-mcp): 模块脚手架 + 分级注入策略 direct"
```

---

### Task 3: SkillPlatformClient（网关拉取平台配置）

**Files:**
- Create: `AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/SkillConfig.java`
- Create: `AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/SkillPlatformClient.java`
- Test: `AutoVoiceServer/skill-mcp/src/test/java/com/autovoice/server/skillmcp/SkillPlatformClientTest.java`

**Interfaces:**
- Consumes: 无（Task 1/2 不依赖）。okhttp + jackson。
- Produces:
  ```java
  public record SkillConfig(String id, String name, String description, String mcpUrl,
                            String authHeader, String authValue, String toolsJson,
                            boolean enabled, long updatedAt) {}
  public final class SkillPlatformClient {
      public SkillPlatformClient(OkHttpClient client, String baseUrl, String serviceToken);
      public List<SkillConfig> fetchEnabled() throws IOException;  // GET {baseUrl}/api/skills?enabled=true
      public boolean isEnabled();  // baseUrl 空白 → false，fetchEnabled 返回空表
  }
  ```

- [ ] **Step 1: 写失败测试**

`SkillPlatformClientTest.java`：
```java
package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

class SkillPlatformClientTest {

    private final MockWebServer server = new MockWebServer();

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    @Test
    void fetchEnabledParsesAndSendsToken() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("[{\"id\":\"amap-maps\",\"name\":\"高德地图\",\"description\":\"导航\","
                        + "\"mcpUrl\":\"https://mcp.example.com/mcp\",\"authHeader\":\"x-api-key\","
                        + "\"authValue\":\"secret-key\",\"toolsJson\":\"[{\\\"name\\\":\\\"poi_search\\\",\\\"enabled\\\":true}]\","
                        + "\"enabled\":true,\"updatedAt\":1723500000000}]"));
        SkillPlatformClient client = new SkillPlatformClient(new OkHttpClient(),
                server.url("/").toString(), "tok-123");
        List<SkillConfig> cfgs = client.fetchEnabled();
        assertEquals(1, cfgs.size());
        SkillConfig c = cfgs.get(0);
        assertEquals("amap-maps", c.id());
        assertEquals("secret-key", c.authValue()); // 网关拉取必须拿到明文凭据
        assertEquals("[{\"name\":\"poi_search\",\"enabled\":true}]", c.toolsJson());
        RecordedRequest r = server.takeRequest(3, TimeUnit.SECONDS);
        assertEquals("/api/skills?enabled=true", r.getPath());
        assertEquals("tok-123", r.getHeader("X-Skill-Service-Token"));
    }

    @Test
    void emptyUrlDisablesClient() throws Exception {
        SkillPlatformClient client = new SkillPlatformClient(new OkHttpClient(), "  ", "t");
        assertFalse(client.isEnabled());
        assertTrue(client.fetchEnabled().isEmpty());
    }

    @Test
    void non2xxThrows() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        SkillPlatformClient client = new SkillPlatformClient(new OkHttpClient(),
                server.url("/").toString(), "t");
        assertThrows(IOException.class, client::fetchEnabled);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :skill-mcp:test --tests com.autovoice.server.skillmcp.SkillPlatformClientTest`
Expected: FAIL（编译错）。

- [ ] **Step 3: 写实现**

```java
package com.autovoice.server.skillmcp;

/**
 * 平台返回的启用 skill 配置（GET /api/skills?enabled=true 的响应元素）。
 * toolsJson 为勾选清单 JSON 文本：[{"name":"...","enabled":true},...]。
 */
public record SkillConfig(String id, String name, String description, String mcpUrl,
                          String authHeader, String authValue, String toolsJson,
                          boolean enabled, long updatedAt) {
}
```

```java
package com.autovoice.server.skillmcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;

/**
 * skill 平台拉取客户端：GET {baseUrl}/api/skills?enabled=true，带 X-Skill-Service-Token。
 * baseUrl 空白 → 功能关闭（fetchEnabled 返回空表，isEnabled false）。
 */
public final class SkillPlatformClient {

    static final String SERVICE_TOKEN_HEADER = "X-Skill-Service-Token";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long CALL_TIMEOUT_MS = 5_000;

    private final OkHttpClient client;
    private final String baseUrl;
    private final String serviceToken;

    public SkillPlatformClient(OkHttpClient client, String baseUrl, String serviceToken) {
        this.client = client.newBuilder().callTimeout(CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS).build();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.serviceToken = serviceToken;
    }

    public boolean isEnabled() {
        return !baseUrl.isEmpty();
    }

    /** 拉取全部启用 skill；HTTP 非 2xx 或解析失败抛 IOException（调用方保留旧快照）。 */
    public List<SkillConfig> fetchEnabled() throws IOException {
        if (!isEnabled()) {
            return List.of();
        }
        Request req = new Request.Builder()
                .url(baseUrl + "/api/skills?enabled=true")
                .header(SERVICE_TOKEN_HEADER, serviceToken)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("skill platform pull failed: HTTP " + resp.code() + ": " + body);
            }
            return MAPPER.readValue(body, new TypeReference<List<SkillConfig>>() {});
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :skill-mcp:test`
Expected: PASS（3 个用例，含 Task 2 的）。

- [ ] **Step 5: 提交**

```bash
git -C /Users/michaelliu/code/AutoVoice add AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/SkillConfig.java AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/SkillPlatformClient.java AutoVoiceServer/skill-mcp/src/test/java/com/autovoice/server/skillmcp/SkillPlatformClientTest.java
git -C /Users/michaelliu/code/AutoVoice commit -m "feat(skill-mcp): SkillPlatformClient 拉取启用 skill 配置"
```

---

### Task 4: McpToolSession + McpToolExecutor（MCP SDK 客户端）

**Files:**
- Create: `AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/McpToolSession.java`
- Create: `AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/McpToolException.java`
- Create: `AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/McpToolExecutor.java`
- Test: `AutoVoiceServer/skill-mcp/src/test/java/com/autovoice/server/skillmcp/FakeMcpServer.java`
- Test: `AutoVoiceServer/skill-mcp/src/test/java/com/autovoice/server/skillmcp/McpToolSessionTest.java`
- Test: `AutoVoiceServer/skill-mcp/src/test/java/com/autovoice/server/skillmcp/McpToolExecutorTest.java`

**Interfaces:**
- Consumes: `SkillConfig`（Task 3）、`FunctionTool`/`ToolExecutor`（Task 1）。
- Produces:
  ```java
  public final class McpToolSession implements AutoCloseable {
      public static McpToolSession connect(SkillConfig config, long connectTimeoutMs) throws IOException;
      public String skillId();
      public Map<String, FunctionTool> tools();  // 已按 toolsJson 过滤的 {工具名: 定义}
      public String callTool(String name, String argumentsJson) throws McpToolException;
      public void close();
  }
  public final class McpToolExecutor implements com.autovoice.server.contracts.ToolExecutor {
      public McpToolExecutor(java.util.function.BiFunction<String, String, String> callFn); // 测试注入；生产 = registry 委托
  }
  ```
  `McpToolExecutor` 的**生产实现**在 Task 5 的 registry 里（`(name, args) -> sessionOf(name).callTool(name, args)`）；本任务的 executor 只是把这个 lambda 包装成 `ToolExecutor` 的薄适配器，测试用它验证包装语义（未知工具名 → 抛异常）。

- [ ] **Step 1: 写失败测试（FakeMcpServer 帮手 + 两个测试类）**

`FakeMcpServer.java`（测试帮手，模拟 MCP streamable HTTP server 的 JSON-RPC；放在 `src/test/java`，非 `@Test` 类）：
```java
package com.autovoice.server.skillmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最小 MCP streamable HTTP 假服务器：处理 initialize / notifications/initialized /
 * tools/list / tools/call 四个 JSON-RPC 方法；回显请求的 MCP-Session-Id。
 */
final class FakeMcpServer implements AutoCloseable {

    static final ObjectMapper MAPPER = new ObjectMapper();
    final MockWebServer server = new MockWebServer();
    final AtomicInteger callCount = new AtomicInteger();

    FakeMcpServer() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    JsonNode req = MAPPER.readTree(request.getBody().readUtf8());
                    String method = req.path("method").asText("");
                    ObjectNode result = MAPPER.createObjectNode();
                    MockResponse resp = new MockResponse().setHeader("Content-Type", "application/json");
                    if (request.getHeader("MCP-Session-Id") != null) {
                        resp = resp.setHeader("MCP-Session-Id", request.getHeader("MCP-Session-Id"));
                    }
                    if ("initialize".equals(method)) {
                        result.put("protocolVersion", request.getHeader("MCP-Protocol-Version") == null
                                ? "2025-11-25" : request.getHeader("MCP-Protocol-Version"));
                        result.putObject("capabilities");
                        ObjectNode info = result.putObject("serverInfo");
                        info.put("name", "fake-mcp");
                        info.put("version", "1.0");
                        resp = resp.setHeader("MCP-Session-Id", "sess-1");
                        return resp.setBody(rpc(1, result).toString());
                    }
                    if ("notifications/initialized".equals(method)) {
                        return new MockResponse().setResponseCode(202).setBody("");
                    }
                    if ("tools/list".equals(method)) {
                        ArrayNode tools = result.putArray("tools");
                        tools.add(tool("poi_search", "搜索兴趣点", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}"));
                        tools.add(tool("route_plan", "规划驾车路线", "{\"type\":\"object\"}"));
                        return resp.setBody(rpc(2, result).toString());
                    }
                    if ("tools/call".equals(method)) {
                        callCount.incrementAndGet();
                        ArrayNode content = result.putArray("content");
                        content.addObject().put("type", "text").put("text", "找到 1 个结果：西湖");
                        result.put("isError", false);
                        return resp.setBody(rpc(3, result).toString());
                    }
                    result.put("error", "unknown method: " + method);
                    return resp.setBody(rpc(-1, result).toString());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private static ObjectNode tool(String name, String desc, String schema) throws IOException {
        ObjectNode t = MAPPER.createObjectNode();
        t.put("name", name);
        t.put("description", desc);
        t.set("inputSchema", MAPPER.readTree(schema));
        return t;
    }

    private static ObjectNode rpc(int id, ObjectNode result) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.set("result", result);
        return out;
    }

    String url() {
        return server.url("/mcp").toString();
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
    }
}
```

`McpToolSessionTest.java`：
```java
package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.autovoice.server.contracts.FunctionTool;
import org.junit.jupiter.api.Test;

import java.util.Map;

class McpToolSessionTest {

    @Test
    void connectsListsFiltersAndCalls() throws Exception {
        try (FakeMcpServer fake = new FakeMcpServer()) {
            SkillConfig cfg = new SkillConfig("amap-maps", "高德地图", "导航",
                    fake.url(), "", "",
                    "[{\"name\":\"poi_search\",\"enabled\":true},{\"name\":\"route_plan\",\"enabled\":false}]",
                    true, 1L);
            try (McpToolSession s = McpToolSession.connect(cfg, 5000)) {
                assertEquals("amap-maps", s.skillId());
                Map<String, FunctionTool> tools = s.tools();
                assertTrue(tools.containsKey("poi_search"));
                assertFalse(tools.containsKey("route_plan")); // 勾选过滤
                assertEquals("找到 1 个结果：西湖", s.callTool("poi_search", "{\"query\":\"西湖\"}"));
                assertEquals(1, fake.callCount.get());
            }
        }
    }

    @Test
    void emptyToolsJsonMeansAllEnabled() throws Exception {
        try (FakeMcpServer fake = new FakeMcpServer()) {
            SkillConfig cfg = new SkillConfig("a", "b", "c", fake.url(), "", "",
                    "", true, 1L);
            try (McpToolSession s = McpToolSession.connect(cfg, 5000)) {
                assertEquals(2, s.tools().size()); // 空勾选清单 = 全选
            }
        }
    }
}
```

`McpToolExecutorTest.java`：
```java
package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class McpToolExecutorTest {

    @Test
    void delegatesAndPropagatesUnknownTool() {
        McpToolExecutor exec = new McpToolExecutor((name, args) -> {
            if ("poi_search".equals(name)) {
                return "结果文本";
            }
            throw new IllegalArgumentException("unknown tool: " + name);
        });
        assertEquals("结果文本", exec.execute("poi_search", "{}"));
        assertThrows(RuntimeException.class, () -> exec.execute("nope", "{}"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :skill-mcp:test`
Expected: FAIL（编译错：找不到 McpToolSession / McpToolExecutor / McpToolException）。

- [ ] **Step 3: 写实现**

`McpToolException.java`：
```java
package com.autovoice.server.skillmcp;

/** MCP 工具调用失败（isError=true 或 SDK 异常）。调用方把 message 作为 tool_result 回 LLM。 */
public final class McpToolException extends RuntimeException {
    public McpToolException(String message) {
        super(message);
    }
}
```

`McpToolSession.java`：
```java
package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
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
import java.util.List;
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

    private McpToolSession(SkillConfig config, McpSyncClient client, Map<String, FunctionTool> tools) {
        this.config = config;
        this.client = client;
        this.tools = tools;
    }

    /** 连接 + initialize + list_tools + 勾选过滤；连接失败抛 IOException（registry 跳过该 skill）。 */
    public static McpToolSession connect(SkillConfig config, long connectTimeoutMs) throws IOException {
        HttpClientStreamableHttpTransport.Builder tb = HttpClientStreamableHttpTransport
                .builder(URI.create(config.mcpUrl()))
                .connectTimeout(Duration.ofMillis(connectTimeoutMs));
        if (!config.authHeader().isBlank()) {
            String header = config.authHeader();
            String value = config.authValue();
            // 认证头必须每请求注入（httpRequestCustomizer），不能用已弃用的 customizeRequest()
            tb.httpRequestCustomizer((HttpRequest.Builder b, String method, URI endpoint, String body) ->
                    b.header(header, value));
        }
        McpSyncClient c = McpClient.sync(tb.build())
                .requestTimeout(Duration.ofMillis(connectTimeoutMs))
                .clientInfo(new McpSchema.Implementation("autovoice-gateway", "1.0"))
                .build();
        c.initialize();
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
            Object args = MAPPER.readTree(argumentsJson);
            // 注意：SDK 2.0.0 的 CallToolRequest 构造器签名按 flavor 略有差异
            // （Map<String,Object> 或 JsonNode 版本），以 mcp-json-jackson2 编译为准，
            // 若编译失败改用 MAPPER.convertValue(args, Map.class) 传 Map。
            req = new CallToolRequest(name, args);
        } catch (IOException e) {
            throw new McpToolException("tool call arguments invalid: " + argumentsJson);
        }
        CallToolResult res = client.callTool(req);
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
```
（**实现说明**：若 `mcp-json-jackson2` 的 `McpSchema.Tool.inputSchema()` 返回的是 JSON 字符串而非 JsonNode（不同版本差异），则跳过 `MAPPER.writeValueAsString`，直接 `t.inputSchema().toString()`；以实际编译结果为准，保持 `FunctionTool.parametersJson` 是合法 JSON 文本即可。`CallToolRequest` 构造器签名同样以编译为准，参数只要是合法 JSON 的表示即可。）

`McpToolExecutor.java`：
```java
package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.ToolExecutor;

import java.util.function.BiFunction;

/**
 * ToolExecutor 薄适配器：把"按工具名路由执行"的 lambda 包装为契约接口。
 * 生产装配时 lambda = registry 的按名路由（Task 5）；测试注入假 lambda。
 */
public final class McpToolExecutor implements ToolExecutor {

    private final BiFunction<String, String, String> callFn;

    public McpToolExecutor(BiFunction<String, String, String> callFn) {
        this.callFn = callFn;
    }

    @Override
    public String execute(String toolName, String argumentsJson) {
        return callFn.apply(toolName, argumentsJson);
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :skill-mcp:test`
Expected: PASS（Task 2/3/4 全部用例）。若 SDK API 签名有出入（inputSchema 类型、CallToolRequest 构造器），按 Step 3 备注调整后重跑。

- [ ] **Step 5: 提交**

```bash
git -C /Users/michaelliu/code/AutoVoice add AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/McpToolSession.java AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/McpToolException.java AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/McpToolExecutor.java AutoVoiceServer/skill-mcp/src/test/java/com/autovoice/server/skillmcp/FakeMcpServer.java AutoVoiceServer/skill-mcp/src/test/java/com/autovoice/server/skillmcp/McpToolSessionTest.java AutoVoiceServer/skill-mcp/src/test/java/com/autovoice/server/skillmcp/McpToolExecutorTest.java
git -C /Users/michaelliu/code/AutoVoice commit -m "feat(skill-mcp): MCP SDK 会话（发现/过滤/执行）+ 执行器适配"
```

---

### Task 5: McpSkillRegistry（内存快照 + 刷新 + 降级）

**Files:**
- Create: `AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/McpSkillRegistry.java`
- Test: `AutoVoiceServer/skill-mcp/src/test/java/com/autovoice/server/skillmcp/McpSkillRegistryTest.java`

**Interfaces:**
- Consumes: `SkillPlatformClient`（Task 3）、`McpToolSession`（Task 4）、`ToolInjector`（Task 2）、`FunctionTool`（Task 1）。
- Produces:
  ```java
  public final class McpSkillRegistry implements AutoCloseable {
      public McpSkillRegistry(SkillPlatformClient client, ToolInjector injector,
                              long pollMs, long connectTimeoutMs,
                              BiFunction<SkillConfig, Long, McpToolSession> sessionFactory);
      public void start();                                   // 启动异步首拉 + 定时轮询
      public void refreshAsync();                            // webhook 触发：立即异步重拉
      public void refresh();                                 // 同步重拉（测试用）
      public List<FunctionTool> enabledToolSpecs();          // 注入 LLM 的工具（经 injector）
      public String callTool(String toolName, String argumentsJson);  // 按名路由到所属 session
      public void close();
  }
  ```
  行为：`refresh()` 拉取 → 逐个 connect（失败跳过该 skill 并 warn）→ 整体替换快照 → 关闭旧 session；拉取本身失败（平台不可达）→ **保留旧快照**并 warn；全部成功但 sessions 为空 → 快照替换为空表。`callTool` 找不到工具 → 抛 `McpToolException`。

- [ ] **Step 1: 写失败测试**

`McpSkillRegistryTest.java`（用假 client + 假 sessionFactory，不碰真实 MCP）：
```java
package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.autovoice.server.contracts.FunctionTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class McpSkillRegistryTest {

    private static SkillConfig cfg(String id) {
        return new SkillConfig(id, id, "d", "https://mcp.example.com/mcp", "", "",
                "[{\"name\":\"t1\",\"enabled\":true}]", true, 1L);
    }

    private static McpToolSession session(SkillConfig cfg) {
        return new McpToolSession(cfg, null, Map.of("t1",
                new FunctionTool("t1", "d", "{\"type\":\"object\"}")));
    }

    @Test
    void refreshBuildsSnapshotFromEnabledSkills() throws Exception {
        FakePlatformClient client = new FakePlatformClient(List.of(cfg("a"), cfg("b")));
        try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
                60_000, 5_000, (c, timeout) -> session(c))) {
            reg.refresh();
            assertEquals(2, reg.enabledToolSpecs().size());
            assertEquals("a", reg.callTool("t1", "{}")); // t1 可路由（不炸）
        }
    }

    @Test
    void platformDownKeepsOldSnapshot() throws Exception {
        AtomicInteger pulls = new AtomicInteger();
        FakePlatformClient client = new FakePlatformClient(null) {
            @Override
            public List<SkillConfig> fetchEnabled() throws java.io.IOException {
                if (pulls.incrementAndGet() == 1) {
                    return List.of(cfg("a"));
                }
                throw new java.io.IOException("platform down");
            }
        };
        try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
                60_000, 5_000, (c, timeout) -> session(c))) {
            reg.refresh();
            assertEquals(1, reg.enabledToolSpecs().size());
            reg.refresh(); // 平台挂了
            assertEquals(1, reg.enabledToolSpecs().size()); // 旧快照仍在
        }
    }

    @Test
    void failedSessionSkipsOnlyThatSkill() throws Exception {
        SkillConfig bad = cfg("bad");
        FakePlatformClient client = new FakePlatformClient(List.of(cfg("good"), bad));
        try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
                60_000, 5_000, (c, timeout) -> {
                    if ("bad".equals(c.id())) {
                        throw new IllegalStateException("mcp down");
                    }
                    return session(c);
                })) {
            reg.refresh();
            assertEquals(1, reg.enabledToolSpecs().size());
        }
    }

    @Test
    void callToolUnknownNameThrows() throws Exception {
        FakePlatformClient client = new FakePlatformClient(List.of(cfg("a")));
        try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
                60_000, 5_000, (c, timeout) -> session(c))) {
            reg.refresh();
            assertThrows(McpToolException.class, () -> reg.callTool("ghost", "{}"));
        }
    }

    private static class FakePlatformClient extends SkillPlatformClient {
        private final List<SkillConfig> configs;

        FakePlatformClient(List<SkillConfig> configs) {
            super(new okhttp3.OkHttpClient(), "http://127.0.0.1:1", "t");
            this.configs = configs;
        }

        @Override
        public List<SkillConfig> fetchEnabled() throws java.io.IOException {
            return configs;
        }
    }
}
```
（说明：`McpToolSession` 构造器是 private 且字段 final——测试的 `session(cfg)` 帮手无法 new。**实现时必须把 `McpToolSession` 的构造器放宽为 package-private**，或提供 `static McpToolSession of(SkillConfig, Map<String,FunctionTool>)` 测试工厂。本任务 Step 3 采用：把构造器从 `private` 改为 `McpToolSession(SkillConfig, McpSyncClient, Map)` package-private（Task 4 的类同步改一处），并在测试里直接用。）

- [ ] **Step 2: 运行确认失败**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :skill-mcp:test --tests com.autovoice.server.skillmcp.McpSkillRegistryTest`
Expected: FAIL（编译错：找不到 McpSkillRegistry；McpToolSession 构造器不可见）。

- [ ] **Step 3: 写实现**

先改 Task 4 的 `McpToolSession`：构造器 `private McpToolSession(...)` → **package-private** `McpToolSession(...)`（去掉 private 关键字），其余不动。

`McpSkillRegistry.java`：
```java
package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * 启用的 skill 内存快照：启动异步首拉 + 定时兜底轮询 + webhook 触发重拉。
 * 平台不可达 → 保留上次成功快照；单 skill MCP 连接失败 → 跳过该 skill。
 * 会话（SegmentPipeline）零感知：每次直接用当前快照。
 */
public final class McpSkillRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(McpSkillRegistry.class);

    private final SkillPlatformClient client;
    private final ToolInjector injector;
    private final long pollMs;
    private final long connectTimeoutMs;
    private final BiFunction<SkillConfig, Long, McpToolSession> sessionFactory;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "skill-registry");
        t.setDaemon(true);
        return t;
    });

    private volatile Map<String, McpToolSession> sessions = Map.of();
    private volatile long lastRefreshMs;

    public McpSkillRegistry(SkillPlatformClient client, ToolInjector injector,
                            long pollMs, long connectTimeoutMs,
                            BiFunction<SkillConfig, Long, McpToolSession> sessionFactory) {
        this.client = client;
        this.injector = injector;
        this.pollMs = pollMs < 1 ? 600_000 : pollMs;
        this.connectTimeoutMs = connectTimeoutMs < 1 ? 5_000 : connectTimeoutMs;
        this.sessionFactory = sessionFactory;
    }

    /** 启动：异步首拉一次 + 按 pollMs 定时轮询（daemon 线程，不阻塞启动）。 */
    public void start() {
        scheduler.execute(this::refresh);
        scheduler.scheduleWithFixedDelay(this::refresh, pollMs, pollMs, TimeUnit.MILLISECONDS);
    }

    /** webhook 通知后立即异步重拉。 */
    public void refreshAsync() {
        scheduler.execute(this::refresh);
    }

    /** 同步重拉（start 的调度与测试都走它）。 */
    public synchronized void refresh() {
        List<SkillConfig> configs;
        try {
            configs = client.fetchEnabled();
        } catch (IOException e) {
            LOG.warn("skill platform pull failed, keep {} sessions: {}", sessions.size(), e.getMessage());
            return; // 平台不可达：保留旧快照
        }
        Map<String, McpToolSession> next = new LinkedHashMap<>();
        for (SkillConfig cfg : configs) {
            try {
                McpToolSession s = sessionFactory.apply(cfg, connectTimeoutMs);
                next.put(cfg.id(), s);
            } catch (RuntimeException e) {
                LOG.warn("skill {} mcp connect failed, skip: {}", cfg.id(), e.getMessage());
            }
        }
        Map<String, McpToolSession> old = sessions;
        sessions = next;
        lastRefreshMs = System.currentTimeMillis();
        for (McpToolSession s : old.values()) {
            if (!next.containsValue(s)) {
                s.close();
            }
        }
        LOG.info("skill registry refreshed: {} sessions ({} tools)",
                next.size(), next.values().stream().mapToInt(s -> s.tools().size()).sum());
    }

    /** 注入 LLM 的工具列表（经注入策略，含分级）。 */
    public List<FunctionTool> enabledToolSpecs() {
        List<FunctionTool> all = new ArrayList<>();
        for (McpToolSession s : sessions.values()) {
            all.addAll(s.tools().values());
        }
        return injector.inject(all);
    }

    /** 按工具名路由到所属 session 执行；未知工具抛 McpToolException。 */
    public String callTool(String toolName, String argumentsJson) {
        for (McpToolSession s : sessions.values()) {
            if (s.tools().containsKey(toolName)) {
                return s.callTool(toolName, argumentsJson);
            }
        }
        throw new McpToolException("no skill owns tool: " + toolName);
    }

    public long lastRefreshMs() {
        return lastRefreshMs;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        for (McpToolSession s : sessions.values()) {
            s.close();
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :skill-mcp:test`
Expected: PASS（全部用例）。

- [ ] **Step 5: 提交**

```bash
git -C /Users/michaelliu/code/AutoVoice add AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/McpToolSession.java AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/McpSkillRegistry.java AutoVoiceServer/skill-mcp/src/test/java/com/autovoice/server/skillmcp/McpSkillRegistryTest.java
git -C /Users/michaelliu/code/AutoVoice commit -m "feat(skill-mcp): McpSkillRegistry 快照/重拉/降级/路由执行"
```

---

### Task 6: skill-manager 平台脚手架 + SQLite 存储

**Files:**
- Modify: `AutoVoiceServer/settings.gradle.kts`（include 加 `:skill-manager`）
- Create: `AutoVoiceServer/skill-manager/build.gradle.kts`
- Create: `AutoVoiceServer/skill-manager/src/main/resources/application.yml`
- Create: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/SkillManagerApplication.java`
- Create: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/SkillProperties.java`
- Create: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/SqliteSkillStore.java`
- Test: `AutoVoiceServer/skill-manager/src/test/java/com/autovoice/server/skillmanager/SqliteSkillStoreTest.java`

**Interfaces:**
- Consumes: 无项目依赖（独立应用）。
- Produces（Task 7 依赖，签名固定）:
  ```java
  @ConfigurationProperties(prefix = "autovoice.skill-manager")
  public record SkillProperties(String dbPath, String adminToken, String serviceToken,
                                String gatewayWebhookUrl, long mcpConnectTimeoutMs) { ...紧凑构造器默认值... }
  public final class SqliteSkillStore {
      public SqliteSkillStore(String dbPath);
      public void init();
      public List<SkillRecord> findAll(boolean enabledOnly);
      public SkillRecord findById(String id);
      public void upsert(SkillRecord r);
      public void delete(String id);
  }
  public record SkillRecord(String id, String name, String description, String mcpUrl,
                            String authHeader, String authValue, String toolsJson,
                            boolean enabled, long updatedAt) {}
  ```

- [ ] **Step 1: 注册模块 + 写 build/配置**

`settings.gradle.kts` include 加 `:skill-manager`。

`skill-manager/build.gradle.kts`（镜像 tts-server：java + spring-boot + dep-mgmt）：
```kotlin
plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.squareup.okhttp3:okhttp")
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson2)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.mockwebserver)
}

tasks.test { useJUnitPlatform() }

tasks.bootJar { mainClass.set("com.autovoice.server.skillmanager.SkillManagerApplication") }
```

`skill-manager/src/main/resources/application.yml`：
```yaml
server:
  port: ${SKILL_MANAGER_PORT:8083}
spring:
  main:
    banner-mode: off
autovoice:
  skill-manager:
    db-path: ${SKILL_MANAGER_DB:${java.io.tmpdir}/autovoice-skill-manager/skill-manager.db}
    admin-token: ${SKILL_MANAGER_ADMIN_TOKEN:}
    service-token: ${SKILL_SERVICE_TOKEN:}
    gateway-webhook-url: ${SKILL_MANAGER_GATEWAY_WEBHOOK_URL:}
    mcp-connect-timeout-ms: ${SKILL_MANAGER_MCP_TIMEOUT_MS:5000}
```

`SkillManagerApplication.java`：
```java
package com.autovoice.server.skillmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** skill 管理平台：管理第三方 MCP server 封装（skill）的独立应用。 */
@SpringBootApplication(scanBasePackages = "com.autovoice.server.skillmanager")
@ConfigurationPropertiesScan
public class SkillManagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SkillManagerApplication.class, args);
    }
}
```

- [ ] **Step 2: 写失败测试**

`SqliteSkillStoreTest.java`（临时目录落盘，镜像 telemetry 测试风格）：
```java
package com.autovoice.server.skillmanager;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class SqliteSkillStoreTest {

    private String tmpDb() throws Exception {
        return Files.createTempDirectory("skill-store-test").resolve("skills.db").toString();
    }

    @Test
    void crudAndEnabledFilter() throws Exception {
        SqliteSkillStore store = new SqliteSkillStore(tmpDb());
        store.init();
        SkillRecord a = new SkillRecord("amap-maps", "高德地图", "导航", "https://mcp.example.com/mcp",
                "x-api-key", "secret-1", "[]", true, 100L);
        SkillRecord b = new SkillRecord("weather", "天气", "查天气", "https://mcp2.example.com/mcp",
                "", "", "[]", false, 200L);
        store.upsert(a);
        store.upsert(b);

        assertEquals(2, store.findAll(false).size());
        List<SkillRecord> enabled = store.findAll(true);
        assertEquals(1, enabled.size());
        assertEquals("amap-maps", enabled.get(0).id());
        assertEquals("secret-1", store.findById("amap-maps").authValue()); // 库内存明文

        // upsert 覆盖：a 改为 disabled 后 enabled 列表应为空；findAll(false) 仍 2 条
        store.upsert(new SkillRecord("amap-maps", "高德地图2", "导航2", "https://mcp.example.com/mcp",
                "x-api-key", "secret-2", "[]", false, 300L));
        assertTrue(store.findAll(true).isEmpty());
        assertEquals(2, store.findAll(false).size());
        assertEquals("secret-2", store.findById("amap-maps").authValue()); // 覆盖生效
    }
}
```
（注意上面最后一个断言的逻辑：upsert 把 a 改成 disabled 后 `findAll(true)` 应为 **0**。写测试时以"a 改 disabled 后 enabled 列表为空"为准修正断言；`findById` 不做 enabled 过滤，返回最新记录。）

- [ ] **Step 3: 运行确认失败**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :skill-manager:test`
Expected: FAIL（编译错）。

- [ ] **Step 4: 写实现**

`SkillProperties.java`（镜像 TelemetryProperties 的紧凑构造器默认值模式）：
```java
package com.autovoice.server.skillmanager;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * skill 平台配置。adminToken：管理界面口令；serviceToken：网关拉取/内部端点共用
 * （与网关 env SKILL_SERVICE_TOKEN 同值）；gatewayWebhookUrl：写操作后推送网关刷新。
 */
@ConfigurationProperties(prefix = "autovoice.skill-manager")
public record SkillProperties(String dbPath, String adminToken, String serviceToken,
                              String gatewayWebhookUrl, long mcpConnectTimeoutMs) {

    public SkillProperties {
        if (dbPath == null || dbPath.isBlank()) dbPath = "./skill-manager.db";
        if (adminToken == null) adminToken = "";
        if (serviceToken == null) serviceToken = "";
        if (gatewayWebhookUrl == null) gatewayWebhookUrl = "";
        if (mcpConnectTimeoutMs < 1) mcpConnectTimeoutMs = 5_000;
    }
}
```

`SkillRecord.java`：
```java
package com.autovoice.server.skillmanager;

/** skill 表记录（与网关 SkillConfig 字段对齐；平台侧 authValue 存明文）。 */
public record SkillRecord(String id, String name, String description, String mcpUrl,
                          String authHeader, String authValue, String toolsJson,
                          boolean enabled, long updatedAt) {
}
```

`SqliteSkillStore.java`（镜像 SqliteTelemetryStore 短连接模式）：
```java
package com.autovoice.server.skillmanager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** skill 表 SQLite 存储：短连接 + busy_timeout，模式同 telemetry 存储。 */
public final class SqliteSkillStore {

    private final String dbPath;

    public SqliteSkillStore(String dbPath) {
        this.dbPath = dbPath;
    }

    public void init() {
        Path p = Path.of(dbPath);
        Path parent = p.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("skill db dir create failed: " + parent, e);
            }
        }
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS skills ("
                    + "id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '',"
                    + "mcp_url TEXT NOT NULL, auth_header TEXT NOT NULL DEFAULT '', auth_value TEXT NOT NULL DEFAULT '',"
                    + "tools_json TEXT NOT NULL DEFAULT '[]', enabled INTEGER NOT NULL DEFAULT 0,"
                    + "updated_at INTEGER NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("skill schema init failed: " + dbPath, e);
        }
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    /** enabledOnly=true 时仅返回 enabled=1 的记录（网关拉取路径）。 */
    public List<SkillRecord> findAll(boolean enabledOnly) {
        String sql = enabledOnly
                ? "SELECT * FROM skills WHERE enabled=1 ORDER BY id"
                : "SELECT * FROM skills ORDER BY id";
        List<SkillRecord> out = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("skill find failed", e);
        }
        return out;
    }

    public SkillRecord findById(String id) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM skills WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("skill findById failed: " + id, e);
        }
    }

    public void upsert(SkillRecord r) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO skills (id, name, description, mcp_url, auth_header, auth_value,"
                        + " tools_json, enabled, updated_at) VALUES (?,?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT(id) DO UPDATE SET name=excluded.name, description=excluded.description,"
                        + " mcp_url=excluded.mcp_url, auth_header=excluded.auth_header, auth_value=excluded.auth_value,"
                        + " tools_json=excluded.tools_json, enabled=excluded.enabled, updated_at=excluded.updated_at")) {
            ps.setString(1, r.id());
            ps.setString(2, r.name());
            ps.setString(3, r.description());
            ps.setString(4, r.mcpUrl());
            ps.setString(5, r.authHeader());
            ps.setString(6, r.authValue());
            ps.setString(7, r.toolsJson());
            ps.setInt(8, r.enabled() ? 1 : 0);
            ps.setLong(9, r.updatedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("skill upsert failed: " + r.id(), e);
        }
    }

    public void delete(String id) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM skills WHERE id=?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("skill delete failed: " + id, e);
        }
    }

    private static SkillRecord map(ResultSet rs) throws SQLException {
        return new SkillRecord(rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("mcp_url"), rs.getString("auth_header"), rs.getString("auth_value"),
                rs.getString("tools_json"), rs.getInt("enabled") == 1, rs.getLong("updated_at"));
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :skill-manager:test`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git -C /Users/michaelliu/code/AutoVoice add AutoVoiceServer/settings.gradle.kts AutoVoiceServer/skill-manager
git -C /Users/michaelliu/code/AutoVoice commit -m "feat(skill-manager): 平台脚手架 + SQLite 存储"
```

---

### Task 7: 平台 API（CRUD / enabled / 管理鉴权）

**Files:**
- Create: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/SkillService.java`
- Create: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/SkillController.java`
- Create: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/AdminController.java`
- Create: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/AdminAuthInterceptor.java`
- Create: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/WebMvcConfig.java`
- Test: `AutoVoiceServer/skill-manager/src/test/java/com/autovoice/server/skillmanager/SkillControllerTest.java`
- Test: `AutoVoiceServer/skill-manager/src/test/java/com/autovoice/server/skillmanager/AdminAuthTest.java`

**Interfaces:**
- Consumes: `SkillProperties` / `SqliteSkillStore` / `SkillRecord`（Task 6）。webhook 推送器（Task 8）通过 `java.util.function.Consumer<String>` 回调注入 `SkillService`（Task 8 之前先传 no-op，Task 8 换真实现——**为避免改动两遍，本任务直接定义 `SkillWebhookNotifier` 接口并由 SkillService 持有，Task 8 提供 HTTP 实现**）。
  ```java
  public interface SkillWebhookNotifier { void notifySkillChanged(String skillId); }
  ```
  （文件在 Task 8 建；本任务测试里用 lambda 注入。）—— 修正：接口文件在**本任务**创建（SkillService 的构造器参数类型），实现类 Task 8 建。
- Produces:
  ```java
  public final class SkillService {
      public SkillService(SqliteSkillStore store, SkillWebhookNotifier notifier, LongSupplier clock);
      public List<SkillResponse> list(boolean gatewayView);          // gatewayView=true 明文
      public SkillResponse get(String id, boolean gatewayView);
      public SkillResponse create(SkillRequest req);                 // 409 语义由 controller 用 exists 判断
      public boolean exists(String id);
      public SkillResponse update(String id, SkillRequest req);      // authValue 留空=保留
      public void delete(String id);
      public SkillResponse setEnabled(String id, boolean enabled);
      public SkillResponse toResponse(SkillRecord r, boolean gatewayView);  // 掩码逻辑
  }
  public record SkillRequest(String id, String name, String description, String mcpUrl,
                             String authHeader, String authValue, String toolsJson, Boolean enabled) {}
  public record SkillResponse(String id, String name, String description, String mcpUrl,
                              String authHeader, String authValue, String toolsJson,
                              boolean enabled, long updatedAt) {}
  public record ToolInfo(String name, String description) {}          // discover 返回元素（Task 8 用）
  ```

- [ ] **Step 1: 写失败测试**

`SkillControllerTest.java`（MockMvc 起完整 context——SkillManagerApplication + 临时 db；镜像 app 模块 @SpringBootTest properties 覆盖写法）：
```java
package com.autovoice.server.skillmanager;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

@SpringBootTest(properties = {
        "autovoice.skill-manager.db-path=${java.io.tmpdir}/skill-mgr-test-${random.uuid}.db",
        "autovoice.skill-manager.admin-token=admin-secret",
        "autovoice.skill-manager.service-token=svc-secret",
        "autovoice.skill-manager.gateway-webhook-url="})
@AutoConfigureMockMvc
class SkillControllerTest {

    @Autowired MockMvc mvc;
    @Autowired SqliteSkillStore store;
    static final ObjectMapper MAPPER = new ObjectMapper();

    private String login() throws Exception {
        return mvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"admin-secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("skill_admin").getValue();
    }

    private org.springframework.test.web.servlet.MvcResult createSkill() throws Exception {
        return mvc.perform(post("/api/skills")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"amap-maps\",\"name\":\"高德地图\",\"description\":\"导航\","
                                + "\"mcpUrl\":\"https://mcp.example.com/mcp\",\"authHeader\":\"x-api-key\","
                                + "\"authValue\":\"secret-1\",\"toolsJson\":\"[]\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mvc.perform(get("/api/skills")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"a\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void serviceTokenPullsEnabledWithRawSecret() throws Exception {
        createSkill();
        mvc.perform(get("/api/skills").param("enabled", "true")
                        .header("X-Skill-Service-Token", "svc-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].authValue").value("secret-1"))      // 网关拿明文
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void adminViewMasksSecret() throws Exception {
        createSkill();
        mvc.perform(get("/api/skills").cookie(new jakarta.servlet.http.Cookie("skill_admin", login())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].authValue").value("****"));         // 管理端掩码
    }

    @Test
    void updateWithBlankAuthKeepsOldValue() throws Exception {
        createSkill();
        mvc.perform(put("/api/skills/amap-maps")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"amap-maps\",\"name\":\"高德地图2\",\"description\":\"导航2\","
                                + "\"mcpUrl\":\"https://mcp.example.com/mcp\",\"authHeader\":\"x-api-key\","
                                + "\"authValue\":\"\",\"toolsJson\":\"[]\",\"enabled\":true}"))
                .andExpect(status().isOk());
        assert "secret-1".equals(store.findById("amap-maps").authValue()) : "blank 保留旧值";
    }

    @Test
    void duplicateCreateReturns409() throws Exception {
        createSkill();
        mvc.perform(post("/api/skills")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"amap-maps\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void patchEnabledToggles() throws Exception {
        createSkill();
        mvc.perform(patch("/api/skills/amap-maps/enabled")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/skills").param("enabled", "true")
                        .header("X-Skill-Service-Token", "svc-secret"))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void deleteRemoves() throws Exception {
        createSkill();
        mvc.perform(delete("/api/skills/amap-maps")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/skills/amap-maps")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login())))
                .andExpect(status().isNotFound());
    }
}
```

`AdminAuthTest.java`（鉴权细节：错误口令 401、错误 service token 401）：
```java
package com.autovoice.server.skillmanager;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "autovoice.skill-manager.db-path=${java.io.tmpdir}/skill-mgr-auth-${random.uuid}.db",
        "autovoice.skill-manager.admin-token=admin-secret",
        "autovoice.skill-manager.service-token=svc-secret"})
@AutoConfigureMockMvc
class AdminAuthTest {

    @Autowired MockMvc mvc;

    @Test
    void wrongPasswordRejected() throws Exception {
        mvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongServiceTokenRejected() throws Exception {
        mvc.perform(get("/api/skills").param("enabled", "true")
                        .header("X-Skill-Service-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutClearsCookie() throws Exception {
        mvc.perform(post("/api/admin/logout"))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("skill_admin", 0));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :skill-manager:test`
Expected: FAIL（编译错）。

- [ ] **Step 3: 写实现**

`SkillWebhookNotifier.java`：
```java
package com.autovoice.server.skillmanager;

/** skill 变更通知（写操作后触发；网关刷新）。Task 8 提供 HTTP 实现，测试注入 lambda。 */
public interface SkillWebhookNotifier {
    void notifySkillChanged(String skillId);
}
```

`SkillService.java`：
```java
package com.autovoice.server.skillmanager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** skill 业务：CRUD、启停、脱敏（管理端掩码 / 网关拉取明文）、变更后 webhook 通知。 */
public final class SkillService {

    static final String MASKED = "****";

    private final SqliteSkillStore store;
    private final SkillWebhookNotifier notifier;
    private final LongSupplier clock;

    public SkillService(SqliteSkillStore store, SkillWebhookNotifier notifier, LongSupplier clock) {
        this.store = store;
        this.notifier = notifier;
        this.clock = clock;
    }

    public List<SkillResponse> list(boolean gatewayView) {
        List<SkillResponse> out = new ArrayList<>();
        for (SkillRecord r : store.findAll(gatewayView)) {   // 网关视图只拿 enabled
            out.add(toResponse(r, gatewayView));
        }
        return out;
    }

    public SkillResponse get(String id, boolean gatewayView) {
        SkillRecord r = store.findById(id);
        return r == null ? null : toResponse(r, gatewayView);
    }

    public boolean exists(String id) {
        return store.findById(id) != null;
    }

    public SkillResponse create(SkillRequest req) {
        SkillRecord r = new SkillRecord(req.id(), req.name(), req.description(), req.mcpUrl(),
                req.authHeader(), req.authValue() == null ? "" : req.authValue(),
                req.toolsJson() == null ? "[]" : req.toolsJson(),
                req.enabled() != null && req.enabled(), clock.getAsLong());
        store.upsert(r);
        notifier.notifySkillChanged(r.id());
        return toResponse(r, false);
    }

    public SkillResponse update(String id, SkillRequest req) {
        SkillRecord old = store.findById(id);
        SkillRecord r = new SkillRecord(id, req.name(), req.description(), req.mcpUrl(),
                req.authHeader(),
                // 留空（null/空串）= 保留旧值
                (req.authValue() == null || req.authValue().isBlank())
                        ? (old == null ? "" : old.authValue())
                        : req.authValue(),
                req.toolsJson() == null ? "[]" : req.toolsJson(),
                req.enabled() != null ? req.enabled() : (old != null && old.enabled()),
                clock.getAsLong());
        store.upsert(r);
        notifier.notifySkillChanged(id);
        return toResponse(r, false);
    }

    public void delete(String id) {
        store.delete(id);
        notifier.notifySkillChanged(id);
    }

    public SkillResponse setEnabled(String id, boolean enabled) {
        SkillRecord old = store.findById(id);
        SkillRecord r = new SkillRecord(id, old.name(), old.description(), old.mcpUrl(),
                old.authHeader(), old.authValue(), old.toolsJson(), enabled, clock.getAsLong());
        store.upsert(r);
        notifier.notifySkillChanged(id);
        return toResponse(r, false);
    }

    /** 脱敏：网关视图（service token 拉取）返回明文；管理端视图非空即 "****"。 */
    public SkillResponse toResponse(SkillRecord r, boolean gatewayView) {
        String authValue = gatewayView ? r.authValue()
                : (r.authValue() == null || r.authValue().isBlank() ? "" : MASKED);
        return new SkillResponse(r.id(), r.name(), r.description(), r.mcpUrl(),
                r.authHeader(), authValue, r.toolsJson(), r.enabled(), r.updatedAt());
    }
}
```

`SkillController.java`：
```java
package com.autovoice.server.skillmanager;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * skill 管理 + 网关拉取 API。鉴权由 AdminAuthInterceptor 统一做；
 * 视图区分：?enabled=true（service token）→ 网关视图明文；否则管理端视图掩码。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService service;
    private final SqliteSkillStore store;

    public SkillController(SkillService service, SqliteSkillStore store) {
        this.service = service;
        this.store = store;
    }

    @GetMapping
    public List<SkillResponse> list(@RequestParam(value = "enabled", required = false) Boolean enabled,
                                    HttpServletRequest request) {
        boolean gatewayView = Boolean.TRUE.equals(enabled);
        return service.list(gatewayView);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SkillResponse> get(@PathVariable String id) {
        SkillResponse r = service.get(id, false);
        return r == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(r);
    }

    @PostMapping
    public ResponseEntity<SkillResponse> create(@RequestBody SkillRequest req) {
        if (req.id() == null || req.id().isBlank() || !req.id().matches("[a-zA-Z0-9._-]+")) {
            return ResponseEntity.badRequest().build();
        }
        if (service.exists(req.id())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SkillResponse> update(@PathVariable String id, @RequestBody SkillRequest req) {
        if (!service.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!service.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        service.delete(id);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<SkillResponse> setEnabled(@PathVariable String id,
                                                    @RequestBody EnableRequest req) {
        if (!service.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(service.setEnabled(id, req.enabled()));
    }

    public record EnableRequest(boolean enabled) {}
}
```
（`SkillRequest` record 放同包独立文件：`SkillRequest.java` + `SkillResponse.java` 两个 record 文件，字段见 Interfaces 节。）

`AdminController.java`：
```java
package com.autovoice.server.skillmanager;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 管理端口令登录（demo 级）：password 匹配 ADMIN_TOKEN 后发 HttpOnly cookie。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    static final String COOKIE_NAME = "skill_admin";

    private final String adminToken;

    public AdminController(SkillProperties props) {
        this.adminToken = props.adminToken() == null ? "" : props.adminToken();
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@RequestBody LoginRequest req, HttpServletResponse response) {
        byte[] given = req.password() == null ? new byte[0] : req.password().getBytes(StandardCharsets.UTF_8);
        byte[] expected = adminToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, expected)) {
            return ResponseEntity.status(401).build();
        }
        Cookie c = new Cookie(COOKIE_NAME, cookieValue(adminToken));
        c.setHttpOnly(true);
        c.setPath("/");
        c.setMaxAge(60 * 60 * 12);
        response.addCookie(c);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        Cookie c = new Cookie(COOKIE_NAME, "");
        c.setHttpOnly(true);
        c.setPath("/");
        c.setMaxAge(0);
        response.addCookie(c);
        return ResponseEntity.ok().build();
    }

    /** cookie 值 = SHA-256(token) 十六进制；拦截器同样计算比对。 */
    static String cookieValue(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record LoginRequest(String password) {}
}
```

`AdminAuthInterceptor.java`：
```java
package com.autovoice.server.skillmanager;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 平台鉴权：/api/skills/** 与 /api/admin/**（除 login）要求
 * 管理端 cookie 或 X-Skill-Service-Token 二选一通过。
 */
public class AdminAuthInterceptor implements HandlerInterceptor {

    public static final String SERVICE_TOKEN_HEADER = "X-Skill-Service-Token";

    private final String adminToken;
    private final String serviceToken;

    public AdminAuthInterceptor(String adminToken, String serviceToken) {
        this.adminToken = adminToken == null ? "" : adminToken;
        this.serviceToken = serviceToken == null ? "" : serviceToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (checkServiceToken(request) || checkCookie(request)) {
            return true;
        }
        response.setStatus(401);
        response.setContentType("application/json; charset=utf-8");
        response.getWriter().write("{\"error\":\"unauthorized\"}");
        return false;
    }

    private boolean checkServiceToken(HttpServletRequest request) {
        String given = request.getHeader(SERVICE_TOKEN_HEADER);
        return given != null && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), serviceToken.getBytes(StandardCharsets.UTF_8));
    }

    private boolean checkCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        String expected = AdminController.cookieValue(adminToken);
        for (Cookie c : cookies) {
            if (AdminController.COOKIE_NAME.equals(c.getName()) && c.getValue() != null
                    && MessageDigest.isEqual(c.getValue().getBytes(StandardCharsets.UTF_8),
                                              expected.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }
}
```

`WebMvcConfig.java`：
```java
package com.autovoice.server.skillmanager;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册平台鉴权拦截器（/api/skills/** 与 /api/admin/**；login 放行）。 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final SkillProperties props;

    public WebMvcConfig(SkillProperties props) {
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminAuthInterceptor(props.adminToken(), props.serviceToken()))
                .addPathPatterns("/api/skills/**", "/api/admin/**")
                .excludePathPatterns("/api/admin/login");
    }

    @Bean
    public SkillService skillService(SqliteSkillStore store, SkillWebhookNotifier notifier) {
        return new SkillService(store, notifier, System::currentTimeMillis);
    }

    @Bean
    public SqliteSkillStore sqliteSkillStore(SkillProperties props) {
        SqliteSkillStore store = new SqliteSkillStore(props.dbPath());
        store.init();
        return store;
    }

    @Bean
    public SkillWebhookNotifier skillWebhookNotifier(SkillProperties props) {
        // Task 8 换成 SkillWebhookPublisher（HTTP 推送）；当前 no-op 占位
        return skillId -> {};
    }
}
```

`SkillRequest.java` / `SkillResponse.java`（独立文件）：
```java
package com.autovoice.server.skillmanager;

/** 创建/更新请求体。authValue 创建时必填；更新时留空=保留旧值。 */
public record SkillRequest(String id, String name, String description, String mcpUrl,
                           String authHeader, String authValue, String toolsJson, Boolean enabled) {
}
```
```java
package com.autovoice.server.skillmanager;

/** API 响应体。authValue 按视图掩码（管理端 "****" / 网关明文）。 */
public record SkillResponse(String id, String name, String description, String mcpUrl,
                            String authHeader, String authValue, String toolsJson,
                            boolean enabled, long updatedAt) {
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :skill-manager:test`
Expected: PASS（Task 6 + Task 7 全部用例）。
（注意：`AdminAuthTest.logoutClearsCookie` 断言 `cookie().maxAge("skill_admin", 0)` 若与 Spring 渲染细节不符（maxAge 0 可能显示为 -1/0），改为断言响应 cookie 存在且值为空即可——以实际行为修正断言。）

- [ ] **Step 5: 提交**

```bash
git -C /Users/michaelliu/code/AutoVoice add AutoVoiceServer/skill-manager/src/main/java AutoVoiceServer/skill-manager/src/test/java
git -C /Users/michaelliu/code/AutoVoice commit -m "feat(skill-manager): skill CRUD/启停 API + 管理鉴权（cookie/服务 token）"
```

---

### Task 8: discover 端点 + webhook 推送

**Files:**
- Modify: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/SkillController.java`
- Create: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/McpDiscoveryClient.java`
- Create: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/SkillWebhookPublisher.java`
- Modify: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/WebMvcConfig.java`（notifier bean 换真实现）
- Test: `AutoVoiceServer/skill-manager/src/test/java/com/autovoice/server/skillmanager/McpDiscoveryClientTest.java`
- Test: `AutoVoiceServer/skill-manager/src/test/java/com/autovoice/server/skillmanager/SkillWebhookPublisherTest.java`
- Test: `AutoVoiceServer/skill-manager/src/test/java/com/autovoice/server/skillmanager/SkillDiscoverControllerTest.java`

**Interfaces:**
- Consumes: `SkillWebhookNotifier`（Task 7）、`SkillRecord`。
- Produces:
  ```java
  public final class McpDiscoveryClient {
      public McpDiscoveryClient(long connectTimeoutMs);
      public List<ToolInfo> discover(String mcpUrl, String authHeader, String authValue) throws IOException;
  }
  public final class SkillWebhookPublisher implements SkillWebhookNotifier {
      public SkillWebhookPublisher(OkHttpClient client, String gatewayWebhookUrl, String serviceToken);
  }
  ```
  `ToolInfo` record 在 Task 7 的 Interfaces 已声明（`(String name, String description)`），放 `skill-manager` 包独立文件。

- [ ] **Step 1: 写失败测试**

`McpDiscoveryClientTest.java`（复用 skill-mcp 测试的 FakeMcpServer 思路，**本模块测试目录自建一份** `FakeMcpServer.java`（内容同 Task 4 那份，仅包名不同））：
```java
package com.autovoice.server.skillmanager;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class McpDiscoveryClientTest {

    @Test
    void discoversToolNamesAndDescriptions() throws Exception {
        try (FakeMcpServer fake = new FakeMcpServer()) {
            McpDiscoveryClient client = new McpDiscoveryClient(5_000);
            List<ToolInfo> tools = client.discover(fake.url(), "", "");
            assertEquals(2, tools.size());
            assertEquals("poi_search", tools.get(0).name());
            assertTrue(tools.get(0).description().contains("搜索"));
        }
    }

    @Test
    void unreachableServerThrows() {
        McpDiscoveryClient client = new McpDiscoveryClient(1_000);
        assertThrows(Exception.class, () -> client.discover("http://127.0.0.1:1/mcp", "", ""));
    }
}
```

`SkillWebhookPublisherTest.java`：
```java
package com.autovoice.server.skillmanager;

import static org.junit.jupiter.api.Assertions.*;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

class SkillWebhookPublisherTest {

    @Test
    void postsRefreshWithToken() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        SkillWebhookPublisher p = new SkillWebhookPublisher(new OkHttpClient(),
                server.url("/").toString(), "svc-secret");
        p.notifySkillChanged("amap-maps");
        RecordedRequest r = server.takeRequest(3, TimeUnit.SECONDS);
        assertEquals("/api/internal/skills/refresh", r.getPath());
        assertEquals("svc-secret", r.getHeader("X-Skill-Service-Token"));
        server.shutdown();
    }

    @Test
    void failureIsSwallowed() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(500));
        SkillWebhookPublisher p = new SkillWebhookPublisher(new OkHttpClient(),
                server.url("/").toString(), "svc-secret");
        p.notifySkillChanged("x"); // 不抛：webhook 是尽力而为
        server.shutdown();
    }
}
```

`SkillDiscoverControllerTest.java`（discover 路由 + 鉴权）：
```java
package com.autovoice.server.skillmanager;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "autovoice.skill-manager.db-path=${java.io.tmpdir}/skill-mgr-disc-${random.uuid}.db",
        "autovoice.skill-manager.admin-token=admin-secret",
        "autovoice.skill-manager.service-token=svc-secret"})
@AutoConfigureMockMvc
class SkillDiscoverControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void discoverRequiresAuth() throws Exception {
        mvc.perform(post("/api/skills/x/discover")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
```
（discover 成功路径依赖真实 MCP server，放 E2E 验证（Task 12）用本地假服务器跑；本任务只测鉴权与路由注册。）

- [ ] **Step 2: 运行确认失败**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :skill-manager:test`
Expected: FAIL（编译错：找不到 McpDiscoveryClient / SkillWebhookPublisher / ToolInfo）。

- [ ] **Step 3: 写实现**

`McpDiscoveryClient.java`（平台侧一次性连接发现，用完即关）：
```java
package com.autovoice.server.skillmanager;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
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
        HttpClientStreamableHttpTransport.Builder tb = HttpClientStreamableHttpTransport
                .builder(URI.create(mcpUrl))
                .connectTimeout(Duration.ofMillis(connectTimeoutMs));
        if (authHeader != null && !authHeader.isBlank()) {
            String header = authHeader;
            String value = authValue == null ? "" : authValue;
            tb.httpRequestCustomizer((HttpRequest.Builder b, String method, URI endpoint, String body) ->
                    b.header(header, value));
        }
        McpSyncClient c = McpClient.sync(tb.build())
                .requestTimeout(Duration.ofMillis(connectTimeoutMs))
                .clientInfo(new McpSchema.Implementation("autovoice-skill-manager", "1.0"))
                .build();
        try {
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
            c.closeGracefully();
        }
    }
}
```

`SkillWebhookPublisher.java`：
```java
package com.autovoice.server.skillmanager;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/** skill 变更后推送网关刷新（尽力而为：失败仅日志，不阻断写操作）。 */
public final class SkillWebhookPublisher implements SkillWebhookNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(SkillWebhookPublisher.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final String gatewayWebhookUrl;
    private final String serviceToken;

    public SkillWebhookPublisher(OkHttpClient client, String gatewayWebhookUrl, String serviceToken) {
        this.client = client.newBuilder()
                .callTimeout(3_000, TimeUnit.MILLISECONDS)
                .build();
        this.gatewayWebhookUrl = gatewayWebhookUrl == null ? "" : gatewayWebhookUrl.trim();
        this.serviceToken = serviceToken;
    }

    @Override
    public void notifySkillChanged(String skillId) {
        if (gatewayWebhookUrl.isEmpty()) {
            return; // 未配置网关地址（单机开发）→ 跳过推送
        }
        String url = gatewayWebhookUrl.endsWith("/")
                ? gatewayWebhookUrl + "api/internal/skills/refresh"
                : gatewayWebhookUrl + "/api/internal/skills/refresh";
        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create("{\"skillId\":\"" + skillId + "\"}", JSON))
                .header("X-Skill-Service-Token", serviceToken)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                LOG.warn("skill webhook push failed: HTTP {}", resp.code());
            }
        } catch (Exception e) {
            LOG.warn("skill webhook push failed: {}", String.valueOf(e.getMessage()));
        }
    }
}
```

`ToolInfo.java`：
```java
package com.autovoice.server.skillmanager;

/** discover 返回的工具信息（供界面勾选）。 */
public record ToolInfo(String name, String description) {
}
```

`SkillController` 增加 discover 端点（在类内加方法 + 构造器注入 `McpDiscoveryClient`）：
```java
    private final McpDiscoveryClient discovery;

    // 构造器改为：public SkillController(SkillService service, SqliteSkillStore store, McpDiscoveryClient discovery)

    /** 发现工具（用保存的配置；body 可选覆盖 mcpUrl/authHeader/authValue），不落库。 */
    @PostMapping("/{id}/discover")
    public ResponseEntity<List<ToolInfo>> discover(@PathVariable String id,
                                                   @RequestBody(required = false) DiscoverOverride body) {
        SkillRecord r = store.findById(id);
        if (r == null) {
            return ResponseEntity.notFound().build();
        }
        String mcpUrl = body != null && body.mcpUrl() != null ? body.mcpUrl() : r.mcpUrl();
        String authHeader = body != null && body.authHeader() != null ? body.authHeader() : r.authHeader();
        String authValue = body != null && body.authValue() != null ? body.authValue() : r.authValue();
        try {
            return ResponseEntity.ok(discovery.discover(mcpUrl, authHeader, authValue));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(null);
        }
    }

    public record DiscoverOverride(String mcpUrl, String authHeader, String authValue) {}
```

`WebMvcConfig` 的 notifier bean 换成真实现：
```java
    @Bean
    public SkillWebhookNotifier skillWebhookNotifier(SkillProperties props) {
        return new SkillWebhookPublisher(new okhttp3.OkHttpClient(),
                props.gatewayWebhookUrl(), props.serviceToken());
    }

    @Bean
    public McpDiscoveryClient mcpDiscoveryClient(SkillProperties props) {
        return new McpDiscoveryClient(props.mcpConnectTimeoutMs());
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :skill-manager:test`
Expected: PASS（全部用例）。

- [ ] **Step 5: 提交**

```bash
git -C /Users/michaelliu/code/AutoVoice add AutoVoiceServer/skill-manager/src
git -C /Users/michaelliu/code/AutoVoice commit -m "feat(skill-manager): discover 端点 + webhook 推送网关"
```

---

### Task 9: DeepSeekLlmProvider 多轮工具循环

**Files:**
- Modify: `AutoVoiceServer/llm/src/main/java/com/autovoice/server/llm/DeepSeekLlmProvider.java`
- Test: `AutoVoiceServer/llm/src/test/java/com/autovoice/server/llm/DeepSeekLlmLoopTest.java`
- Test: `AutoVoiceServer/llm/src/test/java/com/autovoice/server/llm/DeepSeekLlmBudgetTest.java`

**Interfaces:**
- Consumes: `FunctionTool`/`ToolProvider`/`ToolExecutor`（Task 1）。llm 模块 build.gradle.kts 已依赖 contracts（api），无需改。
- Produces:
  ```java
  public final class DeepSeekLlmProvider implements LlmProvider {
      public static final long DEFAULT_TOOL_LOOP_BUDGET_MS = 5_000;
      static final int MAX_LLM_ROUNDS = 3;
      public DeepSeekLlmProvider(OkHttpClient client, String apiKey, String endpoint, TelemetryRecorder recorder);  // 保留：默认 tools + executor null
      public DeepSeekLlmProvider(OkHttpClient client, String apiKey, String endpoint, TelemetryRecorder recorder,
                                 ToolProvider tools, long toolLoopBudgetMs, ToolExecutor executor);
      public static List<FunctionTool> defaultTools();   // car_control 唯一工具
  }
  ```
  行为（spec §6）：**最多 3 次 LLM 调用**。第 1-2 次带 tools（预算内）；第 3 次（最后）不带 tools 强制直答。每轮前检查 `now - start > budget` → 后续调用不带 tools。model 调用 car_control → 立即终局（`Reply.ofAction`，不续轮）。MCP 工具 → `executor.execute`（异常 → 错误文本作为 tool_result）→ `{role:"tool"}` 消息追加续轮。无 tools 的调用仍返回 tool_calls → `LlmException`。4 参构造器行为与现状完全一致（单轮等价）。

- [ ] **Step 1: 写失败测试**

`DeepSeekLlmLoopTest.java`（MockWebServer Dispatcher 模拟两轮 tool_use）：
```java
package com.autovoice.server.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.autovoice.server.contracts.FunctionTool;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.ToolExecutor;
import com.autovoice.server.contracts.ToolProvider;
import com.autovoice.server.contracts.telemetry.NoopTelemetryRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class DeepSeekLlmLoopTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 模拟 LLM：第 1 次请求 → 调 poi_search 工具；第 2 次 → 最终文本。记录收到的请求数。 */
    private static MockWebServer twoRoundLlm(AtomicInteger calls) throws Exception {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int n = calls.incrementAndGet();
                try {
                    JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
                    boolean hasTools = body.path("tools").isArray() && !body.path("tools").isEmpty();
                    String content;
                    if (n == 1 && hasTools) {
                        content = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                                + "\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                                + "\"function\":{\"name\":\"poi_search\",\"arguments\":\"{\\\"query\\\":\\\"西湖\\\"}\"}}]}}]}";
                    } else {
                        content = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"已为您查询到西湖附近的景点。\"}}]}";
                    }
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody(content);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        server.start();
        return server;
    }

    @Test
    void twoRoundToolLoop() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger execs = new AtomicInteger();
        try (MockWebServer llm = twoRoundLlm(calls)) {
            ToolProvider tools = () -> List.of(new FunctionTool("poi_search", "搜索兴趣点",
                    "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}"));
            ToolExecutor exec = (name, args) -> {
                execs.incrementAndGet();
                assertEquals("{\"query\":\"西湖\"}", args);
                return "西湖，国家 5A 级景区";
            };
            DeepSeekLlmProvider provider = new DeepSeekLlmProvider(new OkHttpClient(), "test-key",
                    llm.url("/chat/completions").toString(), NoopTelemetryRecorder.INSTANCE,
                    tools, 5_000, exec);
            Reply r = provider.chat("导航去西湖", new SessionContext("s1", "zh", java.util.Map.of()))
                    .get(10, TimeUnit.SECONDS);
            assertEquals("text", r.kind());
            assertEquals("已为您查询到西湖附近的景点。", r.text());
            assertEquals(2, calls.get());
            assertEquals(1, execs.get());
        }
    }

    @Test
    void carControlIsTerminalNoLoop() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    // 第 1 次请求返回 car_control 工具调用（默认工具，4 参构造器）
                    return new MockResponse().setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                            + "\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                            + "\"function\":{\"name\":\"car_control\",\"arguments\":"
                            + "\"{\\\"domain\\\":\\\"climate\\\",\\\"action\\\":\\\"power_on\\\"}\"}}]}}]}");
                }
                // 若 provider 续轮，第 2 次请求应出现 —— 断言其不会发生
                fail("car_control 必须终局，不应有第 2 次 LLM 调用");
                return new MockResponse();
            }
        });
        server.start();
        try {
            DeepSeekLlmProvider provider = new DeepSeekLlmProvider(new OkHttpClient(), "test-key",
                    server.url("/").toString(), NoopTelemetryRecorder.INSTANCE);
            Reply r = provider.chat("打开空调", new SessionContext("s1", "zh", java.util.Map.of()))
                    .get(10, TimeUnit.SECONDS);
            assertEquals("action", r.kind());
            assertEquals("climate", r.intent().domain());
            assertEquals("power_on", r.intent().intent());
            assertEquals(1, calls.get()); // 单轮终局，不续轮
        } finally {
            server.shutdown();
        }
    }
}
```

`DeepSeekLlmBudgetTest.java`（预算强制直答 + 轮数上限 + 无工具仍调工具 → 异常）：
```java
package com.autovoice.server.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.autovoice.server.contracts.FunctionTool;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.ToolExecutor;
import com.autovoice.server.contracts.ToolProvider;
import com.autovoice.server.contracts.telemetry.NoopTelemetryRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class DeepSeekLlmBudgetTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void zeroBudgetForcesDirectAnswer() throws Exception {
        // budget=0：第 1 次调用即不带 tools；模型直答
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
                    boolean hasTools = body.path("tools").isArray() && !body.path("tools").isEmpty();
                    if (hasTools) {
                        return new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"不应出现\"}}]}");
                    }
                    return new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"直答文本\"}}]}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        server.start();
        try {
            ToolProvider tools = () -> List.of(new FunctionTool("t", "d", "{}"));
            DeepSeekLlmProvider provider = new DeepSeekLlmProvider(new OkHttpClient(), "k",
                    server.url("/").toString(), NoopTelemetryRecorder.INSTANCE, tools, 0, (n, a) -> "x");
            Reply r = provider.chat("hi", new SessionContext("s", "zh", Map.of())).get(5, TimeUnit.SECONDS);
            assertEquals("直答文本", r.text());
        } finally {
            server.shutdown();
        }
    }

    @Test
    void roundLimitEndsWithForcedFinalCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger noToolCalls = new AtomicInteger();
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                calls.incrementAndGet();
                try {
                    JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
                    boolean hasTools = body.path("tools").isArray() && !body.path("tools").isEmpty();
                    if (!hasTools) {
                        noToolCalls.incrementAndGet();
                        return new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"最终答复\"}}]}");
                    }
                    // 每次都调工具（模型不收敛）
                    return new MockResponse().setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                            + "\"tool_calls\":[{\"id\":\"call-x\",\"type\":\"function\","
                            + "\"function\":{\"name\":\"t\",\"arguments\":\"{}\"}}]}}]}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        server.start();
        try {
            ToolProvider tools = () -> List.of(new FunctionTool("t", "d", "{}"));
            ToolExecutor exec = (n, a) -> "ok";
            DeepSeekLlmProvider provider = new DeepSeekLlmProvider(new OkHttpClient(), "k",
                    server.url("/").toString(), NoopTelemetryRecorder.INSTANCE, tools, 60_000, exec);
            Reply r = provider.chat("hi", new SessionContext("s", "zh", Map.of())).get(5, TimeUnit.SECONDS);
            assertEquals("最终答复", r.text());
            assertEquals(3, calls.get());       // 2 轮带工具 + 1 轮强制直答
            assertEquals(1, noToolCalls.get());
        } finally {
            server.shutdown();
        }
    }

    @Test
    void toolCallWithoutToolsThrows() throws Exception {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                // 无论是否带 tools 都返回工具调用（模型不配合）
                return new MockResponse().setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                        + "\"tool_calls\":[{\"id\":\"call-x\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"t\",\"arguments\":\"{}\"}}]}}]}");
            }
        });
        server.start();
        try {
            DeepSeekLlmProvider provider = new DeepSeekLlmProvider(new OkHttpClient(), "k",
                    server.url("/").toString(), NoopTelemetryRecorder.INSTANCE, () -> List.of(), 0, (n, a) -> "x");
            assertThrows(RuntimeException.class, () -> provider.chat("hi",
                    new SessionContext("s", "zh", Map.of())).get(5, TimeUnit.SECONDS));
        } finally {
            server.shutdown();
        }
    }

    @Test
    void toolFailureReturnsFallbackToLlm() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int n = calls.incrementAndGet();
                try {
                    if (n == 1) {
                        return new MockResponse().setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                                + "\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                                + "\"function\":{\"name\":\"t\",\"arguments\":\"{}\"}}]}}]}");
                    }
                    JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
                    String content = body.path("messages").toString().contains("服务不可用")
                            ? "抱歉，服务暂时不可用" : "错误答复";
                    return new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        server.start();
        try {
            ToolExecutor exec = (n, a) -> { throw new RuntimeException("高德服务不可用"); };
            DeepSeekLlmProvider provider = new DeepSeekLlmProvider(new OkHttpClient(), "k",
                    server.url("/").toString(), NoopTelemetryRecorder.INSTANCE,
                    () -> List.of(new FunctionTool("t", "d", "{}")), 5_000, exec);
            Reply r = provider.chat("hi", new SessionContext("s", "zh", Map.of())).get(5, TimeUnit.SECONDS);
            assertEquals("抱歉，服务暂时不可用", r.text()); // 错误文本回 LLM → 兜底回复
        } finally {
            server.shutdown();
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :llm:test`
Expected: FAIL（编译错：7 参构造器不存在；或断言失败：现有行为单轮）。

- [ ] **Step 3: 写实现**

重构 `DeepSeekLlmProvider`（在现有文件上改，保持类注释风格）：
- 新增字段：`ToolProvider tools`、`ToolExecutor executor`、`long toolLoopBudgetMs`。
- 保留 4 参构造器，委托给 7 参：
  ```java
  public DeepSeekLlmProvider(OkHttpClient client, String apiKey, String endpoint, TelemetryRecorder recorder) {
      this(client, apiKey, endpoint, recorder, DeepSeekLlmProvider::defaultTools, DEFAULT_TOOL_LOOP_BUDGET_MS, null);
  }

  public DeepSeekLlmProvider(OkHttpClient client, String apiKey, String endpoint, TelemetryRecorder recorder,
                             ToolProvider tools, long toolLoopBudgetMs, ToolExecutor executor) {
      this.client = client.newBuilder().callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build();
      this.apiKey = apiKey;
      this.endpoint = endpoint;
      this.recorder = recorder;
      this.tools = tools == null ? DeepSeekLlmProvider::defaultTools : tools;
      this.toolLoopBudgetMs = toolLoopBudgetMs < 1 ? DEFAULT_TOOL_LOOP_BUDGET_MS : toolLoopBudgetMs;
      this.executor = executor;
  }
  ```
- `defaultTools()`：把现有 `TOOLS_JSON` 拆为常量 `CAR_CONTROL_PARAMETERS_JSON` + 构造 `FunctionTool`：
  ```java
  public static List<FunctionTool> defaultTools() {
      return List.of(new FunctionTool(TOOL_NAME, "执行车载控制指令（开关空调、调节温度等）", CAR_CONTROL_PARAMETERS_JSON));
  }
  ```
  （`CAR_CONTROL_PARAMETERS_JSON` 取原 TOOLS_JSON 中 parameters 对象文本：`{"type":"object","properties":{"domain":{...},"action":{...},"temperature":{...}},"required":["domain","action"]}`）
- `callAndParse(text)` 重构为多轮循环：
  ```java
  private Reply callAndParse(String text) throws IOException {
      List<ObjectNode> messages = new ArrayList<>();
      messages.add(systemMessage());
      messages.add(userMessage(text));
      long start = System.currentTimeMillis();
      for (int round = 1; round <= MAX_LLM_ROUNDS; round++) {
          boolean budgetOk = System.currentTimeMillis() - start <= toolLoopBudgetMs;
          boolean lastRound = round == MAX_LLM_ROUNDS;
          List<FunctionTool> tools = (budgetOk && !lastRound) ? this.tools.enabledTools() : List.of();
          JsonNode message = callChat(messages, tools);
          JsonNode toolCalls = message.path("tool_calls");
          if (!toolCalls.isArray() || toolCalls.isEmpty()) {
              return textReply(message);
          }
          if (isCarControl(toolCalls)) {
              return carControlReply(toolCalls);   // 终局：action 回复
          }
          if (tools.isEmpty()) {
              throw new LlmException("deepseek llm called tool without tools enabled");
          }
          for (JsonNode tc : toolCalls) {
              String id = tc.path("id").asText("");
              String name = tc.path("function").path("name").asText("");
              String args = tc.path("function").path("arguments").asText("");
              messages.add(assistantToolCallMessage(tc));
              messages.add(toolResultMessage(id, runTool(name, args)));
          }
      }
      // 轮数上限：最后一次不带工具强制直答
      JsonNode message = callChat(messages, List.of());
      return textReply(message);
  }
  ```
  注意循环内最后强制直答的语义：`lastRound` 时不带 tools，若模型仍调工具 → `tools.isEmpty()` 分支抛异常。`runTool`：
  ```java
  private String runTool(String name, String argumentsJson) {
      if (executor == null) {
          return "工具执行不可用";
      }
      try {
          return executor.execute(name, argumentsJson);
      } catch (RuntimeException e) {
          return "工具执行失败：" + e.getMessage();   // 错误文本回 LLM 续轮
      }
  }
  ```
- `callChat(List<ObjectNode> messages, List<FunctionTool> tools)`：原 `buildRequestBody` 改为组装 messages 数组 + tools（`tools.isEmpty()` 时不设 tools 字段）；POST 并返回 `choices[0].message`（复用现有错误语义：无 choices → LlmException）。
- `carControlReply(JsonNode toolCalls)`：原 `parseToolCall` 逻辑（只解析第一个 car_control；多工具混合时优先 car_control 终局——若 tool_calls 含 car_control 直接终局，忽略其他）。
- `isCarControl`：`toolCalls` 中任一 `function.name == TOOL_NAME`。
- `textReply(JsonNode message)`：原 parseCompletion 的无工具分支（content 校验 + `Reply.ofText`）。
- `assistantToolCallMessage` / `toolResultMessage`：OpenAI 兼容组装：
  ```java
  private static ObjectNode assistantToolCallMessage(JsonNode tc) {
      ObjectNode m = MAPPER.createObjectNode();
      m.put("role", "assistant");
      m.set("tool_calls", MAPPER.createArrayNode().add(tc));
      return m;
  }

  private static ObjectNode toolResultMessage(String toolCallId, String content) {
      ObjectNode m = MAPPER.createObjectNode();
      m.put("role", "tool");
      m.put("tool_call_id", toolCallId);
      m.put("content", content);
      return m;
  }
  ```
- `systemMessage()`/`userMessage(text)`：现有两条消息的构造。
- 保留 `replySummary`、`chat` 的 future 包装与 telemetry 记录（不改）。
- **现有测试兼容**：llm 模块现有测试若断言 `TOOLS_JSON` 或私有方法（`buildRequestBody` 私有，测试不直接碰），4 参构造器路径行为与原来一致（单轮：带默认工具；模型直答 → text；调 car_control → action；调其他工具 → 原来抛"unexpected tool"——新逻辑里 executor==null → `工具执行不可用` tool_result 回 LLM，**行为变化**：原抛异常走 safety fallback，新走"错误文本续轮"。若现有测试覆盖该路径，按新语义更新断言：单轮场景下模型调未知工具 → 第 2 轮强制直答（不带 tools）。以 `./gradlew :llm:test` 全绿为准调整。）

- [ ] **Step 4: 运行确认通过**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :llm:test`
Expected: PASS（Task 9 全部 + 现有 llm 用例全绿）。

- [ ] **Step 5: 提交**

```bash
git -C /Users/michaelliu/code/AutoVoice add AutoVoiceServer/llm/src
git -C /Users/michaelliu/code/AutoVoice commit -m "feat(llm): DeepSeekLlmProvider 注入式工具 + 多轮循环（≤3 轮，5s 预算）"
```

---

### Task 10: app 装配（配置 + beans + webhook 端点 + 端到端测试）

**Files:**
- Modify: `AutoVoiceServer/app/src/main/java/com/autovoice/server/app/AppConfig.java`
- Modify: `AutoVoiceServer/app/src/main/java/com/autovoice/server/app/AutoVoiceServerApplication.java`（不需要——skill-mcp 无组件扫描；**确认**：不修改）
- Modify: `AutoVoiceServer/app/src/main/resources/application.yml`
- Modify: `AutoVoiceServer/app/build.gradle.kts`（加 `implementation(project(":skill-mcp"))`）
- Create: `AutoVoiceServer/app/src/main/java/com/autovoice/server/app/SkillRefreshController.java`
- Test: `AutoVoiceServer/app/src/test/java/com/autovoice/server/app/SkillRefreshControllerTest.java`
- Test: `AutoVoiceServer/app/src/test/java/com/autovoice/server/app/AppConfigSkillManagerTest.java`
- Test: `AutoVoiceServer/app/src/test/java/com/autovoice/server/app/McpEndToEndGatewayTest.java`

**Interfaces:**
- Consumes: `SkillPlatformClient`/`McpSkillRegistry`/`McpToolExecutor`/`ToolInjectors`（skill-mcp）、`FunctionTool`/`ToolProvider`/`ToolExecutor`（contracts）、`DeepSeekLlmProvider.defaultTools()`（Task 9）。
- Produces:
  - `AutovoiceProperties` 新增 record：`SkillManager(String url, String serviceToken, long pollMs)`，前缀 `autovoice.skill-manager`，默认 `(null→"", null→"", <1→600000)`。
  - beans：`skillPlatformClient`、`mcpSkillRegistry`（注册后 `start()`）、`mcpToolProvider`（`() -> registry.enabledToolSpecs()`）、`mcpToolExecutor`、`skillRefreshController`；`llmProvider` bean 改用 7 参构造器 + 合并 provider。

- [ ] **Step 1: 写失败测试**

`AppConfigSkillManagerTest.java`（镜像 AppConfigGatewayTest：纯 record 解析，不起 Spring）：
```java
package com.autovoice.server.app;

import static org.junit.jupiter.api.Assertions.*;

import com.autovoice.server.app.AppConfig.AutovoiceProperties;
import org.junit.jupiter.api.Test;

class AppConfigSkillManagerTest {

    @Test
    void skillManagerDefaults() {
        AutovoiceProperties props = new AutovoiceProperties(null, null, null, null, null, null, null);
        var sm = props.skillManager();
        assertEquals("", sm.url());
        assertEquals("", sm.serviceToken());
        assertEquals(600_000, sm.pollMs());
    }

    @Test
    void skillManagerParsed() {
        AutovoiceProperties props = new AutovoiceProperties(null, null, null, null, null, null,
                new AutovoiceProperties.SkillManager("http://127.0.0.1:8083", "tok", 60_000));
        assertEquals("http://127.0.0.1:8083", props.skillManager().url());
        assertEquals(60_000, props.skillManager().pollMs());
    }
}
```

`SkillRefreshControllerTest.java`（@SpringBootTest：webhook 端点鉴权 + 触发刷新）：
```java
package com.autovoice.server.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.autovoice.server.skillmcp.McpSkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "autovoice.skill-manager.url=http://127.0.0.1:1",
        "autovoice.skill-manager.service-token=svc-secret",
        "autovoice.skill-manager.poll-ms=600000",
        "autovoice.telemetry.db-path=${java.io.tmpdir}/skill-refresh-${random.uuid}.db"})
@AutoConfigureMockMvc
class SkillRefreshControllerTest {

    @Autowired MockMvc mvc;
    @MockBean McpSkillRegistry registry;

    @Test
    void wrongTokenRejected() throws Exception {
        mvc.perform(post("/api/internal/skills/refresh")
                        .header("X-Skill-Service-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenTriggersRefresh() throws Exception {
        mvc.perform(post("/api/internal/skills/refresh")
                        .header("X-Skill-Service-Token", "svc-secret")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(registry, org.mockito.Mockito.atLeastOnce()).refreshAsync();
    }
}
```

`McpEndToEndGatewayTest.java`（**本特性核心 E2E**：真 LLM provider（MockWebServer）+ 真 registry（MockWebServer 平台 + MockWebServer MCP）+ WS 探针全链路。镜像 MultiDeviceGatewayTest 的容器形态）：
```java
package com.autovoice.server.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.ToolProvider;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.llm.DeepSeekLlmProvider;
import com.autovoice.server.skillmcp.McpSkillExecutorFactory; // 见下：Task 10 用 lambda 直接构造 McpToolExecutor
import com.autovoice.server.skillmcp.McpSkillRegistry;
import com.autovoice.server.skillmcp.SkillConfig;
import com.autovoice.server.skillmcp.SkillPlatformClient;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 端到端：WS 说话 → ASR(假) → 真 DeepSeekLlmProvider(MockWebServer) 多轮循环
 * → 调 MCP 工具(MockWebServer 假 MCP) → 最终文本回复。验证 registry→注入→循环→执行全链。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "autovoice.skill-manager.url=unused",          // 平台客户端被 @MockBean 替换
        "autovoice.skill-manager.service-token=t",
        "autovoice.skill-manager.poll-ms=600000",
        "autovoice.telemetry.db-path=${java.io.tmpdir}/mcp-e2e-${random.uuid}.db"})
class McpEndToEndGatewayTest {

    @LocalServerPort int port;
    @Autowired OkHttpClient client;

    @MockBean com.autovoice.server.contracts.AsrProvider asr;
    @MockBean com.autovoice.server.contracts.TtsProvider tts;
    @MockBean SkillPlatformClient platform;

    MockWebServer llmServer;
    MockWebServer mcpServer;

    @BeforeEach
    void setUp() throws Exception {
        when(asr.transcribe(any(), any(), any())).thenReturn("导航去西湖");

        // 假 MCP server（tools/list + tools/call）
        mcpServer = new MockWebServer();
        mcpServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    var req = com.fasterxml.jackson.databind.ObjectMapper.readTree(
                            request.getBody().readUtf8(), com.fasterxml.jackson.databind.JsonNode.class);
                    String method = req.path("method").asText("");
                    var result = MAPPER.createObjectNode();
                    MockResponse resp = new MockResponse().setHeader("Content-Type", "application/json");
                    if ("initialize".equals(method)) {
                        result.put("protocolVersion", "2025-11-25");
                        result.putObject("capabilities");
                        var info = result.putObject("serverInfo");
                        info.put("name", "fake"); info.put("version", "1.0");
                        return resp.setHeader("MCP-Session-Id", "sess-1")
                                .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}");
                    }
                    if ("notifications/initialized".equals(method)) {
                        return new MockResponse().setResponseCode(202);
                    }
                    if ("tools/list".equals(method)) {
                        return resp.setBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":["
                                + "{\"name\":\"poi_search\",\"description\":\"搜索兴趣点\","
                                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}}]}}");
                    }
                    if ("tools/call".equals(method)) {
                        return resp.setBody("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":["
                                + "{\"type\":\"text\",\"text\":\"找到 1 个结果：西湖\"}],\"isError\":false}}");
                    }
                    return resp.setBody("{\"jsonrpc\":\"2.0\",\"id\":-1,\"error\":{\"message\":\"no\"}}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        mcpServer.start();

        // 假 LLM：第 1 次调 poi_search，第 2 次最终文本
        llmServer = new MockWebServer();
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        llmServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int n = calls.accumulateAndGet(1, Integer::sum);
                try {
                    var body = MAPPER.readTree(request.getBody().readUtf8());
                    boolean hasTools = body.path("tools").isArray() && !body.path("tools").isEmpty();
                    if (n == 1 && hasTools) {
                        return new MockResponse().setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                                + "\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                                + "\"function\":{\"name\":\"poi_search\",\"arguments\":\"{\\\"query\\\":\\\"西湖\\\"}\"}}]}}]}");
                    }
                    return new MockResponse().setBody("{\"choices\":[{\"message\":{"
                            + "\"content\":\"已为您找到西湖附近的景点。\"}}]}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        llmServer.start();

        when(platform.fetchEnabled()).thenReturn(List.of(new SkillConfig(
                "amap-maps", "高德地图", "导航", mcpServer.url("/mcp").toString(),
                "", "", "", true, 1L)));

        // 用 @TestConfiguration 覆盖 llmProvider bean（见下方内部类）
    }

    @TestConfiguration
    static class TestCfg {
        // 通过静态字段拿到真实 provider 所需端点
    }
    // —— 实现说明：TestCfg 的 @Primary LlmProvider bean 用 @Autowired 注入
    //    McpSkillRegistry（容器内真实 bean）+ llmServer URL（实例字段无法进静态配置类——
    //    改为在 setUp 里把 llmServer.url 存进 static 字段，TestCfg 读取）构造
    //    DeepSeekLlmProvider(new OkHttpClient(), "k", LLM_ENDPOINT, recorder,
    //        (ToolProvider) () -> registry.enabledToolSpecs(), 5_000,
    //        (name, args) -> registry.callTool(name, args))，并 @Primary。
}
```
（**实现注意**：`@TestConfiguration` 静态类拿 `llmServer.url()` 需要静态字段桥接（`static String LLM_ENDPOINT`，`@BeforeEach` 赋值）；或者更简单——**不用 @TestConfiguration**，直接在 setUp 里手动装配一个真实 `DeepSeekLlmProvider` 并 `mock(llm)` 的 `chat` 委托不可行（llm bean 是接口）。最干净方案：`@MockBean LlmProvider llm` 的 default answer 改为 `thenAnswer(inv -> realProvider.chat(...))`——委托真实实现。若委托方式与 Mockito 泛型冲突，退回 @TestConfiguration + static 端点桥接。测试断言：WS 收到 reply 含"已为您找到西湖附近的景点"，且 mcpServer 的 tools/call 被调过 1 次（Dispatcher 里 callCount）。WS 探针代码复用 MultiDeviceGatewayTest 的客户端片段（hello→audio_start→PCM→audio_end→等 reply）。PCM 用任意字节数组（ASR 是 mock）。）

- [ ] **Step 2: 运行确认失败**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :app:test`
Expected: FAIL（编译错：`AutovoiceProperties` 无 `skillManager()`；beans 不存在）。

- [ ] **Step 3: 写实现**

`app/build.gradle.kts` 依赖加：`implementation(project(":skill-mcp"))`。

`application.yml` 的 `autovoice:` 下加：
```yaml
  skill-manager:
    url: ${SKILL_MANAGER_URL:}
    service-token: ${SKILL_SERVICE_TOKEN:}
    poll-ms: ${SKILL_MANAGER_POLL_MS:600000}
```

`AppConfig.java`：
- `AutovoiceProperties` record 加一个组件：`record AutovoiceProperties(Arbitration arbitration, Providers providers, Secrets secrets, Offline offline, Tts tts, Gateway gateway, SkillManager skillManager)`，紧凑构造器 `skillManager == null → new SkillManager("", "", 600_000)`；`record SkillManager(String url, String serviceToken, long pollMs) { 构造器：url/serviceToken 空→""，pollMs<1→600_000 }`。
- 新 beans（在 llmProvider 前后任意位置，保持类内方法顺序）：
  ```java
  @Bean
  public SkillPlatformClient skillPlatformClient(OkHttpClient client, AutovoiceProperties props) {
      AutovoiceProperties.SkillManager sm = props.skillManager();
      return new SkillPlatformClient(client, sm.url(), sm.serviceToken());
  }

  @Bean
  public McpSkillRegistry mcpSkillRegistry(SkillPlatformClient platformClient, AutovoiceProperties props) {
      // 注入策略按"当前启用工具总数"动态选择（≤8 direct / >8 direct+warn，selector 预留）
      ToolInjector dynamic = all -> ToolInjectors.forCount(all.size()).inject(all);
      McpSkillRegistry registry = new McpSkillRegistry(platformClient, dynamic,
              props.skillManager().pollMs(), 5_000,
              McpToolSession::connect);
      registry.start();
      return registry;
  }
  ```
- `llmProvider` bean 改为 7 参：
  ```java
  @Bean
  public LlmProvider llmProvider(OkHttpClient client, AutovoiceProperties props,
                                 TelemetryRecorder recorder, McpSkillRegistry registry) {
      if (!"deepseek".equals(props.providers().llm())) {
          throw new IllegalArgumentException("unknown providers.llm: " + props.providers().llm() + " (deepseek)");
      }
      ToolProvider merged = () -> {
          List<FunctionTool> out = new ArrayList<>(DeepSeekLlmProvider.defaultTools());
          out.addAll(registry.enabledToolSpecs());
          return out;
      };
      return new DeepSeekLlmProvider(client, props.secrets().deepseekApiKey(),
              DeepSeekLlmProvider.DEFAULT_ENDPOINT, recorder, merged,
              DeepSeekLlmProvider.DEFAULT_TOOL_LOOP_BUDGET_MS,
              new com.autovoice.server.skillmcp.McpToolExecutor(registry::callTool));
  }
  ```
- `skillRefreshController` bean：
  ```java
  @Bean
  public SkillRefreshController skillRefreshController(McpSkillRegistry registry, AutovoiceProperties props) {
      return new SkillRefreshController(registry, props.skillManager().serviceToken());
  }
  ```

`SkillRefreshController.java`：
```java
package com.autovoice.server.app;

import com.autovoice.server.skillmcp.McpSkillRegistry;
import com.autovoice.server.skillmcp.SkillPlatformClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 平台 webhook 接收端：X-Skill-Service-Token 校验后触发 registry 重拉。 */
@RestController
@RequestMapping("/api/internal/skills")
public class SkillRefreshController {

    private final McpSkillRegistry registry;
    private final String serviceToken;

    public SkillRefreshController(McpSkillRegistry registry, String serviceToken) {
        this.registry = registry;
        this.serviceToken = serviceToken == null ? "" : serviceToken;
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        String given = request.getHeader(SkillPlatformClient.SERVICE_TOKEN_HEADER);
        boolean ok = given != null && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), serviceToken.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            return ResponseEntity.status(401).body("{\"error\":\"unauthorized\"}");
        }
        registry.refreshAsync();
        return ResponseEntity.ok("{\"status\":\"ok\"}");
    }
}
```
（注意 `SkillPlatformClient.SERVICE_TOKEN_HEADER` 是 package-private static——skill-mcp 里改为 **public** static final，或在本类直接写字面量 `"X-Skill-Service-Token"`。采用：Task 3 的 `SERVICE_TOKEN_HEADER` 改 `public`。）

- [ ] **Step 4: 运行确认通过**

Run: `cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :app:test`
Expected: PASS（Task 10 全部 + 现有 app 用例含 EndToEnd/MultiDevice/AppConfig 全绿）。

- [ ] **Step 5: 提交**

```bash
git -C /Users/michaelliu/code/AutoVoice add AutoVoiceServer/app/src AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/SkillPlatformClient.java
git -C /Users/michaelliu/code/AutoVoice commit -m "feat(app): skill-mcp 装配（registry/工具合并/多轮 provider）+ webhook 端点 + E2E"
```

---

### Task 11: skill-manager-web 管理前端

**Files:**
- Create: `AutoVoiceServer/skill-manager-web/package.json`
- Create: `AutoVoiceServer/skill-manager-web/vite.config.ts`
- Create: `AutoVoiceServer/skill-manager-web/tsconfig.json`
- Create: `AutoVoiceServer/skill-manager-web/index.html`
- Create: `AutoVoiceServer/skill-manager-web/.gitignore`
- Create: `AutoVoiceServer/skill-manager-web/src/main.tsx`
- Create: `AutoVoiceServer/skill-manager-web/src/types.ts`
- Create: `AutoVoiceServer/skill-manager-web/src/api.ts`
- Create: `AutoVoiceServer/skill-manager-web/src/App.tsx`
- Create: `AutoVoiceServer/skill-manager-web/src/index.css`

**Interfaces:**
- Consumes: Task 7/8 的 API（`GET /api/skills`（掩码）、`POST /api/skills`、`PUT /api/skills/{id}`、`PATCH /api/skills/{id}/enabled`、`DELETE /api/skills/{id}`、`POST /api/skills/{id}/discover`、`POST /api/admin/login`）。
- Produces: 前端构建产物进 `skill-manager/src/main/resources/static/skill-manager/`（随平台 jar 托管于 `/skill-manager/` 路径）。

- [ ] **Step 1: 脚手架文件**

`package.json`（镜像 telemetry-web，名字改 skill-manager-web）：
```json
{
  "name": "skill-manager-web",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1"
  },
  "devDependencies": {
    "@vitejs/plugin-react": "^4.3.1",
    "typescript": "^5.5.3",
    "vite": "^5.4.0"
  }
}
```

`vite.config.ts`（outDir 指 skill-manager 模块；dev proxy 8083）：
```typescript
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// 产物由 Spring Boot 托管在 /skill-manager/ 子路径，必须相对引用
export default defineConfig({
  base: './',
  plugins: [react()],
  server: {
    proxy: { '/api': 'http://127.0.0.1:8083' },
  },
  build: {
    outDir: '../skill-manager/src/main/resources/static/skill-manager',
    emptyOutDir: true,
  },
});
```

`tsconfig.json`（镜像 telemetry-web 的 strict 配置）：
```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true
  },
  "include": ["src"]
}
```

`index.html`：
```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Skill 管理平台</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

`.gitignore`：
```
node_modules/
dist/
```

- [ ] **Step 2: 类型与 API 封装**

`src/types.ts`：
```typescript
export interface Skill {
  id: string;
  name: string;
  description: string;
  mcpUrl: string;
  authHeader: string;
  authValue: string; // 管理端视图为 "****"
  toolsJson: string;
  enabled: boolean;
  updatedAt: number;
}

export interface ToolInfo {
  name: string;
  description: string;
}

export interface SkillDraft {
  id: string;
  name: string;
  description: string;
  mcpUrl: string;
  authHeader: string;
  authValue: string;
  toolsJson: string;
  enabled: boolean;
}
```

`src/api.ts`（原生 fetch 封装，镜像 telemetry-web 风格）：
```typescript
import type { Skill, ToolInfo } from './types';

async function req(path: string, init?: RequestInit): Promise<any> {
  const resp = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
  if (resp.status === 401) {
    throw new Error('unauthorized');
  }
  if (!resp.ok) {
    throw new Error(`http ${resp.status}`);
  }
  if (resp.status === 204) return null;
  return resp.json();
}

export async function login(password: string): Promise<void> {
  await req('/api/admin/login', { method: 'POST', body: JSON.stringify({ password }) });
}

export async function listSkills(): Promise<Skill[]> {
  return req('/api/skills');
}

export async function createSkill(s: SkillDraft): Promise<Skill> {
  return req('/api/skills', { method: 'POST', body: JSON.stringify(s) });
}

export async function updateSkill(id: string, s: SkillDraft): Promise<Skill> {
  return req(`/api/skills/${id}`, { method: 'PUT', body: JSON.stringify(s) });
}

export async function setEnabled(id: string, enabled: boolean): Promise<Skill> {
  return req(`/api/skills/${id}/enabled`, { method: 'PATCH', body: JSON.stringify({ enabled }) });
}

export async function deleteSkill(id: string): Promise<void> {
  await req(`/api/skills/${id}`, { method: 'DELETE' });
}

export async function discoverTools(id: string, draft: SkillDraft): Promise<ToolInfo[]> {
  return req(`/api/skills/${id}/discover`, {
    method: 'POST',
    body: JSON.stringify({ mcpUrl: draft.mcpUrl, authHeader: draft.authHeader, authValue: draft.authValue }),
  });
}
```

- [ ] **Step 3: 主界面**

`src/main.tsx`（镜像 telemetry-web：createRoot + StrictMode + index.css）。

`src/App.tsx`（登录门 + 列表 + 编辑表单 + 发现工具勾选；单页无 router）：
```tsx
import { useEffect, useState } from 'react';
import * as api from './api';
import type { Skill, SkillDraft, ToolInfo } from './types';

interface FormState extends SkillDraft {
  tools: ToolInfo[];
  checked: Record<string, boolean>;
}

const emptyForm = (): FormState => ({
  id: '', name: '', description: '', mcpUrl: '', authHeader: '', authValue: '', toolsJson: '[]',
  enabled: true, tools: [], checked: {},
});

export default function App() {
  const [authed, setAuthed] = useState<boolean>(() => localStorage.getItem('skill-authed') === '1');
  const [password, setPassword] = useState('');
  const [skills, setSkills] = useState<Skill[]>([]);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm());
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');

  async function load() {
    try {
      setSkills(await api.listSkills());
    } catch (e: any) {
      if (e.message === 'unauthorized') {
        setAuthed(false);
        localStorage.removeItem('skill-authed');
      } else {
        setErr(String(e.message || e));
      }
    }
  }

  useEffect(() => {
    if (authed) load();
  }, [authed]);

  async function doLogin() {
    try {
      await api.login(password);
      localStorage.setItem('skill-authed', '1');
      setAuthed(true);
      setErr('');
    } catch (e) {
      setErr('口令错误');
    }
  }

  async function doDiscover() {
    if (!editingId) return;
    setMsg(''); setErr('');
    try {
      const tools = await api.discoverTools(editingId, form);
      const checked: Record<string, boolean> = {};
      tools.forEach((t) => (checked[t.name] = true));
      setForm({ ...form, tools, checked });
    } catch (e) {
      setErr('发现工具失败（MCP server 不可达？）');
    }
  }

  function buildDraft(): SkillDraft {
    const enabledTools = Object.entries(form.checked)
      .filter(([, v]) => v)
      .map(([name]) => ({ name, enabled: true }));
    return {
      id: form.id, name: form.name, description: form.description,
      mcpUrl: form.mcpUrl, authHeader: form.authHeader, authValue: form.authValue,
      toolsJson: JSON.stringify(enabledTools), enabled: form.enabled,
    };
  }

  async function doSave() {
    setMsg(''); setErr('');
    try {
      if (editingId) {
        await api.updateSkill(editingId, buildDraft());
        setMsg('已保存');
      } else {
        await api.createSkill(buildDraft());
        setMsg('已创建');
      }
      setEditingId(null);
      setForm(emptyForm());
      load();
    } catch (e: any) {
      setErr(e.message === 'http 409' ? 'skill id 已存在' : String(e.message || e));
    }
  }

  async function doToggle(s: Skill) {
    await api.setEnabled(s.id, !s.enabled);
    load();
  }

  async function doDelete(s: Skill) {
    if (!confirm(`删除 skill ${s.id}？`)) return;
    await api.deleteSkill(s.id);
    if (editingId === s.id) { setEditingId(null); setForm(emptyForm()); }
    load();
  }

  function edit(s: Skill) {
    const checked: Record<string, boolean> = {};
    try {
      const arr = JSON.parse(s.toolsJson) as { name: string; enabled: boolean }[];
      arr.forEach((t) => (checked[t.name] = true));
    } catch { /* 忽略非法清单 */ }
    setEditingId(s.id);
    setForm({
      id: s.id, name: s.name, description: s.description, mcpUrl: s.mcpUrl,
      authHeader: s.authHeader, authValue: '', toolsJson: s.toolsJson,
      enabled: s.enabled, tools: [], checked,
    });
  }

  if (!authed) {
    return (
      <div className="login">
        <h1>Skill 管理平台</h1>
        <input type="password" placeholder="管理口令" value={password}
               onChange={(e) => setPassword(e.target.value)} />
        <button onClick={doLogin}>登录</button>
        {err && <p className="err">{err}</p>}
      </div>
    );
  }

  return (
    <div className="app">
      <div className="topbar">
        <h1>Skill 管理平台</h1>
        <button onClick={() => { setAuthed(false); localStorage.removeItem('skill-authed'); }}>退出</button>
      </div>
      <div className="main">
        <div className="list-pane">
          {skills.map((s) => (
            <div key={s.id} className={`row ${editingId === s.id ? 'sel' : ''}`} onClick={() => edit(s)}>
              <span className="name">{s.name}</span>
              <span className="desc">{s.description || s.id}</span>
              <span className={`badge ${s.enabled ? 'on' : 'off'}`}>{s.enabled ? '启用' : '禁用'}</span>
              <button onClick={(e) => { e.stopPropagation(); doToggle(s); }}>{s.enabled ? '禁用' : '启用'}</button>
              <button onClick={(e) => { e.stopPropagation(); doDelete(s); }}>删除</button>
            </div>
          ))}
          <button className="new" onClick={() => { setEditingId(null); setForm(emptyForm()); }}>+ 新建 skill</button>
        </div>
        <div className="form-pane">
          <h2>{editingId ? `编辑 ${editingId}` : '新建 skill'}</h2>
          <label>id<input value={form.id} disabled={!!editingId}
                 onChange={(e) => setForm({ ...form, id: e.target.value })} /></label>
          <label>名称<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
          <label>描述（面向 LLM）<textarea value={form.description}
                 onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
          <label>MCP 地址<input value={form.mcpUrl} placeholder="https://mcp.example.com/mcp"
                 onChange={(e) => setForm({ ...form, mcpUrl: e.target.value })} /></label>
          <label>认证头名<input value={form.authHeader} placeholder="x-api-key（可留空）"
                 onChange={(e) => setForm({ ...form, authHeader: e.target.value })} /></label>
          <label>认证头值<input type="password" value={form.authValue}
                 placeholder={editingId ? '留空保持不变' : ''}
                 onChange={(e) => setForm({ ...form, authValue: e.target.value })} /></label>
          <label>启用<input type="checkbox" checked={form.enabled}
                 onChange={(e) => setForm({ ...form, enabled: e.target.checked })} /></label>
          <button onClick={doDiscover} disabled={!editingId || !form.mcpUrl}>发现工具</button>
          {form.tools.length > 0 && (
            <div className="tools">
              {form.tools.map((t) => (
                <label key={t.name}>
                  <input type="checkbox" checked={!!form.checked[t.name]}
                         onChange={(e) => setForm({ ...form, checked: { ...form.checked, [t.name]: e.target.checked } })} />
                  {t.name} <span className="tool-desc">{t.description}</span>
                </label>
              ))}
            </div>
          )}
          <div className="actions">
            <button className="save" onClick={doSave}>{editingId ? '保存' : '创建'}</button>
            {msg && <span className="msg">{msg}</span>}
            {err && <span className="err">{err}</span>}
          </div>
        </div>
      </div>
    </div>
  );
}
```

`src/index.css`（完整文件；纯 CSS + :root 变量，镜像 telemetry-web 风格）：
```css
/* Skill 管理平台样式：单文件手写 CSS，变量在 :root 定义 */
:root {
  --bg: #f5f6f8;
  --panel: #ffffff;
  --border: #e2e4e8;
  --text: #1f2329;
  --text-dim: #6b7280;
  --primary: #2f6fed;
  --primary-hover: #2456c8;
  --danger: #d93026;
  --ok: #0a7d33;
  --radius: 8px;
}

* { box-sizing: border-box; }

body {
  margin: 0;
  background: var(--bg);
  color: var(--text);
  font-family: system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 14px;
}

h1 { font-size: 18px; margin: 0; }
h2 { font-size: 16px; margin: 0 0 16px; }

button {
  padding: 6px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--panel);
  cursor: pointer;
  font-size: 13px;
}
button:hover { border-color: var(--primary); color: var(--primary); }
button.save { background: var(--primary); border-color: var(--primary); color: #fff; }
button.save:hover { background: var(--primary-hover); color: #fff; }
button.new { margin-top: 12px; width: 100%; }

input, textarea {
  width: 100%;
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  font-size: 13px;
  font-family: inherit;
}
input:focus, textarea:focus { outline: none; border-color: var(--primary); }
textarea { min-height: 56px; resize: vertical; }
input[type="checkbox"] { width: auto; }

label { display: block; margin-bottom: 10px; font-size: 13px; color: var(--text-dim); }
label > input, label > textarea { margin-top: 4px; color: var(--text); }

.err { color: var(--danger); margin: 8px 0; }
.msg { color: var(--ok); margin: 8px 0; }

/* 登录页 */
.login {
  max-width: 320px;
  margin: 15vh auto 0;
  padding: 32px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 12px;
  text-align: center;
}
.login h1 { margin-bottom: 20px; }
.login button { width: 100%; margin-top: 10px; }

/* 主布局：顶部栏 + 左右两栏 */
.app { max-width: 1100px; margin: 0 auto; padding: 16px; }

.topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 12px;
  margin-bottom: 16px;
}

.main { display: flex; gap: 16px; align-items: flex-start; }

/* 左侧 skill 列表 */
.list-pane {
  flex: 0 0 420px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 12px;
}
.row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 8px;
  border-radius: var(--radius);
  cursor: pointer;
}
.row:hover { background: var(--bg); }
.row.sel { background: #eaf1fe; }
.row .name { font-weight: 600; min-width: 90px; }
.row .desc { flex: 1; color: var(--text-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.badge {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 12px;
  white-space: nowrap;
}
.badge.on { background: #e5f6ec; color: var(--ok); }
.badge.off { background: #fdeeee; color: var(--danger); }

/* 右侧编辑表单 */
.form-pane {
  flex: 1;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
}
.form-pane button { margin-top: 8px; }
.tools { margin-top: 12px; border-top: 1px solid var(--border); padding-top: 10px; }
.tools label { display: flex; align-items: baseline; gap: 6px; }
.tool-desc { color: var(--text-dim); font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.actions { margin-top: 16px; display: flex; align-items: center; gap: 12px; }
```

- [ ] **Step 4: 构建并验证**

```bash
cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer/skill-manager-web
npm install
npm run build
```
Expected: tsc 零错误；产物出现在 `skill-manager/src/main/resources/static/skill-manager/{index.html,assets/}`。

```bash
cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew :skill-manager:test
```
Expected: PASS（后端不受影响）。手动冒烟（可选）：`./gradlew :skill-manager:bootRun` 后浏览器开 `http://127.0.0.1:8083/skill-manager/` 应出登录页（**注意**：子路径 `/skill-manager/` 与 telemetry 同样的限制——直接访问根路径不会自动 index，按 `/skill-manager/index.html` 访问）。

- [ ] **Step 5: 提交**

```bash
git -C /Users/michaelliu/code/AutoVoice add AutoVoiceServer/skill-manager-web AutoVoiceServer/skill-manager/src/main/resources/static
git -C /Users/michaelliu/code/AutoVoice commit -m "feat(skill-manager-web): 管理前端（登录/列表/编辑/发现工具勾选）"
```

---

### Task 12: 部署文档与验证清单（runbook）

**Files:**
- Modify: `AutoVoiceServer/docs/runbook.md`
- Create: `AutoVoiceServer/docs/skill-mcp-deploy.md`

**Interfaces:** 无（纯文档）。

- [ ] **Step 1: 写部署文档**

`AutoVoiceServer/docs/skill-mcp-deploy.md`（新文档，供运维侧执行；内容含）：

1. **构建**：
   ```bash
   cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer
   ./gradlew :app:bootJar :skill-manager:bootJar   # 产出 app/build/libs/app-*.jar、skill-manager/build/libs/skill-manager-*.jar
   ```
2. **平台部署**（阿里云 47.94.4.204）：
   - 目录 `/opt/autovoice/skill-manager/`，SQLite 落 `/opt/autovoice/skill-manager/skill-manager.db`。
   - systemd 服务 `autovoice-skill-manager.service`（镜像 autovoice-gateway.service 结构）：
     ```ini
     [Unit]
     Description=AutoVoice skill manager platform
     After=network.target

     [Service]
     User=root
     EnvironmentFile=/etc/autovoice/.env
     ExecStart=/usr/bin/java -jar /opt/autovoice/skill-manager/skill-manager.jar
     Restart=always
     RestartSec=5

     [Install]
     WantedBy=multi-user.target
     ```
   - `/etc/autovoice/.env` 追加：
     ```
     SKILL_MANAGER_PORT=8083
     SKILL_MANAGER_DB=/opt/autovoice/skill-manager/skill-manager.db
     SKILL_MANAGER_ADMIN_TOKEN=<平台管理口令>
     SKILL_SERVICE_TOKEN=<与网关一致的内部 token>
     SKILL_MANAGER_GATEWAY_WEBHOOK_URL=http://127.0.0.1:8080/api/internal/skills/refresh
     ```
3. **网关侧**：`/etc/autovoice/.env` 追加：
   ```
   SKILL_MANAGER_URL=http://127.0.0.1:8083
   SKILL_SERVICE_TOKEN=<同平台>
   SKILL_MANAGER_POLL_MS=600000
   ```
   重启 `systemctl restart autovoice-gateway`。
4. **高德 MCP 示例**：平台里新建 skill：mcpUrl `https://mcp.amap.com/mcp`（示例地址，以高德开放平台实际文档为准），authHeader `x-api-key`，authValue=高德 Web 服务 API key；发现工具后勾选 poi_search 等；启用。
5. **验证清单**（对应 spec §9/§10）：
   - 平台 API：`curl -H 'X-Skill-Service-Token: <token>' http://127.0.0.1:8083/api/skills?enabled=true` 返回启用 skill 明文。
   - 网关拉取：网关日志出现 `skill registry refreshed: N sessions`。
   - webhook：平台改 skill 后网关日志立即出现重拉（无需等 10min）。
   - 降级：`systemctl stop autovoice-skill-manager` → 网关日志 `skill platform pull failed, keep N sessions`，链路不崩；重启平台后恢复。
   - E2E 真机/探针：说话"导航去西湖" → 网关 LLM 多轮调 MCP → 播报 POI 结果；命令词"打开空调"仍离线秒回（回归）。
   - 前端：浏览器 `http://47.94.4.204:8083/skill-manager/index.html` 登录 → 新建/发现/勾选/启用。
6. **风险/已知限制**：MCP SDK 2.0.0 对应 MCP spec 2025-11-25，若目标 MCP server 实现更新的 spec（2026-07-28）需验证兼容；selector 分级未实现（>8 工具全量注入，告警日志）；空 MCP server（tools 为空）→ 该 skill 不注入工具但 skill 保持启用。

`AutoVoiceServer/docs/runbook.md`：在部署章节加一节"skill 管理平台（可选组件）"指向 `skill-mcp-deploy.md`，并列出 3 个新 env（`SKILL_MANAGER_URL`/`SKILL_SERVICE_TOKEN`/`SKILL_MANAGER_POLL_MS`）与平台 5 个 env（`SKILL_MANAGER_PORT`/`SKILL_MANAGER_DB`/`SKILL_MANAGER_ADMIN_TOKEN`/`SKILL_SERVICE_TOKEN`/`SKILL_MANAGER_GATEWAY_WEBHOOK_URL`）；说明未配置 `SKILL_MANAGER_URL` 时功能关闭（仅 car_control）。

- [ ] **Step 2: 全量回归**

```bash
cd /Users/michaelliu/code/AutoVoice/AutoVoiceServer && ./gradlew test
```
Expected: 全部模块测试绿（contracts/skill-mcp/skill-manager/llm/app/gateway/…）。

- [ ] **Step 3: 提交**

```bash
git -C /Users/michaelliu/code/AutoVoice add AutoVoiceServer/docs
git -C /Users/michaelliu/code/AutoVoice commit -m "docs: skill 管理平台部署文档 + runbook 章节"
```

---

## Self-Review 结果（写完后自查）

- **Spec 覆盖**：§3 数据模型 → Task 6（表结构逐字段对应）；§4 API（CRUD/discover/enabled/网关拉取/鉴权）→ Task 7/8；§5.1 配置同步（启动拉取/webhook/10min 轮询/平台不可达保留快照）→ Task 5 + Task 10；§5.2 MCP 客户端与发现 → Task 4；§5.3 分级注入 → Task 2；§6 多轮循环与预算 → Task 9；§7 仲裁协调（零改动）→ Task 10 装配不碰 RaceArbiter；§8 安全（掩码/留空不变/ServiceToken/管理口令/工具结果不可信——tool_result 以普通 tool 消息进 messages，不拼接 system 提示）→ Task 7/9/10；§9 测试矩阵 → 各任务测试 + Task 10 E2E + Task 12 验证清单；§10 部署 → Task 12。
- **占位符扫描**：无 TBD/TODO；Task 10 的 E2E 测试文件含"实现说明"替代具体代码的部分（@TestConfiguration 与 @MockBean 委托二选一）——这是有意的弹性设计（两种方案都给出），执行时选一即可。
- **类型一致性**：`SkillConfig`（网关侧）与 `SkillRecord`（平台侧）字段一致（id/name/description/mcpUrl/authHeader/authValue/toolsJson/enabled/updatedAt）；`FunctionTool(name, description, parametersJson)` 全计划一致；`ToolProvider.enabledTools()` / `ToolExecutor.execute(name, args)` 签名跨任务一致；`McpSkillRegistry.enabledToolSpecs()`/`callTool(name, args)` 在 Task 5 定义、Task 10 消费一致；`SkillWebhookNotifier.notifySkillChanged(String)` 跨 Task 7/8 一致。
