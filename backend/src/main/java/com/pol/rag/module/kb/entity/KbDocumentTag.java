package com.pol.rag.module.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("kb_document_tag")
public class KbDocumentTag implements Serializable {

    private Long id;
    private Long documentId;
    private Long tagId;
}
