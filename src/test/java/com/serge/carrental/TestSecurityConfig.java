// file: src/test/java/com/serge/carrental/TestSecurityConfig.java
package com.serge.carrental;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@TestConfiguration
public class TestSecurityConfig {
    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        return token -> {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid token");
            }

            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
            Map<String, Object> parsedClaims = null;
            try {
                parsedClaims = new ObjectMapper().readValue(payloadJson, Map.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

            final Map<String, Object> claimsFinal = parsedClaims; // make effectively final

            return Jwt.withTokenValue(token)
                    .headers(h -> h.put("alg", "none"))
                    .claims(c -> c.putAll(claimsFinal))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
        };
    }
}
