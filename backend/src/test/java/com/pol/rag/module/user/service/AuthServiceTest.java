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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("认证服务")
class AuthServiceTest {

    @Mock private SysUserMapper sysUserMapper;
    @Mock private SysRoleMapper sysRoleMapper;
    @Mock private SysUserRoleMapper sysUserRoleMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;

    @Spy
    private AppProperties appProperties = new AppProperties();

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setUp() {
        appProperties.getJwt().setAccessTokenExpireMinutes(120);
        appProperties.getJwt().setRefreshTokenExpireDays(7);

        // Use lenient to avoid UnnecessaryStubbingException when some tests don't call Redis
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ────────── Register ──────────

    @Test
    @DisplayName("注册成功：用户名不存在时创建用户并分配 USER 角色")
    void shouldRegisterNewUser() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("newuser");
        req.setPassword("pass123");
        req.setNickname("新用户");

        when(sysUserMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(passwordEncoder.encode("pass123")).thenReturn("encoded-pass");
        when(sysRoleMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(buildRole(1L, "USER")));

        authService.register(req);

        verify(sysUserMapper).insert(any(SysUser.class));
        verify(sysUserRoleMapper).insert(any(SysUserRole.class));
    }

    @Test
    @DisplayName("注册失败：用户名已存在应抛出 USERNAME_EXISTS")
    void shouldRejectDuplicateUsername() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("admin");
        req.setPassword("pass123");

        when(sysUserMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> authService.register(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名已存在");
    }

    @Test
    @DisplayName("注册时昵称为 null 应使用用户名作为默认昵称")
    void shouldUseUsernameAsDefaultNickname() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("user1");
        req.setPassword("pass");
        req.setNickname(null);

        when(sysUserMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(any())).thenReturn("enc");
        when(sysRoleMapper.selectList(any())).thenReturn(List.of(buildRole(1L, "USER")));

        authService.register(req);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).insert(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("user1");
    }

    // ────────── Login ──────────

    @Test
    @DisplayName("登录成功应返回 access + refresh token")
    void shouldLoginSuccessfully() {
        LoginRequest req = new LoginRequest();
        req.setUsername("admin");
        req.setPassword("123456");

        UserPrincipal principal = new UserPrincipal(buildUser(1L, "admin", "encoded", 1), List.of("ADMIN"));
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(principal);

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(auth);
        when(jwtTokenProvider.createAccessToken(1L, "admin", List.of("ADMIN")))
                .thenReturn("access-token-xxx");
        when(jwtTokenProvider.createRefreshToken(1L, "admin"))
                .thenReturn("refresh-token-xxx");

        SysUser user = buildUser(1L, "admin", "Admin", 1);
        when(sysUserMapper.selectById(1L)).thenReturn(user);

        TokenResponse resp = authService.login(req);

        assertThat(resp.getAccessToken()).isEqualTo("access-token-xxx");
        assertThat(resp.getRefreshToken()).isEqualTo("refresh-token-xxx");
        assertThat(resp.getUserInfo().getUsername()).isEqualTo("admin");
        verify(valueOperations).set(eq("refresh:refresh-token-xxx"), eq("1"), any(Duration.class));
    }

    @Test
    @DisplayName("登录失败：密码错误应抛出 PASSWORD_ERROR")
    void shouldRejectBadCredentials() {
        LoginRequest req = new LoginRequest();
        req.setUsername("admin");
        req.setPassword("wrong");

        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException(""));

        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    @DisplayName("登录失败：账户被禁用应抛出 ACCOUNT_DISABLED")
    void shouldRejectDisabledAccount() {
        LoginRequest req = new LoginRequest();
        req.setUsername("disabled");
        req.setPassword("pass");

        when(authenticationManager.authenticate(any()))
                .thenThrow(new DisabledException(""));

        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账户已被禁用");
    }

    // ────────── Token Refresh ──────────

    @Test
    @DisplayName("刷新令牌成功应颁发新令牌并撤销旧 refresh token")
    void shouldRefreshTokenSuccessfully() {
        io.jsonwebtoken.Claims mockClaims = mock(io.jsonwebtoken.Claims.class);
        when(mockClaims.get("uid", Long.class)).thenReturn(1L);
        when(jwtTokenProvider.parseClaims("old-refresh")).thenReturn(mockClaims);
        when(valueOperations.get("refresh:old-refresh")).thenReturn("1");

        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setNickname("管理员");
        user.setStatus(1);
        when(sysUserMapper.selectById(1L)).thenReturn(user);
        when(sysRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("ADMIN"));
        when(jwtTokenProvider.createAccessToken(1L, "admin", List.of("ADMIN")))
                .thenReturn("new-access");
        when(jwtTokenProvider.createRefreshToken(1L, "admin"))
                .thenReturn("new-refresh");

        TokenResponse resp = authService.refresh("old-refresh");

        assertThat(resp.getAccessToken()).isEqualTo("new-access");
        assertThat(resp.getRefreshToken()).isEqualTo("new-refresh");
        verify(redisTemplate).delete("refresh:old-refresh");
    }

    @Test
    @DisplayName("刷新令牌失败：Redis 中不存在应抛出 TOKEN_INVALID")
    void shouldRejectMissingRefreshToken() {
        io.jsonwebtoken.Claims mockClaims = mock(io.jsonwebtoken.Claims.class);
        when(mockClaims.get("uid", Long.class)).thenReturn(1L);
        when(jwtTokenProvider.parseClaims("stale")).thenReturn(mockClaims);
        when(valueOperations.get("refresh:stale")).thenReturn(null);

        assertThatThrownBy(() -> authService.refresh("stale"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无效的令牌");
    }

    // ────────── Change Password ──────────

    @Test
    @DisplayName("修改密码成功：旧密码匹配时更新密码")
    void shouldChangePassword() {
        try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
            mocked.when(SecurityUtil::getCurrentUserId).thenReturn(1L);

            SysUser user = new SysUser();
            user.setId(1L);
            user.setPassword("old-encoded");
            when(sysUserMapper.selectById(1L)).thenReturn(user);
            when(passwordEncoder.matches("old", "old-encoded")).thenReturn(true);
            when(passwordEncoder.encode("newpass")).thenReturn("new-encoded");

            ChangePasswordRequest req = new ChangePasswordRequest();
            req.setOldPassword("old");
            req.setNewPassword("newpass");

            authService.changePassword(req);

            verify(sysUserMapper).updateById(argThat((SysUser u) -> "new-encoded".equals(u.getPassword())));
        }
    }

    @Test
    @DisplayName("修改密码失败：旧密码不匹配应抛出 OLD_PASSWORD_ERROR")
    void shouldRejectWrongOldPassword() {
        try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
            mocked.when(SecurityUtil::getCurrentUserId).thenReturn(1L);

            SysUser user = new SysUser();
            user.setId(1L);
            user.setPassword("encoded-old");
            when(sysUserMapper.selectById(1L)).thenReturn(user);
            when(passwordEncoder.matches("wrong-old", "encoded-old")).thenReturn(false);

            ChangePasswordRequest req = new ChangePasswordRequest();
            req.setOldPassword("wrong-old");
            req.setNewPassword("newpass");

            assertThatThrownBy(() -> authService.changePassword(req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("原密码不正确");
        }
    }

    // ────────── Logout ──────────

    @Test
    @DisplayName("登出应将 access token 加入黑名单并删除 refresh token")
    void shouldLogoutAndBlacklistTokens() {
        java.util.Date future = new java.util.Date(System.currentTimeMillis() + 3600_000);
        io.jsonwebtoken.Claims mockClaims = mock(io.jsonwebtoken.Claims.class);
        when(mockClaims.getExpiration()).thenReturn(future);
        when(jwtTokenProvider.parseClaims("access-token")).thenReturn(mockClaims);

        authService.logout("access-token", "refresh-token");

        verify(valueOperations).set(eq("blacklist:access-token"), eq("1"), any(Duration.class));
        verify(redisTemplate).delete("refresh:refresh-token");
    }

    // ────────── Helpers ──────────

    private SysUser buildUser(Long id, String username, String nickname, int status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setStatus(status);
        user.setPassword("encoded");
        return user;
    }

    private SysRole buildRole(Long id, String code) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleCode(code);
        role.setRoleName(code);
        return role;
    }
}
