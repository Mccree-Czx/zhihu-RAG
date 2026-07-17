package com.pol.rag.module.user.dto;

import lombok.Data;

@Data
public class LogoutRequest {
    private String refreshToken;
}
