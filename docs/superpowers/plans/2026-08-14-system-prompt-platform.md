# system prompt 平台化配置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `DeepSeekLlmProvider` 的 system prompt 从硬编码改为通过 skill 管理平台（`/api/config/system-prompt`）配置，运行时热更新立即生效。

**Architecture:** 平台新增 `settings` 表 + `ConfigController`（GET 双鉴权 / PUT admin-only，保存后触发 webhook）；网关 `SkillPlatformClient.fetchSystemPrompt()` 每次刷新顺带拉取，写入 `SystemPromptStore`（`AtomicReference`）；`DeepSeekLlmProvider` 第 8 参 `Supplier<String>` 运行时取值，空 → 回退内置默认。`/api/skills` 协议不动（老平台 404 → 网关 keep 默认，无缝降级）。

**Tech Stack:** Spring Boot 3 (Java 21, SQLite JDBC, okhttp3, MockMvc)、React 18 + Vite 5 + TS 5（前端，npm 用 npmmirror）、JUnit 5。

## Global Constraints

- 全模块测试回归基准：当前 224 测试全绿（13 模块）。
- 不新增第三方依赖：llm 模块只接受 JDK `java.util.function.Supplier`；前端不引新 npm 包。
- 鉴权防时序侧信道：`MessageDigest.isEqual` 比较 + **先拒空**（空 header/cookie 绝不通过）。
- 平台写操作（PUT config）后必须触发 webhook（`SkillWebhookNotifier.notifySkillChanged`），网关全量重拉。
- 注释与代码风格沿用现有中文注释习惯；提交信息前缀 `feat:` / `fix:` / `docs:`。
- 前端构建：`npm install --registry=https://registry.npmmirror.com --ignore-scripts --no-audit`（fsevents 避免 node-gyp 挂起）；`npm run build` 产物直出
  `skill-manager/src/main/resources/static/skill-manager/`（vite `outDir` 已配置），**产物必须 git add 提交**。
- 默认 prompt 文案不得改动（未配置时行为与现在完全一致）。

---

### Task 1: 平台存储层 — settings 表

**Files:**
- Modify: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/SqliteSkillStore.java`（init 加建表、加 getSetting/setSetting）
- Test: `AutoVoiceServer/skill-manager/src/test/java/com/autovoice/server/skillmanager/SqliteSkillStoreTest.java`

**Interfaces:**
- Consumes: 现有 `SqliteSkillStore(String dbPath)`、`init()`、短连接 + busy_timeout 模式（`connect()` private）。
- Produces:
  - `public java.util.Optional<String> getSetting(String key)` —— 未配置返回 `Optional.empty()`
  - `public void setSetting(String key, String value)` —— upsert，value 可为空串

- [ ] **Step 1: 写失败测试**（`SqliteSkillStoreTest` 追加两个用例，沿用该文件现有 @TempDir 风格——先读该文件确认 setup 形态）

```java
@Test
void settingUpsertAndGet() throws Exception {
    store.setSetting("system_prompt", "你是助手");
    assertEquals(Optional.of("你是助手"), store.getSetting("system_prompt"));
}

@Test
void settingAbsentReturnsEmpty() throws Exception {
    assertEquals(Optional.empty(), store.getSetting("system_prompt"));
}

@Test
void settingEmptyValueStored() throws Exception {
    store.setSetting("system_prompt", "");
    assertEquals(Optional.of(""), store.getSetting("system_prompt"));
}
```

- [ ] **Step 2: 跑测试确认失败**（`getSetting`/`setSetting` 不存在 → 编译失败）
- [ ] **Step 3: 实现**

`init()` 的 try 块内、skills 建表之后追加：

```java
st.execute("CREATE TABLE IF NOT EXISTS settings ("
        + "key TEXT PRIMARY KEY, value TEXT NOT NULL)");
```

类内新增（沿用 `findById` 的 try-with-resources 风格）：

```java
public Optional<String> getSetting(String key) {
    try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
            "SELECT value FROM settings WHERE key=?")) {
        ps.setString(1, key);
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(rs.getString("value")) : Optional.empty();
        }
    } catch (SQLException e) {
        throw new IllegalStateException("setting get failed: " + key, e);
    }
}

