package com.pol.rag.module.audit.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("sys_audit_log")
public class SysAuditLog implements Serializable {

    private Long id;
    private Long userId;
    private String username;
    private String action;
    private String detail;
    private String ip;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
