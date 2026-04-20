package com.example.demo.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rate limiting integration tests
 */
@TestPropertySource(properties = {
        "rate-limit.enabled=true",
        "rate-limit.auth.max-requests=3",
        "rate-limit.auth.window-seconds=60"
})
class RateLimitTest extends BaseIntegrationTest {

    @BeforeEach
    void setUp() {
        // Flush all rate limit keys to reset state between tests
        Set<String> keys = redisTemplate.keys("rate_limit:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void authEndpointBlockedAfterLimit() {
        // Send 3 requests - should all succeed
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/api/auth/send-code",
                    null,
                    String.class
            );
            assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        // 4th request should be rate limited
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/auth/send-code",
                null,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).contains("\"error\"");
        assertThat(response.getBody()).contains("\"TOO_MANY_REQUESTS\"");
        assertThat(response.getBody()).contains("\"retryAfterSeconds\"");
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }

    @Test
    void generalEndpointUsesSeparateBucket() {
        // Send 3 auth requests - should all succeed (limit is 3)
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/api/auth/send-code",
                    null,
                    String.class
            );
            assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        // 4th auth request should be blocked
        ResponseEntity<String> authResponse = restTemplate.postForEntity(
                "/api/auth/send-code",
                null,
                String.class
        );
        assertThat(authResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // But GET to general endpoint should work (different bucket with 60 request limit)
        ResponseEntity<String> generalResponse = restTemplate.getForEntity(
                "/api/orders",
                String.class
        );
        // Should be either 401 (unauthorized) or 400 (bad request), but NOT 429
        assertThat(generalResponse.getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @Disabled("MockMvc always uses 127.0.0.1, multi-IP test requires manual verification")
    void differentBucketsAreIndependent() {
        // This test would require multiple client IPs to properly test
        // Since TestRestTemplate always uses 127.0.0.1, we can't test this automatically
    }
}
