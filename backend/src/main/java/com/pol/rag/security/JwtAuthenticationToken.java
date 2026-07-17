package com.pol.rag.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Custom authentication token holding user id, name, roles, and the raw JWT.
 */
public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final Long userId;
    private final String username;
    private final String token;

    public JwtAuthenticationToken(Long userId, String username,
                                  Collection<? extends GrantedAuthority> authorities,
                                  String token) {
        super(authorities);
        this.userId = userId;
        this.username = username;
        this.token = token;
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    @Override
    public Object getPrincipal() {
        return username;
    }

    public Long getUserId() {
        return userId;
    }

    public String getToken() {
        return token;
    }
}
