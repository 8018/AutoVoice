package com.autovoice.server.offlinecommand;

import com.autovoice.server.contracts.OfflineCommandProvider;
import com.autovoice.server.contracts.SessionContext;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 空实现：离线命令链未启用（offline.enabled=false）或引擎初始化失败时的降级 Provider——
 * 永远"未命中"，云端链路行为与改造前完全一致。
 */
public final class NoopOfflineCommandProvider implements OfflineCommandProvider {

    @Override
    public CompletableFuture<Optional<String>> recognize(byte[] pcm16k, SessionContext ctx) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
}