public void setSetting(String key, String value) {
    try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO settings (key, value) VALUES (?,?)"
                    + " ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
        ps.setString(1, key);
        ps.setString(2, value);
        ps.executeUpdate();
    } catch (SQLException e) {
        throw new IllegalStateException("setting set failed: " + key, e);
    }
}
```

import 增加 `java.util.Optional`。

- [ ] **Step 4: 跑测试通过**：`./gradlew :skill-manager:test --tests '*SqliteSkillStoreTest*'`
- [ ] **Step 5: Commit**：`git add` 两个文件 → `git commit -m "feat: skill 平台 settings 表（system prompt 存储）"`

---

### Task 2: 平台 API — ConfigController + ConfigService + 鉴权接线

**Files:**
- Create: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/ConfigService.java`
- Create: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/ConfigController.java`
- Modify: `AutoVoiceServer/skill-manager/src/main/java/com/autovoice/server/skillmanager/WebMvcConfig.java`（拦截器路径 + 两个 @Bean）
- Test: `AutoVoiceServer/skill-manager/src/test/java/com/autovoice/server/skillmanager/ConfigControllerTest.java`
- Test: `AutoVoiceServer/skill-manager/src/test/java/com/autovoice/server/skillmanager/ConfigServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `SqliteSkillStore.getSetting/setSetting`；现有 `SkillWebhookNotifier` 接口
  （`void notifySkillChanged(String skillId)`，bean 是 `SkillWebhookPublisher`）；`SkillProperties`
  （`adminToken()`）；`AdminController.COOKIE_NAME`（`"skill_admin"`）与 `AdminController.cookieValue(String)`（static）。
- Produces: HTTP 契约
  - `GET /api/config/system-prompt` → 200 `{"value":"..."}`（未配置 `{"value":""}`）；鉴权：admin cookie **或** X-Skill-Service-Token
  - `PUT /api/config/system-prompt` body `{"value":"..."}`（空串合法）→ 200 `{"value":"<保存后值>"}`；**仅 admin cookie**；
    service token → 401；保存后触发 `notifier.notifySkillChanged("system-prompt")`

- [ ] **Step 1: 写失败测试 `ConfigServiceTest`**（纯单元，fake store/notifier）

```java
package com.autovoice.server.skillmanager;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class ConfigServiceTest {

    /** 内存 fake store：只实现本任务用到的两个方法。 */
    private static final class FakeStore extends SqliteSkillStore {
        final Map<String, String> map = new HashMap<>();
        FakeStore() { super("/nonexistent.db"); }
        @Override public Optional<String> getSetting(String key) { return Optional.ofNullable(map.get(key)); }
        @Override public void setSetting(String key, String value) { map.put(key, value); }
    }

    private static final class FakeNotifier implements SkillWebhookNotifier {
        final List<String> calls = new ArrayList<>();
        @Override public void notifySkillChanged(String skillId) { calls.add(skillId); }
    }

    @Test
    void getReturnsEmptyStringWhenUnset() {
        assertEquals("", new ConfigService(new FakeStore(), new FakeNotifier()).getSystemPrompt());
    }

    @Test
    void setStoresAndNotifiesWebhook() {
        FakeStore store = new FakeStore();
        FakeNotifier notifier = new FakeNotifier();
        ConfigService svc = new ConfigService(store, notifier);
        svc.setSystemPrompt("你是助手");
        assertEquals("你是助手", svc.getSystemPrompt());
        assertEquals(List.of("system-prompt"), notifier.calls);
    }

    @Test
    void setEmptyStoresEmptyAndStillNotifies() {
        FakeStore store = new FakeStore();
        FakeNotifier notifier = new FakeNotifier();
        ConfigService svc = new ConfigService(store, notifier);
        svc.setSystemPrompt("");
        assertEquals("", svc.getSystemPrompt());
        assertEquals(1, notifier.calls.size());
    }
}
```

- [ ] **Step 2: 实现 `ConfigService`**（跑测试确认失败后再写）

```java
package com.autovoice.server.skillmanager;

/** 平台级配置：当前只有 system prompt；写操作后通知网关刷新（同 skill 变更语义）。 */
public final class ConfigService {

    static final String SYSTEM_PROMPT_KEY = "system_prompt";

    private final SqliteSkillStore store;
    private final SkillWebhookNotifier notifier;

    public ConfigService(SqliteSkillStore store, SkillWebhookNotifier notifier) {
        this.store = store;
        this.notifier = notifier;
    }

    /** 未配置返回空串（网关侧回退内置默认）。 */
    public String getSystemPrompt() {
        return store.getSetting(SYSTEM_PROMPT_KEY).orElse("");
    }

    /** 保存并触发网关刷新（空串合法 = 恢复默认）。 */
    public void setSystemPrompt(String value) {
        store.setSetting(SYSTEM_PROMPT_KEY, value);
        notifier.notifySkillChanged(SYSTEM_PROMPT_KEY);
    }
}
```

- [ ] **Step 3: 跑 `ConfigServiceTest` 通过**
- [ ] **Step 4: 写失败测试 `ConfigControllerTest`**（@SpringBootTest 风格照抄 `SkillControllerTest` 头部的 properties 块）

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

