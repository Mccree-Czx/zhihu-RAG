package com.pol.rag.common.exception;

import com.pol.rag.common.api.Result;
import com.pol.rag.common.api.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器，将各类异常映射为统一的 {@link Result} 响应格式。
 *
 * <p>覆盖：业务异常、参数校验失败、文件上传超限、权限不足、认证失败、未预期异常。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：返回异常携带的 code 和 message */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("Business exception: code={}, msg={}", e.getCode(), e.getMessage());
        return Result.of(e.getCode(), e.getMessage(), null);
    }

    /** {@code @Valid} 参数校验失败：合并所有字段错误信息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.failed(ResultCode.VALIDATE_FAILED, msg);
    }

    /** 表单绑定校验失败（与上类似，处理不同异常类型） */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.failed(ResultCode.VALIDATE_FAILED, msg);
    }

    /** 文件上传超过大小限制 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleUploadSize(MaxUploadSizeExceededException e) {
        return Result.failed(ResultCode.DOC_UPLOAD_FAILED, "上传文件超过大小限制");
    }

    /** 权限不足（无角色访问受保护资源） */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDenied(AccessDeniedException e) {
        return Result.failed(ResultCode.FORBIDDEN);
    }

    /** 认证失败（未登录或 token 无效），返回脱敏消息不泄露具体原因 */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleAuth(AuthenticationException e) {
        log.debug("Authentication failed: {}", e.getMessage());
        return Result.failed(ResultCode.UNAUTHORIZED, "认证失败，请重新登录");
    }

    /** 未预期异常：记录完整堆栈到日志，前端仅返回脱敏的通用错误消息 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return Result.failed(ResultCode.FAILED, "系统内部异常，请联系管理员");
    }
}
