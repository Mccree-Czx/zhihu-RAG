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
 * JWT 令牌提供者，负责 access token 和 refresh token 的生成、解析与校验。
 *
 * <p>密钥从 {@link AppProperties.Jwt#secret}（Base64 编码）解码得到 HMAC-SHA256 密钥。
 * access token 包含 uid、roles 等业务声明；refresh token 额外包含 {@code type=refresh} 声明。</p>
 *
 * <p>校验行为：过期 token 抛出 {@link ExpiredJwtException}（供上层统一处理），
 * 签名/格式错误返回 {@code false}。</p>
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

    /**
     * 创建 access token（短期，用于接口鉴权）。
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @param roles    角色列表（如 {@code ["ADMIN", "USER"]}）
     * @return JWT 签名字符串
     */
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

    /**
     * 创建 refresh token（长期，仅用于换取新的 access token）。
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @return JWT 签名字符串
     */
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

    /**
     * 解析 JWT 令牌并返回 Claims。
     *
     * @param token JWT 签名字符串
     * @return 解析后的 Claims 对象
     * @throws JwtException 令牌无效、过期或签名不匹配时抛出
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 校验 JWT 令牌是否有效。
     *
     * <p><b>注意：</b>过期令牌会抛出 {@link ExpiredJwtException}（供上层统一处理），
     * 而不是返回 false。只有签名错误、格式错误等不可恢复的错误才返回 false。</p>
     *
     * @param token JWT 签名字符串
     * @return {@code true} 令牌有效
     * @throws ExpiredJwtException 令牌已过期
     */
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
