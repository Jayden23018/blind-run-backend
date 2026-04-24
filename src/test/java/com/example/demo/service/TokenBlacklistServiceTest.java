package com.example.demo.service;

import com.example.demo.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TokenBlacklistService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private JwtUtil jwtUtil;

    private TokenBlacklistService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new TokenBlacklistService(redisTemplate, jwtUtil, 86400000L);
    }

    // === blacklistUser ===

    @Test
    void blacklistUser_setsRedisKeyWithTtl() {
        service.blacklistUser(42L, 3600);

        verify(valueOps).setIfAbsent(eq("jwt:blacklist:42"), eq("1"), eq(3600L), eq(TimeUnit.SECONDS));
    }

    @Test
    void blacklistUser_minimumTtlIs1() {
        service.blacklistUser(42L, 0);

        verify(valueOps).setIfAbsent(eq("jwt:blacklist:42"), eq("1"), eq(1L), eq(TimeUnit.SECONDS));
    }

    @Test
    void blacklistUser_negativeTtlClampedTo1() {
        service.blacklistUser(42L, -100);

        verify(valueOps).setIfAbsent(eq("jwt:blacklist:42"), eq("1"), eq(1L), eq(TimeUnit.SECONDS));
    }

    // === isBlacklisted ===

    @Test
    void isBlacklisted_returnsTrue_whenKeyExists() {
        when(redisTemplate.hasKey("jwt:blacklist:42")).thenReturn(true);

        assertTrue(service.isBlacklisted(42L));
    }

    @Test
    void isBlacklisted_returnsFalse_whenKeyMissing() {
        when(redisTemplate.hasKey("jwt:blacklist:42")).thenReturn(false);

        assertFalse(service.isBlacklisted(42L));
    }

    @Test
    void isBlacklisted_returnsFalse_whenRedisReturnsNull() {
        when(redisTemplate.hasKey("jwt:blacklist:42")).thenReturn(null);

        assertFalse(service.isBlacklisted(42L));
    }

    // === blacklistUserFromToken ===

    @Test
    void blacklistUserFromToken_parsesAndBlacklists() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("42");
        when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 7200000));
        when(jwtUtil.parseToken("valid.token")).thenReturn(claims);

        service.blacklistUserFromToken("valid.token");

        verify(valueOps).setIfAbsent(eq("jwt:blacklist:42"), eq("1"), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void blacklistUserFromToken_skips_whenTokenInvalid() {
        when(jwtUtil.parseToken("bad.token")).thenReturn(null);

        service.blacklistUserFromToken("bad.token");

        verifyNoInteractions(valueOps);
    }

    @Test
    void blacklistUserFromToken_skips_whenTokenExpired() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("42");
        when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() - 1000));
        when(jwtUtil.parseToken("expired.token")).thenReturn(claims);

        service.blacklistUserFromToken("expired.token");

        verifyNoInteractions(valueOps);
    }

    // === blacklistUserWithMaxTtl ===

    @Test
    void blacklistUserWithMaxTtl_usesJwtExpirationAsTtl() {
        service.blacklistUserWithMaxTtl(99L);

        // jwtExpirationMs=86400000 → 86400 seconds
        verify(valueOps).setIfAbsent(eq("jwt:blacklist:99"), eq("1"), eq(86400L), eq(TimeUnit.SECONDS));
    }

    // === TTL overwrite protection (C-1 fix) ===

    @Test
    void blacklistUser_usesSetIfAbsent_notSet() {
        service.blacklistUser(42L, 3600);

        // setIfAbsent preserves existing longer TTL instead of overwriting
        verify(valueOps).setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }
}
