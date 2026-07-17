package com.pol.rag.module.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * User-role association entity.
 */
@Data
@TableName("sys_user_role")
public class SysUserRole implements Serializable {

    private Long id;
    private Long userId;
    private Long roleId;
}
