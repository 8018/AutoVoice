package com.autovoice.server.gateway;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    static List<String> gatewayFixtureNames() {
        try {
            URL hello = TestFixtures.class.getClassLoader().getResource("gateway-hello.json");
            if (hello == null) throw new IllegalStateException("shared fixtures not on test classpath");
            try (var files = Files.list(Path.of(hello.toURI()).getParent())) {
                return files.map(path -> path.getFileName().toString())
                        .filter(name -> name.startsWith("gateway-") && name.endsWith(".json"))
                        .filter(name -> !name.endsWith(".schema.json"))
                        .sorted()
                        .toList();
            }
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("cannot list shared gateway fixtures", e);
        }
    }
}
