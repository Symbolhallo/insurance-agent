package com.xxx.insurance.common.config;

import com.xxx.insurance.common.util.TraceIdUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * HTTP 请求 TraceId 过滤器。
 *
 * <p>TraceId 会写入 MDC 和响应头，便于后续把 [Agent]、[Skill]、[Tool]、[Memory]
 * 链路日志串起来。当前不引入分布式追踪组件，只先稳定本地 API 和日志边界。</p>
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TraceIdUtil.TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        MDC.put(TraceIdUtil.TRACE_ID, traceId);
        response.setHeader(TraceIdUtil.TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        }
        finally {
            MDC.remove(TraceIdUtil.TRACE_ID);
        }
    }
}
