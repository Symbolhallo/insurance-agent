package com.xxx.insurance.common.util;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * TraceId 工具。
 */
public final class TraceIdUtil {

    public static final String TRACE_ID = "traceId";

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private TraceIdUtil() {
    }

    public static String currentTraceId() {
        String traceId = MDC.get(TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        return traceId;
    }
}
