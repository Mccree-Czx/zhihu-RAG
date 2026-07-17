package com.pol.rag.module.audit.service;

import com.pol.rag.module.audit.entity.SysAuditLog;
import com.pol.rag.module.audit.mapper.SysAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final SysAuditLogMapper auditLogMapper;

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
