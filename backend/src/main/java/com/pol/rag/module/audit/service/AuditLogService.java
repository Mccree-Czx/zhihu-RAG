package com.pol.rag.module.audit.service;

import com.pol.rag.module.audit.entity.SysAuditLog;
import com.pol.rag.module.audit.mapper.SysAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 审计日志服务，记录用户操作行为（仅插入，不提供查询/删除接口）。
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final SysAuditLogMapper auditLogMapper;

    /**
     * 记录一条审计日志。
     *
     * @param userId   操作者用户 ID
     * @param username 操作者用户名
     * @param action   操作类型（如 {@code "QUERY"}、{@code "UPLOAD"}）
     * @param detail   操作详情
     * @param ip       客户端 IP 地址
     */
    public void log(Long userId, String username, String action, String detail, String ip) {
        SysAuditLog log = new SysAuditLog();
        log.setUserId(userId);
        log.setUsername(username);
        log.setAction(action);
        log.setDetail(detail);
        log.setIp(ip);
        auditLogMapper.insert(log);
    }
}
