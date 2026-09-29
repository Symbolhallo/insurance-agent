package com.xxx.insurance.common.config;

import com.xxx.insurance.common.security.RequestIdentity;
import com.xxx.insurance.common.security.RequestIdentityArgumentResolver;
import com.xxx.insurance.common.security.SecurityIdentityProperties;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTests {

    @Test
    void replacesExpandedIdentityFieldsWithTheActualTrustedHeaders() throws Exception {
        SecurityIdentityProperties properties = new SecurityIdentityProperties();
        Operation operation = new Operation()
                .addParametersItem(new Parameter().name("tenantId").in("query"))
                .addParametersItem(new Parameter().name("limit").in("query"));

        Operation customized = new OpenApiConfig()
                .trustedIdentityHeadersOpenApiCustomizer(properties)
                .customize(operation, identityHandlerMethod());

        assertThat(customized.getParameters())
                .extracting(Parameter::getName)
                .contains("limit",
                        RequestIdentityArgumentResolver.TENANT_HEADER,
                        RequestIdentityArgumentResolver.USER_HEADER,
                        RequestIdentityArgumentResolver.CUSTOMER_HEADER,
                        RequestIdentityArgumentResolver.OPERATOR_HEADER)
                .doesNotContain("tenantId");
        assertThat(header(customized, RequestIdentityArgumentResolver.TENANT_HEADER).getRequired())
                .isFalse();
    }

    @Test
    void marksTenantAndUserHeadersRequiredInStrictMode() throws Exception {
        SecurityIdentityProperties properties = new SecurityIdentityProperties();
        properties.setRequireHeaders(true);

        Operation customized = new OpenApiConfig()
                .trustedIdentityHeadersOpenApiCustomizer(properties)
                .customize(new Operation(), identityHandlerMethod());

        assertThat(header(customized, RequestIdentityArgumentResolver.TENANT_HEADER).getRequired())
                .isTrue();
        assertThat(header(customized, RequestIdentityArgumentResolver.USER_HEADER).getRequired())
                .isTrue();
        assertThat(header(customized, RequestIdentityArgumentResolver.CUSTOMER_HEADER).getRequired())
                .isFalse();
    }

    private Parameter header(Operation operation, String name) {
        return operation.getParameters().stream()
                .filter(parameter -> name.equals(parameter.getName()))
                .findFirst()
                .orElseThrow();
    }

    private HandlerMethod identityHandlerMethod() throws Exception {
        Method method = TestController.class.getDeclaredMethod("handle", String.class, RequestIdentity.class);
        return new HandlerMethod(new TestController(), method);
    }

    private static final class TestController {

        @SuppressWarnings("unused")
        void handle(String value, RequestIdentity identity) {
        }
    }
}
