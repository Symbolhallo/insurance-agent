package com.xxx.insurance.common.exception;

import com.xxx.insurance.common.result.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 *
 * <p>Controller 只表达业务入口，异常到响应协议的转换集中在这里处理。这样后续新增
 * policy、knowledge、asset 等子智能体 API 时，可以复用同一套错误码、日志和响应结构。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(this::formatFieldError)
                .orElse(ErrorCode.PARAM_INVALID.message());
        log.warn("[Agent] errorCode={} message={}", ErrorCode.PARAM_INVALID.code(), message);
        return failure(ErrorCode.PARAM_INVALID, message);
    }

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(RuntimeException ex) {
        log.warn("[Agent] errorCode={} message={}", ErrorCode.PARAM_INVALID.code(), ex.getMessage());
        return failure(ErrorCode.PARAM_INVALID, ex.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        log.warn("[Agent] errorCode={} message={}", ex.errorCode().code(), ex.getMessage());
        return failure(ex.errorCode(), ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.error("[Agent] errorCode={} message={}", ErrorCode.AGENT_INVOKE_FAILED.code(), ex.getMessage(), ex);
        return failure(ErrorCode.AGENT_INVOKE_FAILED, ErrorCode.AGENT_INVOKE_FAILED.message());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        log.error("[Agent] errorCode={} message={}", ErrorCode.SYSTEM_ERROR.code(), ex.getMessage(), ex);
        return failure(ErrorCode.SYSTEM_ERROR, ErrorCode.SYSTEM_ERROR.message());
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }

    private ResponseEntity<ApiResponse<Void>> failure(ErrorCode errorCode, String message) {
        return ResponseEntity
                .status(errorCode.httpStatus())
                .body(ApiResponse.failure(errorCode, message));
    }
}
