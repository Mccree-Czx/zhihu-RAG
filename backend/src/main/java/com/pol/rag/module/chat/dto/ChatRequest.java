package com.pol.rag.module.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {

    @NotBlank(message = "问题不能为空")
    private String question;

    @jakarta.validation.constraints.NotNull(message = "会话ID不能为空")
    private Long sessionId;
}
