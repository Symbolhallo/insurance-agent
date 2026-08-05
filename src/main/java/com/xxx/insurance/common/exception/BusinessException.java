package com.xxx.insurance.common.exception;

/**
 * 业务异常基类。
 *
 * <p>当前阶段主要为后续 Service、Tool、Agent 边界预留统一异常模型。本阶段已有的参数校验
 * 仍使用 Bean Validation 和 IllegalArgumentException，避免为了异常体系做过度改造。</p>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
