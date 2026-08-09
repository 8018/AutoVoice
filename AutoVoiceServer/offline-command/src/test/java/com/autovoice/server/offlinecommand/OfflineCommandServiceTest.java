package com.autovoice.server.offlinecommand;

import com.autovoice.server.contracts.OfflineCommandHit;
import com.autovoice.server.contracts.OfflineCommandProvider;
import com.autovoice.server.contracts.SessionContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线命令编排测试：Provider 命中 → 规则映射；空/失败/unknown → 空结果（永不崩服务）。
 */
class OfflineCommandServiceTest {

    private final SessionContext ctx = new SessionContext("s1", "zh-CN", Map.of());

    private static OfflineCommandService service(OfflineCommandProvider provider) {
        return new OfflineCommandService(provider);
    }

    @Test
    void hitMapsThroughRuleNlu() {
        OfflineCommandService svc = service((pcm, c) -> completed("打开空调"));
        Optional<OfflineCommandHit> hit = svc.recognize(new byte[320], ctx).join();
        assertTrue(hit.isPresent());
        assertEquals("打开空调", hit.get().text());
        assertEquals("climate", hit.get().intent().domain());
        assertEquals("power_on", hit.get().intent().intent());
    }

    @Test
    void emptyResultIsMiss() {
        OfflineCommandService svc = service((pcm, c) -> completed(""));
        assertTrue(svc.recognize(new byte[320], ctx).join().isEmpty());
    }

    @Test
    void blankResultIsMiss() {
        OfflineCommandService svc = service((pcm, c) -> completed("   "));
        assertTrue(svc.recognize(new byte[320], ctx).join().isEmpty());
    }

    @Test
    void unknownIntentIsMiss() {
        // 识别到文本但规则未命中 → 拒识语义：不作为命中
        OfflineCommandService svc = service((pcm, c) -> completed("我想听周杰伦的专辑"));
        assertTrue(svc.recognize(new byte[320], ctx).join().isEmpty());
    }

    @Test
    void providerExceptionIsMiss() {
        OfflineCommandService svc = service((pcm, c) ->
                CompletableFuture.failedFuture(new RuntimeException("sdk boom")));
        assertTrue(svc.recognize(new byte[320], ctx).join().isEmpty());
    }

    @Test
    void providerSyncThrowIsMiss() {
        OfflineCommandService svc = service((pcm, c) -> {
            throw new IllegalStateException("init failed");
        });
        assertTrue(svc.recognize(new byte[320], ctx).join().isEmpty());
    }

    private static CompletableFuture<Optional<String>> completed(String text) {
        return CompletableFuture.completedFuture(Optional.ofNullable(text));
    }
}
