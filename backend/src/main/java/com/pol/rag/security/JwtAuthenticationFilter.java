package com.pol.rag.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JWT 认证过滤器：从 Authorization 请求头提取 Bearer token，
 * 解析后设置 Spring Security 上下文。
 *
 * <p>继承 {@link OncePerRequestFilter}，保证每个请求仅执行一次。
 * token 缺失或解析失败时静默放行（交给后续过滤器链或 Controller 层处理异常）。
 * 过期 token 不抛异常，仅记日志。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 核心过滤逻辑：解析 JWT → 提取 uid/username/roles → 构建认证令牌 → 设置安全上下文。
     */
    @Override
    @SuppressWarnings("unchecked")
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            Claims claims = jwtTokenProvider.parseClaims(token);
            Long uid = claims.get("uid", Long.class);
            String username = claims.getSubject();
            List<String> roles = claims.get("roles", List.class);

            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .collect(Collectors.toList());

            JwtAuthenticationToken authToken = new JwtAuthenticationToken(
                    uid, username, authorities, token);
            authToken.setAuthenticated(true);
            SecurityContextHolder.getContext().setAuthentication(authToken);
        } catch (ExpiredJwtException e) {
            // Will be handled by JwtAuthenticationEntryPoint or controller
            log.debug("JWT expired for request: {}", request.getRequestURI());
        } catch (Exception e) {
            log.debug("JWT parse error: {}", e.getMessage());
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 从请求头中解析 Bearer token。
     *
     * @param request HTTP 请求
     * @return token 字符串，或 {@code null} 当 head 缺失或格式不正确
     */
    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
