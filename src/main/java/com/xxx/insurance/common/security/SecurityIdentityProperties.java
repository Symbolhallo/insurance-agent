package com.xxx.insurance.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 可信身份请求头及本地默认值配置。 */
@ConfigurationProperties(prefix = "insurance.security.identity")
public class SecurityIdentityProperties {

    private boolean requireHeaders;
    private String defaultTenantId = RequestIdentity.LOCAL_TENANT_ID;
    private String defaultUserId = RequestIdentity.LOCAL_USER_ID;
    private String defaultCustomerId = RequestIdentity.LOCAL_CUSTOMER_ID;
    private String defaultOperatorId = RequestIdentity.LOCAL_OPERATOR_ID;

    public boolean isRequireHeaders() {
        return requireHeaders;
    }

    public void setRequireHeaders(boolean requireHeaders) {
        this.requireHeaders = requireHeaders;
    }

    public String getDefaultTenantId() {
        return defaultTenantId;
    }

    public void setDefaultTenantId(String defaultTenantId) {
        this.defaultTenantId = defaultTenantId;
    }

    public String getDefaultUserId() {
        return defaultUserId;
    }

    public void setDefaultUserId(String defaultUserId) {
        this.defaultUserId = defaultUserId;
    }

    public String getDefaultCustomerId() {
        return defaultCustomerId;
    }

    public void setDefaultCustomerId(String defaultCustomerId) {
        this.defaultCustomerId = defaultCustomerId;
    }

    public String getDefaultOperatorId() {
        return defaultOperatorId;
    }

    public void setDefaultOperatorId(String defaultOperatorId) {
        this.defaultOperatorId = defaultOperatorId;
    }
}
