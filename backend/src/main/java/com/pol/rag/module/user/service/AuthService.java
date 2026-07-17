package com.pol.rag.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pol.rag.common.api.ResultCode;
import com.pol.rag.common.exception.BusinessException;
import com.pol.rag.config.properties.AppProperties;
import com.pol.rag.module.user.dto.*;
import com.pol.rag.module.user.entity.SysRole;
import com.pol.rag.module.user.entity.SysUser;
import com.pol.rag.module.user.entity.SysUserRole;
import com.pol.rag.module.user.mapper.SysRoleMapper;
import com.pol.rag.module.user.mapper.SysUserMapper;
import com.pol.rag.module.user.mapper.SysUserRoleMapper;
import com.pol.rag.security.JwtTokenProvider;
import com.pol.rag.security.SecurityUtil;
import com.pol.rag.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Authentication and user management service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final AppProperties appProperties;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String REFRESH_TOKEN_PREFIX = "refresh:";
    private static final String TOKEN_BLACKLIST_PREFIX = "blacklist:";

    @Transactional
    public TokenResponse login(LoginRequest request) {
        // Authenticate
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        } catch (BadCredentialsException e) {
            throw new BusinessException(ResultCode.PASSWORD_ERROR);
        } catch (DisabledException e) {
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }

        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        List<String> roles = principal.getRoles();

        // Generate tokens
        String accessToken = jwtTokenProvider.createAccessToken(
                principal.getId(), principal.getUsername(), roles);
        String refreshToken = jwtTokenProvider.createRefreshToken(
                principal.getId(), principal.getUsername());

        // Store refresh token in Redis (token -> userId)
        redisTemplate.opsForValue().set(
                REFRESH_TOKEN_PREFIX + refreshToken,
                principal.getId().toString(),
                Duration.ofDays(appProperties.getJwt().getRefreshTokenExpireDays()));

        // Build response
        SysUser user = sysUserMapper.selectById(principal.getId());
        TokenResponse.UserInfo userInfo = new TokenResponse.UserInfo(
                principal.getId(), principal.getUsername(), user.getNickname(), roles);

        return new TokenResponse(
                accessToken, refreshToken, "Bearer",
                appProperties.getJwt().getAccessTokenExpireMinutes() * 60, userInfo);
    }

    @Transactional
    public TokenResponse refresh(String refreshToken) {
        Long uid = jwtTokenProvider.parseClaims(refreshToken).get("uid", Long.class);
        String stored = (String) redisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + refreshToken);
        if (stored == null || !String.valueOf(uid).equals(stored)) {
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }
        // Revoke old refresh token
        redisTemplate.delete(REFRESH_TOKEN_PREFIX + refreshToken);

        SysUser user = sysUserMapper.selectById(uid);
        if (user == null || user.getStatus() != 1) {
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }
        List<String> roles = sysRoleMapper.selectRoleCodesByUserId(uid);

        String newAccess = jwtTokenProvider.createAccessToken(uid, user.getUsername(), roles);
        String newRefresh = jwtTokenProvider.createRefreshToken(uid, user.getUsername());
        redisTemplate.opsForValue().set(
                REFRESH_TOKEN_PREFIX + newRefresh,
                uid.toString(),
                Duration.ofDays(appProperties.getJwt().getRefreshTokenExpireDays()));

        TokenResponse.UserInfo userInfo = new TokenResponse.UserInfo(
                uid, user.getUsername(), user.getNickname(), roles);
        return new TokenResponse(
                newAccess, newRefresh, "Bearer",
                appProperties.getJwt().getAccessTokenExpireMinutes() * 60, userInfo);
    }

    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null) {
            long expireMs = jwtTokenProvider.parseClaims(accessToken).getExpiration().getTime() - System.currentTimeMillis();
            if (expireMs > 0) {
                redisTemplate.opsForValue().set(
                        TOKEN_BLACKLIST_PREFIX + accessToken, "1",
                        Duration.ofMillis(expireMs));
            }
        }
        if (refreshToken != null) {
            redisTemplate.delete(REFRESH_TOKEN_PREFIX + refreshToken);
        }
    }

    @Transactional
    public void register(RegisterRequest request) {
        // Check username uniqueness
        Long count = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.getUsername()));
        if (count > 0) {
            throw new BusinessException(ResultCode.USERNAME_EXISTS);
        }

        // Create user
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() != null ? request.getNickname() : request.getUsername());
        user.setEmail(request.getEmail());
        user.setStatus(1);
        sysUserMapper.insert(user);

        // Assign USER role
        List<SysRole> roles = sysRoleMapper.selectList(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, "USER"));
        if (!roles.isEmpty()) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(user.getId());
            userRole.setRoleId(roles.get(0).getId());
            sysUserRoleMapper.insert(userRole);
        }
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        Long uid = SecurityUtil.getCurrentUserId();
        SysUser user = sysUserMapper.selectById(uid);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.OLD_PASSWORD_ERROR);
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        sysUserMapper.updateById(user);
    }
}
