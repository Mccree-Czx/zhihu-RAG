package com.pol.rag.module.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Change password request.
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @jakarta.validation.constraints.Size(min = 6, max = 32, message = "密码长度6-32位")
    private String newPassword;
}
