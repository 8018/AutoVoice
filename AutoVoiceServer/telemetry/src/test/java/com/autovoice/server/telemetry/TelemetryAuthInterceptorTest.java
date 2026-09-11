package com.autovoice.server.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryAuthInterceptorTest {

    private static MockHttpServletRequest request(String method) {
        var request = new MockHttpServletRequest();
        request.setMethod(method);
        return request;
    }

    @Test
    void blankConfigurationKeepsDemoCompatibility() throws Exception {
        var interceptor = new TelemetryAuthInterceptor("");
        assertTrue(interceptor.preHandle(request("POST"), new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(request("GET"), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void configuredTokenRejectsMissingOrWrongHeaderAndAcceptsMatch() throws Exception {
        var interceptor = new TelemetryAuthInterceptor("secret");
        var missingResponse = new MockHttpServletResponse();
        interceptor.preHandle(request("POST"), missingResponse, new Object());
        assertEquals(401, missingResponse.getStatus());

        var wrong = request("POST");
        wrong.addHeader(TelemetryAuthInterceptor.TOKEN_HEADER, "wrong");
        var wrongResponse = new MockHttpServletResponse();
        interceptor.preHandle(wrong, wrongResponse, new Object());
        assertEquals(401, wrongResponse.getStatus());

        var valid = request("POST");
        valid.addHeader(TelemetryAuthInterceptor.TOKEN_HEADER, "secret");
        assertTrue(interceptor.preHandle(valid, new MockHttpServletResponse(), new Object()));
    }

    // ---------- D03a:上传(设备)与查询(管理员)分权 ----------

    @Test
    void uploadRequiresAccessTokenAndRejectsAdminToken() throws Exception {
        var interceptor = new TelemetryAuthInterceptor("access-secret", "admin-secret");

        var withAccess = request("POST");
        withAccess.addHeader(TelemetryAuthInterceptor.TOKEN_HEADER, "access-secret");
        assertTrue(interceptor.preHandle(withAccess, new MockHttpServletResponse(), new Object()));

        var withAdmin = request("POST");
        withAdmin.addHeader(TelemetryAuthInterceptor.TOKEN_HEADER, "admin-secret");
        var response = new MockHttpServletResponse();
        interceptor.preHandle(withAdmin, response, new Object());
        assertEquals(401, response.getStatus(), "管理令牌不能用于设备上传");
    }

    @Test
    void queryRequiresAdminTokenAndRejectsDeviceUploadToken() throws Exception {
        var interceptor = new TelemetryAuthInterceptor("access-secret", "admin-secret");

        var withAdmin = request("GET");
        withAdmin.addHeader(TelemetryAuthInterceptor.TOKEN_HEADER, "admin-secret");
        assertTrue(interceptor.preHandle(withAdmin, new MockHttpServletResponse(), new Object()));

        var withAccess = request("GET");
        withAccess.addHeader(TelemetryAuthInterceptor.TOKEN_HEADER, "access-secret");
        var response = new MockHttpServletResponse();
        interceptor.preHandle(withAccess, response, new Object());
        assertEquals(401, response.getStatus(), "上传凭据不能查询遥测记录");
    }

    @Test
    void blankAdminTokenFallsBackToAccessTokenForQueries() throws Exception {
        var interceptor = new TelemetryAuthInterceptor("access-secret", "");
        var query = request("GET");
        query.addHeader(TelemetryAuthInterceptor.TOKEN_HEADER, "access-secret");
        assertTrue(interceptor.preHandle(query, new MockHttpServletResponse(), new Object()),
                "本地/demo 兼容:未配置管理令牌时查询沿用接入令牌");
    }
}
