package com.pol.rag.common.api;

import lombok.Getter;

/**
 * Standard API result codes.
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    FAILED(500, "操作失败"),
    VALIDATE_FAILED(400, "参数校验失败"),
    UNAUTHORIZED(401, "暂未登录或token已失效"),
    FORBIDDEN(403, "没有相关权限"),
    NOT_FOUND(404, "资源不存在"),

    // Business specific (1xxx)
    USERNAME_EXISTS(1001, "用户名已存在"),
    USER_NOT_FOUND(1002, "用户不存在"),
    PASSWORD_ERROR(1003, "用户名或密码错误"),
    OLD_PASSWORD_ERROR(1004, "原密码不正确"),
    ACCOUNT_DISABLED(1005, "账户已被禁用"),
    TOKEN_INVALID(1006, "无效的令牌"),
    TOKEN_EXPIRED(1007, "令牌已过期"),

    // Knowledge base (2xxx)
    DOC_UPLOAD_FAILED(2001, "文档上传失败"),
    DOC_TYPE_UNSUPPORTED(2002, "不支持的文档类型"),
    DOC_NOT_FOUND(2003, "文档不存在"),
    DOC_PARSE_FAILED(2004, "文档解析失败"),

    // Chat / session (3xxx)
    SESSION_NOT_FOUND(3001, "会话不存在"),
    SESSION_FORBIDDEN(3002, "无权访问该会话"),
    RATE_LIMITED(3003, "请求过于频繁，请稍后再试"),
    AI_SERVICE_ERROR(3004, "AI服务调用失败");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
