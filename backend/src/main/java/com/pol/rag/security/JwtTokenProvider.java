package com.pol.rag.security;

import com.pol.rag.config.properties.AppProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * JWT access-token and refresh-token provider.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long accessExpireMs;
    private final long refreshExpireMs;
    private final String issuer;

    public JwtTokenProvider(AppProperties props) {
        byte[] keyBytes = Base64.getDecoder().decode(props.getJwt().getSecret());
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessExpireMs = props.getJwt().getAccessTokenExpireMinutes() * 60 * 1000;
        this.refreshExpireMs = props.getJwt().getRefreshTokenExpireDays() * 24 * 3600 * 1000;
        this.issuer = props.getJwt().getIssuer();
    }

    public String createAccessToken(Long userId, String username, List<String> roles) {
        Date now = new Date();
        return Jwts.builder()
                .issuer(issuer)
                .subject(username)
                .claim("uid", userId)
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessExpireMs))
                .signWith(secretKey)
                .compact();
    }

    public String createRefreshToken(Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .issuer(issuer)
                .subject(username)
                .claim("uid", userId)
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshExpireMs))
                .signWith(secretKey)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validate(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
            throw e;
        } catch (JwtException e) {
            log.debug("JWT invalid: {}", e.getMessage());
            return false;
        }
    }
}
