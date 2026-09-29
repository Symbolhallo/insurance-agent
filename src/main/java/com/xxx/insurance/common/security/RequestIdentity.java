package com.xxx.insurance.common.security;

/**
 * 由可信网关或本地默认配置提供的调用方身份。
 *
 * @param tenantId 租户编号，决定数据隔离边界
 * @param userId 登录用户编号，决定会话和工作流所有权
 * @param customerId 当前业务客户编号，供保单/资产 Tool 后续鉴权使用
 * @param operatorId 实际操作员编号，供审计追踪使用
 */
public record RequestIdentity(
        String tenantId,
        String userId,
        String customerId,
        String operatorId) {

    public static final String LOCAL_TENANT_ID = "local-tenant";
    public static final String LOCAL_USER_ID = "mock-user";
    public static final String LOCAL_CUSTOMER_ID = "MOCK-CUSTOMER-001";
    public static final String LOCAL_OPERATOR_ID = "mock-operator";

    /** 为单元测试、非 HTTP 内部调用和默认本地联调提供稳定身份。 */
    public static RequestIdentity localDefault() {
        return new RequestIdentity(
                LOCAL_TENANT_ID, LOCAL_USER_ID, LOCAL_CUSTOMER_ID, LOCAL_OPERATOR_ID);
    }

    /** AgentScope RuntimeContext 使用租户限定的用户命名空间，避免不同租户共享同名用户状态。 */
    public String namespacedUserId() {
        return tenantId + ":" + userId;
    }
}
