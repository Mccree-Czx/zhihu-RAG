package com.pol.rag.module.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("chat_message_source")
public class ChatMessageSource implements Serializable {

    private Long id;
    private Long messageId;
    private Long chunkId;
    private Long documentId;
    private String docTitle;
    private Double score;
    private String snippet;
}