@SpringBootTest(properties = {
        "autovoice.skill-manager.db-path=${java.io.tmpdir}/skill-mgr-cfg-test-${random.uuid}.db",
        "autovoice.skill-manager.admin-token=admin-secret",
        "autovoice.skill-manager.service-token=svc-secret",
        "autovoice.skill-manager.gateway-webhook-url="})
@AutoConfigureMockMvc
class ConfigControllerTest {

    @Autowired MockMvc mvc;
    static final ObjectMapper MAPPER = new ObjectMapper();

    private String login() throws Exception {
        return mvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"admin-secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("skill_admin").getValue();
    }

    @Test
    void getRequiresAuth() throws Exception {
        mvc.perform(get("/api/config/system-prompt")).andExpect(status().isUnauthorized());
    }

    @Test
    void getWithServiceTokenOrAdminCookie() throws Exception {
        mvc.perform(get("/api/config/system-prompt")
                        .header("X-Skill-Service-Token", "svc-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(""));
        mvc.perform(get("/api/config/system-prompt")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", login())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(""));
    }

    @Test
    void putRequiresAdminCookieServiceTokenRejected() throws Exception {
        mvc.perform(put("/api/config/system-prompt")
                        .header("X-Skill-Service-Token", "svc-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putAndReadBack() throws Exception {
        String cookie = login();
        mvc.perform(put("/api/config/system-prompt")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", cookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"你是车载语音助手，说话简短。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("你是车载语音助手，说话简短。"));
        mvc.perform(get("/api/config/system-prompt")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", cookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("你是车载语音助手，说话简短。"));
    }

    @Test
    void putEmptyValueAllowed() throws Exception {
        String cookie = login();
        mvc.perform(put("/api/config/system-prompt")
                        .cookie(new jakarta.servlet.http.Cookie("skill_admin", cookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(""));
    }
}
```

- [ ] **Step 5: 实现 `ConfigController` + 接线**

`ConfigController.java`：

```java
package com.autovoice.server.skillmanager;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 平台级配置：system prompt。GET 供网关拉取（admin cookie 或 service token）；
 * PUT 仅管理端（admin cookie），保存后经 ConfigService 触发 webhook 推网关刷新。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService service;
    private final String adminToken;

    public ConfigController(ConfigService service, SkillProperties props) {
        this.service = service;
        this.adminToken = props.adminToken();
    }

    @GetMapping("/system-prompt")
    public Map<String, String> getSystemPrompt() {
        return Map.of("value", service.getSystemPrompt());
    }

    @PutMapping("/system-prompt")
    public ResponseEntity<?> putSystemPrompt(HttpServletRequest request,
                                             @RequestBody PromptRequest body) {
        // 写操作仅管理端：service token（内部网关 token）无写权
        if (!hasAdminCookie(request)) {
            return ResponseEntity.status(401).body("{\"error\":\"unauthorized\"}");
        }
        String value = body.value() == null ? "" : body.value();
        service.setSystemPrompt(value);
        return ResponseEntity.ok(Map.of("value", service.getSystemPrompt()));
    }

    public record PromptRequest(String value) {}

    private boolean hasAdminCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        String expected = AdminController.cookieValue(adminToken);
        for (Cookie c : cookies) {
            if (AdminController.COOKIE_NAME.equals(c.getName()) && c.getValue() != null
                    && !c.getValue().isEmpty()
                    && MessageDigest.isEqual(c.getValue().getBytes(StandardCharsets.UTF_8),
                                              expected.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }
}
```

`WebMvcConfig.java` 三处修改：
1. `addPathPatterns("/api/skills/**", "/api/admin/**", "/api/config/**")`（exclude 不变）
2. 追加 bean：

```java
@Bean
public ConfigService configService(SqliteSkillStore store, SkillWebhookNotifier notifier) {
    return new ConfigService(store, notifier);
}

@Bean
public ConfigController configController(ConfigService service, SkillProperties props) {
    return new ConfigController(service, props);
}
```

（`ConfigController` 是 `@RestController`，显式 @Bean 覆盖组件扫描 —— 与 `SkillRefreshController` 同款注释说明，因 ctor 需要 `SkillProperties`。）

- [ ] **Step 6: 跑测试通过**：`./gradlew :skill-manager:test --tests '*Config*Test'`
- [ ] **Step 7: Commit**：`git commit -m "feat: skill 平台 /api/config/system-prompt（GET 双鉴权 / PUT admin-only + webhook）"`

---

### Task 3: 网关客户端 — fetchSystemPrompt

**Files:**
- Modify: `AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/SkillPlatformClient.java`
- Test: `AutoVoiceServer/skill-mcp/src/test/java/com/autovoice/server/skillmcp/SkillPlatformClientTest.java`

**Interfaces:**
- Consumes: 现有 ctor `SkillPlatformClient(OkHttpClient, String baseUrl, String serviceToken)`、`isEnabled()`、`SERVICE_TOKEN_HEADER`。
- Produces: `public String fetchSystemPrompt()` —— 不可用（未接入/HTTP 非 2xx/解析失败）返回 **null**（不抛）；可用返回 `value` 字段文本。

- [ ] **Step 1: 写失败测试**（先读 `SkillPlatformClientTest` 现有 MockWebServer 写法，照抄 setup；追加用例）

```java
@Test
void fetchSystemPromptReturnsValue() throws Exception {
    server.enqueue(new okhttp3.mockwebserver.MockResponse()
            .setResponseCode(200)
            .setBody("{\"value\":\"你是助手\"}"));
    assertEquals("你是助手", client.fetchSystemPrompt());
}

@Test
void fetchSystemPromptNullWhenServerError() throws Exception {
    server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(500));
    assertNull(client.fetchSystemPrompt());
}

@Test
void fetchSystemPromptNullWhenNotEnabled() {
    SkillPlatformClient off = new SkillPlatformClient(
            new okhttp3.OkHttpClient(), "", "t");
    assertNull(off.fetchSystemPrompt());
}

@Test
void fetchSystemPromptNullWhenMalformedBody() throws Exception {
    server.enqueue(new okhttp3.mockwebserver.MockResponse()
            .setResponseCode(200)
            .setBody("not-json"));
    assertNull(client.fetchSystemPrompt());
}
```

- [ ] **Step 2: 实现**（追加在 `fetchEnabled()` 之后；`MAPPER.readTree` 已可用）

```java
/**
 * 拉取平台级 system prompt（GET /api/config/system-prompt）。
 * 不可用（未接入 / 非 2xx / 解析失败）返回 null —— 调用方保留现值，不抛。
 */
public String fetchSystemPrompt() {
    if (!isEnabled()) {
        return null;
    }
    try {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/config/system-prompt")
                .header(SERVICE_TOKEN_HEADER, serviceToken)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                return null;
            }
            String body = resp.body() == null ? "" : resp.body().string();
            return MAPPER.readTree(body).path("value").asText(null);
        }
    } catch (Exception e) {
        return null;
    }
}
```

- [ ] **Step 3: 跑测试通过**：`./gradlew :skill-mcp:test --tests '*SkillPlatformClientTest*'`
- [ ] **Step 4: Commit**：`git commit -m "feat: 网关拉取平台 system prompt（不可用返回 null 不抛）"`

---

### Task 4: 网关注册器 — SystemPromptStore + McpSkillRegistry 集成

**Files:**
- Create: `AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/SystemPromptStore.java`
- Modify: `AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/McpSkillRegistry.java`
- Test: `AutoVoiceServer/skill-mcp/src/test/java/com/autovoice/server/skillmcp/McpSkillRegistryTest.java`
- Test: `AutoVoiceServer/skill-mcp/src/test/java/com/autovoice/server/skillmcp/SystemPromptStoreTest.java`

**Interfaces:**
- Consumes: Task 3 的 `SkillPlatformClient.fetchSystemPrompt()`；现有 `McpSkillRegistry` ctor 5 参。
- Produces:
  - `public final class SystemPromptStore { public void set(String value); public String get(); }`
    —— `set(null)` 忽略；`get()` 返回 null = 未配置（回退由消费者决定）
  - `McpSkillRegistry` 新 ctor（6 参）：`(SkillPlatformClient client, ToolInjector injector, SystemPromptStore promptStore, long pollMs, long connectTimeoutMs, BiFunction<SkillConfig, Long, McpToolSession> sessionFactory)`
  - 行为：每次成功 refresh（fetchEnabled 不抛）后拉 prompt 并 set；prompt 变化时 INFO 日志
    `system prompt updated (N chars)`；拉取失败（null）→ 保留现值，不覆盖。

- [ ] **Step 1: 写失败测试 `SystemPromptStoreTest`**

```java
package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SystemPromptStoreTest {

    @Test
    void initialNull() {
        assertNull(new SystemPromptStore().get());
    }

    @Test
    void setThenGet() {
        SystemPromptStore store = new SystemPromptStore();
        store.set("你是助手");
        assertEquals("你是助手", store.get());
    }

    @Test
    void setNullIgnoredKeepsPrevious() {
        SystemPromptStore store = new SystemPromptStore();
        store.set("a");
        store.set(null);
        assertEquals("a", store.get());
    }

    @Test
    void emptyValueAllowed() {
        SystemPromptStore store = new SystemPromptStore();
        store.set("");
        assertEquals("", store.get());
    }
}
```

- [ ] **Step 2: 实现 `SystemPromptStore`**

```java
package com.autovoice.server.skillmcp;

import java.util.concurrent.atomic.AtomicReference;

/** 平台 system prompt 运行时可换引用；null = 未配置（由消费者回退默认）。线程安全。 */
public final class SystemPromptStore {

    private final AtomicReference<String> ref = new AtomicReference<>();

    /** 更新；null 忽略（拉取失败时不覆盖现值）。 */
    public void set(String value) {
        if (value != null) {
            ref.set(value);
        }
    }

    /** 当前值；null 表示从未配置成功。 */
    public String get() {
        return ref.get();
    }
}
```

- [ ] **Step 3: 跑 `SystemPromptStoreTest` 通过**
- [ ] **Step 4: 更新 `McpSkillRegistryTest`**（编译错误驱动：把每个 `new McpSkillRegistry(client, injector, ...)` 改为 6 参，第三个参数 `new SystemPromptStore()`）；追加用例：

```java
@Test
void refreshPullsSystemPrompt() throws Exception {
    SystemPromptStore store = new SystemPromptStore();
    FakePlatformClient client = new FakePlatformClient(List.of()) {
        @Override public String fetchSystemPrompt() { return "你是助手"; }
    };
    try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
            store, 60_000, 5_000, (c, timeout) -> session(c))) {
        reg.refresh();
        assertEquals("你是助手", store.get());
    }
}

@Test
void fetchPromptFailureKeepsPrevious() throws Exception {
    SystemPromptStore store = new SystemPromptStore();
    store.set("旧值");
    FakePlatformClient client = new FakePlatformClient(List.of()) {
        @Override public String fetchSystemPrompt() { return null; } // 拉取失败
    };
    try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
            store, 60_000, 5_000, (c, timeout) -> session(c))) {
        reg.refresh();
        assertEquals("旧值", store.get());
    }
}

@Test
void platformDownKeepsPrompt() throws Exception {
    SystemPromptStore store = new SystemPromptStore();
    store.set("旧值");
    FakePlatformClient client = new FakePlatformClient(null) {
        @Override public List<SkillConfig> fetchEnabled() throws IOException {
            throw new IOException("down");
        }
    };
    try (McpSkillRegistry reg = new McpSkillRegistry(client, new DirectToolInjector(),
            store, 60_000, 5_000, (c, timeout) -> session(c))) {
        reg.refresh();
        assertEquals("旧值", store.get());
    }
}
```

- [ ] **Step 5: 实现 `McpSkillRegistry` 改动**

ctor 加字段与参数（`SystemPromptStore promptStore` 放第 3 参）：

```java
private final SystemPromptStore promptStore;

public McpSkillRegistry(SkillPlatformClient client, ToolInjector injector,
                        SystemPromptStore promptStore, long pollMs, long connectTimeoutMs,
                        BiFunction<SkillConfig, Long, McpToolSession> sessionFactory) {
    this.client = client;
    this.injector = injector;
    this.promptStore = promptStore;
    this.pollMs = pollMs < 1 ? 600_000 : pollMs;
    this.connectTimeoutMs = connectTimeoutMs < 1 ? 5_000 : connectTimeoutMs;
    this.sessionFactory = sessionFactory;
}
```

`refreshInternal()` 末尾（`lastRefreshMs` 赋值之后、循环 close 之前）插入：

```java
String oldPrompt = promptStore.get();
String prompt = client.fetchSystemPrompt();
if (prompt != null && !prompt.equals(oldPrompt)) {
    promptStore.set(prompt);
    LOG.info("system prompt updated ({} chars)", prompt.length());
}
```

- [ ] **Step 6: 跑测试通过**：`./gradlew :skill-mcp:test`
- [ ] **Step 7: Commit**：`git commit -m "feat: 网关刷新拉取 system prompt 进 store（失败保留现值）"`

---

### Task 5: llm — DeepSeekLlmProvider Supplier 注入

**Files:**
- Modify: `AutoVoiceServer/llm/src/main/java/com/autovoice/server/llm/DeepSeekLlmProvider.java`
- Test: `AutoVoiceServer/llm/src/test/java/com/autovoice/server/llm/DeepSeekLlmProviderTest.java`

**Interfaces:**
- Consumes: 现有 7 参 ctor（AppConfig 用）；`SYSTEM_PROMPT` 常量。
- Produces:
  - `SYSTEM_PROMPT` 改名 `DEFAULT_SYSTEM_PROMPT`（文案不变）
  - 新 8 参 ctor：7 参末尾加 `Supplier<String> systemPrompt`（null = 回退默认）
  - `systemMessage()` 由 static 改实例方法：`supplier.get()`，null/空白 → `DEFAULT_SYSTEM_PROMPT`
  - 4 参单轮 ctor 委托 7 参不变（内部链到 8 参传 null）

- [ ] **Step 1: 读 `DeepSeekLlmProviderTest`** 找到 `assertEquals(DeepSeekLlmProvider.SYSTEM_PROMPT, ...)`（约 :85）改为 `DEFAULT_SYSTEM_PROMPT`（编译失败驱动改名）
- [ ] **Step 2: 写失败测试**（追加到 `DeepSeekLlmProviderTest`，沿用其 MockWebServer 装配——先读文件确认 fake client/recorder 构造方式）

```java
/** helper：最近一次请求的 system 消息 content（照抄现有 :85 的请求体解析写法）。 */
private String capturedSystemContent() throws Exception {
    okhttp3.mockwebserver.RecordedRequest req = server.takeRequest();
    JsonNode body = mapper.readTree(req.getBody().readUtf8());
    return body.path("messages").get(0).path("content").asText();
}

@Test
void customSystemPromptUsed() throws Exception {
    server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(fixture("deepseek-llm-reply.json")));
    String custom = "你是高冷助手。";
    DeepSeekLlmProvider p = new DeepSeekLlmProvider(new OkHttpClient(), API_KEY,
            server.url("/chat/completions").toString(), (utt, e) -> {}, null, 0, null, () -> custom);
    p.chat(USER_TEXT, ctx("s1")).get(5, TimeUnit.SECONDS);
    assertEquals(custom, capturedSystemContent());
}

@Test
void blankSupplierFallsBackToDefault() throws Exception {
    server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(fixture("deepseek-llm-reply.json")));
    DeepSeekLlmProvider p = new DeepSeekLlmProvider(new OkHttpClient(), API_KEY,
            server.url("/chat/completions").toString(), (utt, e) -> {}, null, 0, null, () -> "  ");
    p.chat(USER_TEXT, ctx("s1")).get(5, TimeUnit.SECONDS);
    assertEquals(DeepSeekLlmProvider.DEFAULT_SYSTEM_PROMPT, capturedSystemContent());
}

