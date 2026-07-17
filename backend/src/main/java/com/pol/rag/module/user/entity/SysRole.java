package com.pol.rag.module.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * Role entity (ADMIN / USER).
 */
@Data
@TableName("sys_role")
public class SysRole implements Serializable {

    private Long id;
    private String roleCode;
    private String roleName;
}
