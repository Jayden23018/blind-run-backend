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
 * 两套机制：
 * - blacklistUser/isBlacklisted：按用户整体撤销（注销/封禁），存"拉黑时刻"时间戳，
 *   iat 早于该时刻的 token 全部失效，重新登录的新 token 不受影响。
 * - blacklistToken/isTokenBlacklisted：按 jti 单 token 撤销（登出），只影响这一个 token。
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

    // === blacklistToken：按 jti 单 token 撤销（登出）===

    @Test
    void blacklistToken_setsJtiKeyWithTtl() {
        Claims claims = mock(Claims.class);
        when(claims.getId()).thenReturn("jti-123");
        when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 7200000));
        when(jwtUtil.parseToken("valid.token")).thenReturn(claims);

        service.blacklistToken("valid.token");

        verify(valueOps).set(eq("jwt:blacklist:jti:jti-123"), eq("1"), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void blacklistToken_fallsBackToWholeUser_whenNoJti() {
        Claims claims = mock(Claims.class);
        when(claims.getId()).thenReturn(null);
        when(claims.getSubject()).thenReturn("42");
        when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 7200000));
        when(jwtUtil.parseToken("legacy.token")).thenReturn(claims);

        service.blacklistToken("legacy.token");

        // 旧 token 无 jti，无法单独定位 —— 退化为按用户整体撤销
        verify(valueOps).set(eq("jwt:blacklist:42"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void blacklistToken_skips_whenTokenInvalid() {
        when(jwtUtil.parseToken("bad.token")).thenReturn(null);

        service.blacklistToken("bad.token");

        verifyNoInteractions(valueOps);
    }

    @Test
    void blacklistToken_skips_whenTokenExpired() {
        Claims claims = mock(Claims.class);
        when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() - 1000));
        when(jwtUtil.parseToken("expired.token")).thenReturn(claims);

        service.blacklistToken("expired.token");

        verifyNoInteractions(valueOps);
    }

    // === isTokenBlacklisted ===

    @Test
    void isTokenBlacklisted_returnsTrue_whenJtiKeyExists() {
        when(redisTemplate.hasKey("jwt:blacklist:jti:jti-123")).thenReturn(true);

        assertTrue(service.isTokenBlacklisted("jti-123"));
    }

    @Test
    void isTokenBlacklisted_returnsFalse_whenJtiKeyMissing() {
        when(redisTemplate.hasKey("jwt:blacklist:jti:jti-123")).thenReturn(false);

        assertFalse(service.isTokenBlacklisted("jti-123"));
    }

    @Test
    void isTokenBlacklisted_returnsFalse_whenJtiNull() {
        assertFalse(service.isTokenBlacklisted(null));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void blacklistToken_doesNotAffectOtherTokens_sameUser() {
        // 场景复现：Token A 登出不应影响同账号的 Token B（不同 jti）
        Claims claimsA = mock(Claims.class);
        when(claimsA.getId()).thenReturn("jti-A");
        when(claimsA.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 7200000));
        when(jwtUtil.parseToken("token.A")).thenReturn(claimsA);

        service.blacklistToken("token.A");

        verify(valueOps).set(eq("jwt:blacklist:jti:jti-A"), eq("1"), anyLong(), eq(TimeUnit.SECONDS));
        verify(valueOps, never()).set(eq("jwt:blacklist:jti:jti-B"), anyString(), anyLong(), any(TimeUnit.class));
    }

    // === blacklistUserWithMaxTtl ===

    @Test
    void blacklistUserWithMaxTtl_usesJwtExpirationAsTtl() {
        service.blacklistUserWithMaxTtl(99L);

        // jwtExpirationMs=86400000 → 86400 seconds
        verify(valueOps).set(eq("jwt:blacklist:99"), anyString(), eq(86400L), eq(TimeUnit.SECONDS));
    }
}
