package com.pol.rag.module.kb.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("kb_tag")
public class KbTag implements Serializable {

    private Long id;
    private String name;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
