package com.autovoice.server.contracts;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class ContractSmokeTest {

    @Test
    void sharedFixturesReadable() throws IOException, URISyntaxException {
        URL url = ContractSmokeTest.class.getClassLoader().getResource("gateway-reply-action.json");
        Assertions.assertNotNull(url, "shared/fixtures 未接线");
        String text = new String(Files.readAllBytes(Path.of(url.toURI())), StandardCharsets.UTF_8);
        Assertions.assertTrue(text.contains("\"type\": \"reply\""));
    }
}