@Test
void nullSupplierFallsBackToDefault() throws Exception {
    server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(fixture("deepseek-llm-reply.json")));
    DeepSeekLlmProvider p = new DeepSeekLlmProvider(new OkHttpClient(), API_KEY,
            server.url("/chat/completions").toString(), (utt, e) -> {}, null, 0, null, null);
    p.chat(USER_TEXT, ctx("s1")).get(5, TimeUnit.SECONDS);
    assertEquals(DeepSeekLlmProvider.DEFAULT_SYSTEM_PROMPT, capturedSystemContent());
}
```

> 符号核对（已读源文件确认）：`ctx(String)` → `new SessionContext(id, "zh", Map.of())`（测试内 helper，:172）；
> recorder 用 lambda `(utt, e) -> {}`（:47 同款）；`fixture(...)` 为现有 helper；8 参 ctor 为本任务新增，
> 编译前最后一个 assertThrows 风格用例可先用 `DEFAULT_SYSTEM_PROMPT` 编译失败驱动改名。

- [ ] **Step 3: 实现**

1. `static final String SYSTEM_PROMPT =` → `static final String DEFAULT_SYSTEM_PROMPT =`（文案原样）
2. 字段加 `private final Supplier<String> systemPrompt;`（import `java.util.function.Supplier`）
3. 4 参 ctor 委托链不变；7 参 ctor 改为 8 参并在尾部加：

```java
public DeepSeekLlmProvider(OkHttpClient client, String apiKey, String endpoint, TelemetryRecorder recorder,
                           ToolProvider tools, long toolLoopBudgetMs, ToolExecutor executor,
                           Supplier<String> systemPrompt) {
    ...原 7 参体...
    this.systemPrompt = systemPrompt;
}
```

（若保留 7 参 ctor 供既有调用，则 7 参委托 8 参传 null —— 二选一，以「改动最小」为准：全仓 grep 7 参调用点，只有 AppConfig 一处 → **直接把 7 参改为 8 参**，AppConfig 由 Task 6 同步。）

4. `systemMessage()` 改实例方法：

```java
/** system 消息（OpenAI 兼容）；prompt 未配置（null/空白）回退内置默认。 */
private ObjectNode systemMessage() {
    String prompt = systemPrompt == null ? null : systemPrompt.get();
    if (prompt == null || prompt.isBlank()) {
        prompt = DEFAULT_SYSTEM_PROMPT;
    }
    ObjectNode m = MAPPER.createObjectNode();
    m.put("role", "system");
    m.put("content", prompt);
    return m;
}
```

- [ ] **Step 4: 跑测试通过**：`./gradlew :llm:test`
- [ ] **Step 5: Commit**：`git commit -m "feat: DeepSeekLlmProvider system prompt 改为 Supplier 注入（空回退默认）"`

---

### Task 6: app 装配 — AppConfig 两处 bean

**Files:**
- Modify: `AutoVoiceServer/app/src/main/java/com/autovoice/server/app/AppConfig.java`
- 回归: `AutoVoiceServer/app/src/test/java/com/autovoice/server/app/AppConfigSkillManagerTest.java`（不应改动；如编译失败按错误修）

**Interfaces:**
- Consumes: Task 4 的 `SystemPromptStore` + 新 `McpSkillRegistry` 6 参 ctor；Task 5 的 8 参 `DeepSeekLlmProvider` ctor。
- Produces: `SystemPromptStore` bean（供 registry 与 provider 共用，跨 bean 单例）；装配完成的 `mcpSkillRegistry` 与 `llmProvider`。

- [ ] **Step 1: 实现**（三处编辑）

1. 新 bean（加在 `mcpSkillRegistry` 前）：

```java
/** 平台 system prompt 运行时快照：registry 刷新写入，LLM 读取（未配置回退 provider 默认）。 */
@Bean
public SystemPromptStore systemPromptStore() {
    return new SystemPromptStore();
}
```

2. `mcpSkillRegistry` bean 签名加参 + ctor 传参：

```java
@Bean
public McpSkillRegistry mcpSkillRegistry(SkillPlatformClient platformClient,
                                         SystemPromptStore promptStore, AutovoiceProperties props) {
    ToolInjector dynamic = all -> ToolInjectors.forCount(all.size()).inject(all);
    McpSkillRegistry registry = new McpSkillRegistry(platformClient, dynamic, promptStore,
            props.skillManager().pollMs(), 5_000,
            (cfg, timeout) -> { ...原 lambda 不变... });
    registry.start();
    return registry;
}
```

3. `llmProvider` bean 签名加 `SystemPromptStore promptStore`，ctor 末尾加 `promptStore::get`：

```java
return new DeepSeekLlmProvider(client, props.secrets().deepseekApiKey(),
        DeepSeekLlmProvider.DEFAULT_ENDPOINT, recorder, merged,
        DeepSeekLlmProvider.DEFAULT_TOOL_LOOP_BUDGET_MS,
        new McpToolExecutor(registry::callTool), promptStore::get);
