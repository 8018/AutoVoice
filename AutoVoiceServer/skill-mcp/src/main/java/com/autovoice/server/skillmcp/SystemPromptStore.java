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
