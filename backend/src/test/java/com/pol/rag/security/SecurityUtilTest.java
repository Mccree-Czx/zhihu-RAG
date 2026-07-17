package com.pol.rag.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("安全上下文工具类")
class SecurityUtilTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("已认证 JwtAuthenticationToken 时应返回 userId")
    void shouldReturnUserIdFromJwtToken() {
        JwtAuthenticationToken token = new JwtAuthenticationToken(1L, "admin", null, null);
        SecurityContextHolder.getContext().setAuthentication(token);

        assertThat(SecurityUtil.getCurrentUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("未认证时应返回 null userId")
    void shouldReturnNullUserIdWhenUnauthenticated() {
        SecurityContextHolder.clearContext();

        assertThat(SecurityUtil.getCurrentUserId()).isNull();
    }

    @Test
    @DisplayName("非 JwtAuthenticationToken 的认证对象应返回 null userId")
    void shouldReturnNullUserIdForOtherAuthType() {
        Authentication auth = mock(Authentication.class);
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(SecurityUtil.getCurrentUserId()).isNull();
    }

    @Test
    @DisplayName("已认证 JwtAuthenticationToken 时应返回 username")
    void shouldReturnUsernameFromJwtToken() {
        JwtAuthenticationToken token = new JwtAuthenticationToken(1L, "admin", null, null);
        SecurityContextHolder.getContext().setAuthentication(token);

        assertThat(SecurityUtil.getCurrentUsername()).isEqualTo("admin");
    }

    @Test
    @DisplayName("非 JWT 但已认证的普通 Authentication 应返回 getName()")
    void shouldReturnNameFromGenericAuth() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("generic-user");
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(SecurityUtil.getCurrentUsername()).isEqualTo("generic-user");
    }

    @Test
    @DisplayName("未认证时应返回 null username")
    void shouldReturnNullUsernameWhenUnauthenticated() {
        SecurityContextHolder.clearContext();

        assertThat(SecurityUtil.getCurrentUsername()).isNull();
    }
}
