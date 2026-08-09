package com.autovoice.server.offlinecommand;

import com.autovoice.server.contracts.SessionContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NativeOfflineCommandProvider 的 JVM 侧测试：只覆盖「不加载 .so」的路径——
 * 构造不触 native、.so 缺失/加载失败 → Optional.empty 降级、失败态缓存不重试。
 * （真实 SDK 由用户服务器编译 + cn_test.pcm 冒烟验证，见 offline-command/native/README.md。）
 */
class NativeOfflineCommandProviderTest {

    /** 构造不得触碰 .so（懒加载关键性质：Mac 上可安全构造、可测降级）。 */
    @Test
    void constructorDoesNotTouchNativeLibrary() {
        NativeOfflineCommandProvider p = provider("/definitely/missing/autovoice_offline_esr.so");
        assertNotNull(p);
    }

    /** System.load 失败（.so 不存在）→ Optional.empty（等同未命中），绝不抛。 */
    @Test
    void recognizeWithMissingLibraryFailsEmpty() {
        NativeOfflineCommandProvider p = provider("/definitely/missing/autovoice_offline_esr.so");
        Optional<String> result = await(p.recognize(new byte[3200], ctx()));
        assertEquals(Optional.empty(), result);
    }

    /** 初始化失败态缓存：后续调用不再尝试加载、仍返回空结果（日志只记一次 error）。 */
    @Test
    void initFailureIsCachedAcrossRecognizeCalls() {
        NativeOfflineCommandProvider p = provider("/definitely/missing/autovoice_offline_esr.so");
        assertEquals(Optional.empty(), await(p.recognize(new byte[3200], ctx())));
        assertEquals(Optional.empty(), await(p.recognize(new byte[3200], ctx())));
    }

    /** 空 PCM 也走完整降级路径（空输入不特殊处理，不 NPE）。 */
    @Test
    void recognizeWithEmptyPcmFailsEmpty() {
        NativeOfflineCommandProvider p = provider("/definitely/missing/autovoice_offline_esr.so");
        assertEquals(Optional.empty(), await(p.recognize(new byte[0], ctx())));
    }

    private static NativeOfflineCommandProvider provider(String libPath) {
        return new NativeOfflineCommandProvider(libPath, "/work", "/resource",
                "/cn_fsa.txt", "", "appId", "key", "secret");
    }

    private static SessionContext ctx() {
        return new SessionContext("test-session", null, null);
    }

    private static Optional<String> await(CompletableFuture<Optional<String>> f) {
        return f.join();
    }
}