```

- [ ] **Step 2: 编译 + 跑 app 模块测试**：`./gradlew :app:test`（含 EndToEndGatewayTest、McpEndToEndGatewayTest —— 全绿即装配正确）
- [ ] **Step 3: Commit**：`git commit -m "feat: 网关装配 SystemPromptStore 打通平台 prompt 注入 LLM"`

---

### Task 7: 前端 — 系统提示词编辑面板

**Files:**
- Modify: `AutoVoiceServer/skill-manager-web/src/api.ts`
- Modify: `AutoVoiceServer/skill-manager-web/src/App.tsx`
- Modify: `AutoVoiceServer/skill-manager-web/src/index.css`
- 产物: `AutoVoiceServer/skill-manager/src/main/resources/static/skill-manager/`（vite outDir 直出，构建后 git add）

**Interfaces:**
- Consumes: Task 2 的 HTTP 契约（GET/PUT `/api/config/system-prompt`，`{"value":...}`；PUT 401 → `req()` 抛 `unauthorized`，走现有 setAuthed(false) 路径）。
- Produces: 平台 UI「系统提示词」面板（折叠）；保存/恢复默认按钮；登录后加载现值。

- [ ] **Step 1: api.ts 追加**（`req` 复用现有）

```ts
export async function getSystemPrompt(): Promise<string> {
  const r = await req('/api/config/system-prompt');
  return r ? (r.value ?? '') : '';
}

