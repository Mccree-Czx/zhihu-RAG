package com.pol.rag.module.kb.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("kb_document")
public class KbDocument implements Serializable {

    private Long id;
    private String title;
    private Long categoryId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String storagePath;
    /** Document processing status */
    private String status;
    private String errorMsg;
    private Integer chunkCount;
    private Long uploadedBy;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
