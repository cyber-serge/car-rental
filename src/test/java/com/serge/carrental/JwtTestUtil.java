package com.serge.carrental;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Minimal JWT builder for tests (NOT cryptographically signed).
 * In a real-world test, sign with the Authorization Server's JWK.
 */
public class JwtTestUtil {
    public static String minimalJwt(String subjectEmail, String scope) {
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"sub\":\""+subjectEmail+"\",\"email\":\""+subjectEmail+"\",\"scope\":\""+scope+"\"}");
        return header + "." + payload + ".";
    }

    private static String base64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