export async function setSystemPrompt(value: string): Promise<void> {
  await req('/api/config/system-prompt', { method: 'PUT', body: JSON.stringify({ value }) });
}
```

- [ ] **Step 2: App.tsx 追加**（3 处）

state（`err` 声明旁）：

```tsx
const [prompt, setPrompt] = useState('');
```

加载（`useEffect` 内 `load()` 旁）：

```tsx
async function loadPrompt() {
  try {
    setPrompt(await api.getSystemPrompt());
  } catch (e: any) {
    if (e.message === 'unauthorized') {
      setAuthed(false);
      localStorage.removeItem('skill-authed');
    } else {
      setErr(String(e.message || e));
    }
  }
}
// useEffect 内：if (authed) { load(); loadPrompt(); }
```

保存：

```tsx
async function doSavePrompt() {
  setMsg(''); setErr('');
  try {
    await api.setSystemPrompt(prompt);
    setMsg('系统提示词已保存（网关将热更新）');
    await loadPrompt();
  } catch (e: any) {
    if (e.message === 'unauthorized') {
      setAuthed(false);
      localStorage.removeItem('skill-authed');
    } else {
      setErr(String(e.message || e));
    }
  }
}

async function doResetPrompt() {
  setPrompt('');
  setMsg(''); setErr('');
  try {
    await api.setSystemPrompt('');
    setMsg('已恢复默认系统提示词');
  } catch (e: any) {
    if (e.message === 'unauthorized') {
      setAuthed(false);
      localStorage.removeItem('skill-authed');
    } else {
      setErr(String(e.message || e));
    }
  }
}
```

UI（`topbar` 与 `main` 之间插入）：

```tsx
<details className="prompt-pane">
  <summary>系统提示词（LLM system prompt，保存后网关热更新）</summary>
  <textarea value={prompt} rows={3}
            onChange={(e) => setPrompt(e.target.value)}
            placeholder="留空 = 使用内置默认提示词" />
  <div className="prompt-actions">
    <button onClick={doSavePrompt}>保存</button>
    <button onClick={doResetPrompt}>恢复默认</button>
  </div>
