package com.autovoice.server.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryAuthInterceptorTest {

    @Test
    void blankConfigurationKeepsDemoCompatibility() throws Exception {
        var interceptor = new TelemetryAuthInterceptor("");
        assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void configuredTokenRejectsMissingOrWrongHeaderAndAcceptsMatch() throws Exception {
        var interceptor = new TelemetryAuthInterceptor("secret");
        var missingResponse = new MockHttpServletResponse();
        interceptor.preHandle(new MockHttpServletRequest(), missingResponse, new Object());
        assertEquals(401, missingResponse.getStatus());

        var wrong = new MockHttpServletRequest();
        wrong.addHeader(TelemetryAuthInterceptor.TOKEN_HEADER, "wrong");
        var wrongResponse = new MockHttpServletResponse();
        interceptor.preHandle(wrong, wrongResponse, new Object());
        assertEquals(401, wrongResponse.getStatus());

        var valid = new MockHttpServletRequest();
        valid.addHeader(TelemetryAuthInterceptor.TOKEN_HEADER, "secret");
        assertTrue(interceptor.preHandle(valid, new MockHttpServletResponse(), new Object()));
    }
}
