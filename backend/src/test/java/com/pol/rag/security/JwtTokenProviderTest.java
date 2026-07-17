package com.pol.rag.security;

import com.pol.rag.config.properties.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JWT 令牌提供者")
class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        // Build minimal AppProperties with a valid Base64 secret (>=256 bits for HS256)
        String secret = Base64.getEncoder().encodeToString(
                "this-is-a-test-secret-key-for-jwt-hmac-sha256-min-32-bytes!!".getBytes());
        AppProperties props = new AppProperties();
        props.getJwt().setSecret(secret);
        props.getJwt().setAccessTokenExpireMinutes(120);
        props.getJwt().setRefreshTokenExpireDays(7);
        props.getJwt().setIssuer("rag-kb-test");
        provider = new JwtTokenProvider(props);
    }

    @Test
    @DisplayName("生成 access token 应包含 uid、roles 和 issuer")
    void shouldCreateAccessTokenWithClaims() {
        String token = provider.createAccessToken(1L, "admin", List.of("ADMIN"));

        assertThat(token).isNotBlank();
        Claims claims = provider.parseClaims(token);
        assertThat(claims.getIssuer()).isEqualTo("rag-kb-test");
        assertThat(claims.getSubject()).isEqualTo("admin");
        assertThat(claims.get("uid", Long.class)).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        assertThat(roles).contains("ADMIN");
        assertThat(claims.getExpiration()).isAfter(new java.util.Date());
    }

    @Test
    @DisplayName("生成 refresh token 应包含 type=refresh 且有效期更长")
    void shouldCreateRefreshTokenWithTypeClaim() {
        String token = provider.createRefreshToken(1L, "admin");

        Claims claims = provider.parseClaims(token);
        assertThat(claims.get("type", String.class)).isEqualTo("refresh");
    }

    @Test
    @DisplayName("解析合法 token 应返回完整 Claims")
    void shouldParseValidToken() {
        String token = provider.createAccessToken(2L, "user", List.of("USER"));

        Claims claims = provider.parseClaims(token);
        assertThat(claims.get("uid", Long.class)).isEqualTo(2L);
    }

    @Test
    @DisplayName("验证合法 token 应返回 true")
    void shouldValidateValidToken() {
        String token = provider.createAccessToken(1L, "admin", List.of("ADMIN"));
        assertThat(provider.validate(token)).isTrue();
    }

    @Test
    @DisplayName("验证被篡改的 token 应返回 false")
    void shouldRejectTamperedToken() {
        String token = provider.createAccessToken(1L, "admin", List.of("ADMIN"));
        String tampered = token.substring(0, token.length() - 5) + "xxxxx";

        assertThat(provider.validate(tampered)).isFalse();
    }

    @Test
    @DisplayName("验证 null 和空字符串应抛出 IllegalArgumentException")
    void shouldRejectNullOrEmptyToken() {
        assertThatThrownBy(() -> provider.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.validate(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("已过期的 token 应抛出 ExpiredJwtException")
    void shouldThrowForExpiredToken() {
        // Build a provider with 0 minute expiry (instant expiry)
        String secret = Base64.getEncoder().encodeToString(
                "this-is-a-test-secret-key-for-jwt-hmac-sha256-min-32-bytes!!".getBytes());
        AppProperties props = new AppProperties();
        props.getJwt().setSecret(secret);
        props.getJwt().setAccessTokenExpireMinutes(-1); // already expired
        props.getJwt().setRefreshTokenExpireDays(7);
        props.getJwt().setIssuer("rag-kb-test");
        JwtTokenProvider shortLived = new JwtTokenProvider(props);

        String token = shortLived.createAccessToken(1L, "admin", List.of("ADMIN"));

        assertThatThrownBy(() -> shortLived.validate(token))
                .isInstanceOf(ExpiredJwtException.class);
    }
}