</details>
```

- [ ] **Step 3: index.css 追加**（读文件找风格一致的追加位置；居中/留白参照现有 `.actions` 布局）

```css
.prompt-pane {
  margin: 8px 12px;
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 6px;
  background: #fafafa;
}
.prompt-pane textarea {
  width: 100%;
  box-sizing: border-box;
  margin: 6px 0;
}
.prompt-actions button {
  margin-right: 8px;
}
```

- [ ] **Step 4: 安装依赖 + 构建**

```bash
cd AutoVoiceServer/skill-manager-web
npm install --registry=https://registry.npmmirror.com --ignore-scripts --no-audit
npm run build        # tsc && vite build → 直出 ../skill-manager/src/main/resources/static/skill-manager
```

- [ ] **Step 5: 验证产物含新 bundle**：`ls -la AutoVoiceServer/skill-manager/src/main/resources/static/skill-manager/assets/` 出现新 hash 的 js；`grep -c "prompt"` 产物 js 确认包含新逻辑
- [ ] **Step 6: Commit**（含全部构建产物）：

```bash
git add AutoVoiceServer/skill-manager-web/src AutoVoiceServer/skill-manager/src/main/resources/static/skill-manager
git commit -m "feat: 平台 UI 系统提示词编辑面板（保存/恢复默认 + 热更新提示）"
```

---

### Task 8: 文档 + 全量回归

**Files:**
- Modify: `docs/skill-mcp-deploy.md`
- Modify: `docs/runbook.md`（§1.8 skill 管理平台节）

- [ ] **Step 1: docs/skill-mcp-deploy.md 追加「系统提示词配置」节**（放在「2. 平台部署」与「3. 网关侧」之间或文末，内容）:

```markdown
## 系统提示词配置（/api/config/system-prompt）

