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
 *
 * 黑名单采用"按签发时间比对"：blacklistUser 存"拉黑时刻"时间戳（set 覆盖写），
 * isBlacklisted(userId, issuedAt) 判定 token 签发时间是否早于该时刻。
 * 这样用户登出后重新登录拿到的新 token 不会被旧黑名单锁死。
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

    // === blacklistUser：用 set 覆盖写，值为"拉黑时刻"时间戳 ===

    @Test
    void blacklistUser_setsTimestampWithTtl() {
        service.blacklistUser(42L, 3600);

        verify(valueOps).set(eq("jwt:blacklist:42"), argThat(s -> s.matches("\\d+")),
                eq(3600L), eq(TimeUnit.SECONDS));
    }

    @Test
    void blacklistUser_minimumTtlIs1() {
        service.blacklistUser(42L, 0);

        verify(valueOps).set(eq("jwt:blacklist:42"), anyString(), eq(1L), eq(TimeUnit.SECONDS));
    }

    @Test
    void blacklistUser_negativeTtlClampedTo1() {
        service.blacklistUser(42L, -100);

        verify(valueOps).set(eq("jwt:blacklist:42"), anyString(), eq(1L), eq(TimeUnit.SECONDS));
    }

    @Test
    void blacklistUser_usesSet_notSetIfAbsent() {
        service.blacklistUser(42L, 3600);

        // 每次登出都需刷新为最新拉黑时刻，必须用 set 覆盖写，不能用 setIfAbsent
        verify(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    // === isBlacklisted：按签发时间比对 ===

    @Test
    void isBlacklisted_returnsFalse_whenNotBlacklisted() {
        when(valueOps.get("jwt:blacklist:42")).thenReturn(null);

        assertFalse(service.isBlacklisted(42L, new Date()));
    }

    @Test
    void isBlacklisted_returnsTrue_whenTokenIssuedBeforeBlacklistMoment() {
        long blacklistMoment = System.currentTimeMillis();
        when(valueOps.get("jwt:blacklist:42")).thenReturn(String.valueOf(blacklistMoment));

        // 旧 token：签发于拉黑之前 → 被吊销
        Date oldIssuedAt = new Date(blacklistMoment - 60000);
        assertTrue(service.isBlacklisted(42L, oldIssuedAt));
    }

    @Test
    void isBlacklisted_returnsFalse_whenTokenIssuedAfterBlacklistMoment() {
        long blacklistMoment = System.currentTimeMillis();
        when(valueOps.get("jwt:blacklist:42")).thenReturn(String.valueOf(blacklistMoment));

        // 重新登录的新 token：签发于拉黑之后 → 放行（这正是 S1 修复的锁死场景）
        Date newIssuedAt = new Date(blacklistMoment + 60000);
        assertFalse(service.isBlacklisted(42L, newIssuedAt));
    }

    @Test
    void isBlacklisted_returnsTrue_whenIssuedAtNull() {
        when(valueOps.get("jwt:blacklist:42")).thenReturn(String.valueOf(System.currentTimeMillis()));

        // 无签发时间信息，保守判定为已吊销
        assertTrue(service.isBlacklisted(42L, null));
    }

    // === blacklistUserFromToken ===

    @Test
    void blacklistUserFromToken_parsesAndBlacklists() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("42");
        when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 7200000));
        when(jwtUtil.parseToken("valid.token")).thenReturn(claims);

        service.blacklistUserFromToken("valid.token");

        verify(valueOps).set(eq("jwt:blacklist:42"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
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
        verify(valueOps).set(eq("jwt:blacklist:99"), anyString(), eq(86400L), eq(TimeUnit.SECONDS));
    }
}
