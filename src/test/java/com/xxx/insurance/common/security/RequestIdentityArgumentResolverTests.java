package com.xxx.insurance.common.security;

import com.xxx.insurance.common.exception.BusinessException;
import com.xxx.insurance.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdentityArgumentResolverTests {

    private static final MethodParameter IDENTITY_PARAMETER = identityParameter();

    @Test
    void usesConfiguredDefaultsForLocalDevelopment() {
        SecurityIdentityProperties properties = new SecurityIdentityProperties();
        RequestIdentityArgumentResolver resolver = new RequestIdentityArgumentResolver(properties);

        RequestIdentity identity = resolve(resolver, new MockHttpServletRequest());

        assertThat(identity).isEqualTo(RequestIdentity.localDefault());
    }

    @Test
    void resolvesAndNormalizesTrustedGatewayHeaders() {
        SecurityIdentityProperties properties = new SecurityIdentityProperties();
        properties.setRequireHeaders(true);
        RequestIdentityArgumentResolver resolver = new RequestIdentityArgumentResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdentityArgumentResolver.TENANT_HEADER, " tenant-a ");
        request.addHeader(RequestIdentityArgumentResolver.USER_HEADER, "user-001");
        request.addHeader(RequestIdentityArgumentResolver.CUSTOMER_HEADER, "customer-001");
        request.addHeader(RequestIdentityArgumentResolver.OPERATOR_HEADER, "operator-001");

        RequestIdentity identity = resolve(resolver, request);

        assertThat(identity).isEqualTo(
                new RequestIdentity("tenant-a", "user-001", "customer-001", "operator-001"));
        assertThat(identity.namespacedUserId()).isEqualTo("tenant-a:user-001");
    }

    @Test
    void strictModeRejectsMissingTenantOrUserHeader() {
        SecurityIdentityProperties properties = new SecurityIdentityProperties();
        properties.setRequireHeaders(true);
        RequestIdentityArgumentResolver resolver = new RequestIdentityArgumentResolver(properties);

        assertThatThrownBy(() -> resolve(resolver, new MockHttpServletRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.IDENTITY_REQUIRED));
    }

    @Test
    void rejectsIdentifiersOutsideTheHeaderAllowList() {
        SecurityIdentityProperties properties = new SecurityIdentityProperties();
        RequestIdentityArgumentResolver resolver = new RequestIdentityArgumentResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdentityArgumentResolver.TENANT_HEADER, "tenant/a");

        assertThatThrownBy(() -> resolve(resolver, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.PARAM_INVALID));
    }

    private RequestIdentity resolve(RequestIdentityArgumentResolver resolver,
                                    MockHttpServletRequest request) {
        return (RequestIdentity) resolver.resolveArgument(
                IDENTITY_PARAMETER, null, new ServletWebRequest(request), null);
    }

    private static MethodParameter identityParameter() {
        try {
            Method method = TestEndpoint.class.getDeclaredMethod("handle", RequestIdentity.class);
            return new MethodParameter(method, 0);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class TestEndpoint {

        @SuppressWarnings("unused")
        void handle(RequestIdentity identity) {
        }
    }
}