LLM system prompt 由平台配置，网关热更新（改后立即生效，无需重启）。

- 管理界面「系统提示词」面板编辑保存（或 `PUT /api/config/system-prompt`，body `{"value":"..."}`，
  仅管理 cookie）；留空 = 恢复内置默认提示词。
- 网关每次刷新（webhook/轮询）拉取：`GET /api/config/system-prompt`（X-Skill-Service-Token）。
- 平台未部署或端点不可用 → 网关回退内置默认，链路不崩。
- 验证：改 prompt → 网关日志 `system prompt updated (N chars)`；对同一句话，回答风格随 prompt 变化。
```

- [ ] **Step 2: runbook §1.8 补一句**（定位「skill 管理平台（可选组件）」小节，追加）：

```markdown
- LLM system prompt 亦由平台配置（`/api/config/system-prompt`，管理界面「系统提示词」面板），
  网关热更新；未配置回退内置默认。
```

- [ ] **Step 3: 全量回归**：`cd AutoVoiceServer && ./gradlew test` —— 13 模块全绿（原 224 + 新增用例）
- [ ] **Step 4: Commit**：`git commit -m "docs: system prompt 平台化配置文档 + runbook 补充"`

---

## 自审记录

- **Spec 覆盖**：settings 表（T1）、ConfigController 双鉴权 + PUT admin-only + webhook（T2）、
  fetchSystemPrompt null 语义（T3）、SystemPromptStore + registry 集成 + 失败保留（T4）、
  Supplier 注入 + 空白回退（T5）、AppConfig 装配（T6）、前端面板（T7）、文档（T8）。
- **一致性与默认值**：`DEFAULT_SYSTEM_PROMPT` 命名全文一致；空值回退语义在 T2（ConfigService 允许空串）、
  T4（store 允许空串）、T5（provider 空白回退）三层一致；webhook 通知键统一 `"system-prompt"`。
- **向后兼容**：`/api/skills` 协议未动；老平台 404 → T3 返回 null → T4 保留现值 → 回退默认。
- **已知实现期抉择**（测试锁语义）：McpSkillRegistry ctor 参数顺序、DeepSeekLlmProvider 7 参改 8 参
  （而非保留 7 参重载）、ConfigController 显式 @Bean —— 若与既有测试结构冲突，以本计划签名与测试
  语义为准微调并保持测试绿。
