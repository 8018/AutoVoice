package com.autovoice.server.gateway;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 共享 fixture 读取（build.gradle.kts 已接线 {@code sourceSets.test.resources.srcDir("../../shared/fixtures")}，
 * fixture 经 test classpath 加载，禁止复制粘贴到模块内）。
 */
final class TestFixtures {

    static final String HELLO_JSON = read("gateway-hello.json");

    private TestFixtures() {
    }

    static String read(String name) {
        try {
            URL url = TestFixtures.class.getClassLoader().getResource(name);
            if (url == null) {
                throw new IllegalStateException("shared fixture not on test classpath: " + name);
            }
            return new String(Files.readAllBytes(Path.of(url.toURI())), StandardCharsets.UTF_8);
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("cannot read shared fixture: " + name, e);
        }
    }
}
