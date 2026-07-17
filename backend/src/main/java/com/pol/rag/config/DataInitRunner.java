package com.pol.rag.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pol.rag.module.user.entity.SysRole;
import com.pol.rag.module.user.entity.SysUser;
import com.pol.rag.module.user.entity.SysUserRole;
import com.pol.rag.module.user.mapper.SysRoleMapper;
import com.pol.rag.module.user.mapper.SysUserMapper;
import com.pol.rag.module.user.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the admin user (username=admin, password=123456) on first startup if it
 * does not already exist.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitRunner implements CommandLineRunner {

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        Long count = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "admin"));
        if (count > 0) {
            log.info("Admin user already exists, skipping seed.");
            return;
        }

        SysUser admin = new SysUser();
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode("123456"));
        admin.setNickname("系统管理员");
        admin.setStatus(1);
        sysUserMapper.insert(admin);

        SysRole adminRole = sysRoleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, "ADMIN"));
        if (adminRole != null) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(admin.getId());
            userRole.setRoleId(adminRole.getId());
            sysUserRoleMapper.insert(userRole);
        }

        log.info("Admin user seeded (username=admin, password=123456). "
                + "IMPORTANT: change the password in production!");
    }
}
