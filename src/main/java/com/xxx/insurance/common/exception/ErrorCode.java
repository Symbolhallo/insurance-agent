package com.xxx.insurance.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 统一错误码定义。
 *
 * <p>错误码先按当前 ProductAnalysisAgent 单智能体闭环保守设计。后续接入保单、资产、
 * 知识库等子智能体时，可以按领域扩展编码段，但不要让 Controller 直接散落字符串错误码。</p>
 */
public enum ErrorCode {

    SUCCESS("0", "success", HttpStatus.OK),
    PARAM_INVALID("COMMON-400", "请求参数不合法", HttpStatus.BAD_REQUEST),
    AGENT_INVOKE_FAILED("AGENT-502", "智能体调用失败", HttpStatus.BAD_GATEWAY),
    SYSTEM_ERROR("COMMON-500", "系统异常", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;

    private final String message;

    private final HttpStatus httpStatus;

    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
