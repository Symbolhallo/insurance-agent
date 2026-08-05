package com.xxx.insurance.common.result;

import com.xxx.insurance.common.exception.ErrorCode;
import com.xxx.insurance.common.util.TraceIdUtil;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 统一 API 响应结构。
 *
 * <p>金融行业接口通常需要稳定的响应协议，方便前端、网关、监控和审计系统统一识别
 * 请求是否成功、业务错误码、错误信息和链路标识。当前阶段只定义 HTTP API 边界，
 * 不改变 Agent、Skill、Tool 的内部执行模型。</p>
 *
 * @param success 是否成功
 * @param code 业务响应码
 * @param message 响应信息
 * @param data 响应数据
 * @param traceId 链路标识
 * @param timestamp 响应时间
 * @param <T> 响应数据类型
 */
@Schema(description = "统一 API 响应")
public record ApiResponse<T>(
        @Schema(description = "是否成功", example = "true")
        boolean success,

        @Schema(description = "业务响应码", example = "0")
        String code,

        @Schema(description = "响应信息", example = "success")
        String message,

        @Schema(description = "响应数据")
        T data,

        @Schema(description = "链路标识", example = "7b65d4eecdd44d73a4d15de78d986f21")
        String traceId,

        @Schema(description = "响应时间")
        Instant timestamp) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                true,
                ErrorCode.SUCCESS.code(),
                ErrorCode.SUCCESS.message(),
                data,
                TraceIdUtil.currentTraceId(),
                Instant.now());
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message) {
        return new ApiResponse<>(
                false,
                errorCode.code(),
                message,
                null,
                TraceIdUtil.currentTraceId(),
                Instant.now());
    }
}
