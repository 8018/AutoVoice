package com.autovoice.server.app;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/** Tencent Cloud API 3.0 TC3-HMAC-SHA256 signing for WSA SearchPro. */
final class TencentCloudTc3Signer {
    private static final String HOST = "wsa.tencentcloudapi.com";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String SERVICE = "wsa";
    private static final String ALGORITHM = "TC3-HMAC-SHA256";

    private TencentCloudTc3Signer() {}

    static String authorization(String secretId, String secretKey, String payload, long timestamp) {
        String date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochSecond(timestamp));
        String scope = date + "/" + SERVICE + "/tc3_request";
        String canonical = "POST\n/\n\ncontent-type:" + CONTENT_TYPE + "\nhost:" + HOST
                + "\n\ncontent-type;host\n" + sha256(payload);
        String toSign = ALGORITHM + "\n" + timestamp + "\n" + scope + "\n" + sha256(canonical);
        byte[] dateKey = hmac(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] serviceKey = hmac(dateKey, SERVICE);
        byte[] signingKey = hmac(serviceKey, "tc3_request");
        String signature = HexFormat.of().formatHex(hmac(signingKey, toSign));
        return ALGORITHM + " Credential=" + secretId + "/" + scope
                + ", SignedHeaders=content-type;host, Signature=" + signature;
    }

    static String host() { return HOST; }
    static String contentType() { return CONTENT_TYPE; }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
