package com.xxx.insurance.common.security;

import com.xxx.insurance.common.exception.BusinessException;
import com.xxx.insurance.common.exception.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.regex.Pattern;

/** 把可信网关身份请求头解析成不可变 RequestIdentity，避免各 Controller 重复读取和校验字符串。 */
public class RequestIdentityArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String USER_HEADER = "X-User-Id";
    public static final String CUSTOMER_HEADER = "X-Customer-Id";
    public static final String OPERATOR_HEADER = "X-Operator-Id";

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._@+-]{1,64}");

    private final SecurityIdentityProperties properties;

    public RequestIdentityArgumentResolver(SecurityIdentityProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == RequestIdentity.class;
    }

    /**
     * 生产严格模式要求租户和用户请求头同时存在；本地模式允许使用配置默认值。客户和操作员同样经过
     * 长度/字符白名单校验，防止未经清洗的身份值进入 SQL、日志和 AgentScope 状态命名空间。
     */
    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        String tenantId = resolve(webRequest, TENANT_HEADER, properties.getDefaultTenantId(), true);
        String userId = resolve(webRequest, USER_HEADER, properties.getDefaultUserId(), true);
        String customerId = resolve(
                webRequest, CUSTOMER_HEADER, properties.getDefaultCustomerId(), false);
        String operatorId = resolve(
                webRequest, OPERATOR_HEADER, properties.getDefaultOperatorId(), false);
        return new RequestIdentity(tenantId, userId, customerId, operatorId);
    }

    private String resolve(NativeWebRequest request,
                           String headerName,
                           String defaultValue,
                           boolean requiredInStrictMode) {
        String value = request.getHeader(headerName);
        if (!StringUtils.hasText(value)) {
            if (properties.isRequireHeaders() && requiredInStrictMode) {
                throw new BusinessException(
                        ErrorCode.IDENTITY_REQUIRED, headerName + " header is required");
            }
            value = defaultValue;
        }
        String normalized = value == null ? "" : value.trim();
        if (!SAFE_IDENTIFIER.matcher(normalized).matches()) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, headerName + " contains invalid characters or length");
        }
        return normalized;
    }
}
