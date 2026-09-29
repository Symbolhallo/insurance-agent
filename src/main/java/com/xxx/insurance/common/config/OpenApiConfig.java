package com.xxx.insurance.common.config;

import com.xxx.insurance.common.security.RequestIdentity;
import com.xxx.insurance.common.security.RequestIdentityArgumentResolver;
import com.xxx.insurance.common.security.SecurityIdentityProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * OpenAPI 文档配置。
 *
 * <p>当前只配置最基础的接口文档元信息，方便本地通过 Swagger UI 验证
 * ProductAnalysisAgent 的受控调用入口。这里不加入鉴权、分组、网关地址等生产部署细节，
 * 避免在 Phase1 单 Agent 闭环阶段扩大范围。</p>
 */
@Configuration
public class OpenApiConfig {

    private static final Set<String> REQUEST_IDENTITY_FIELDS = Set.of(
            "identity", "tenantId", "userId", "customerId", "operatorId");

    /**
     * 创建保险智能体 OpenAPI 元数据，供 springdoc 生成 Swagger UI。
     *
     * @return 应用接口标题、说明和版本配置
     */
    @Bean
    public OpenAPI insuranceAgentOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Insurance Agent API")
                        .description("保险产品管理智能体接口文档")
                        .version("v1"));
    }

    /**
     * 对使用 RequestIdentity 的接口隐藏框架可能展开的对象查询参数，并展示真正由 MVC Resolver 读取的
     * 四个请求头。本地默认值模式下请求头可留空；严格模式只把租户和用户标记为必填。
     */
    @Bean
    public OperationCustomizer trustedIdentityHeadersOpenApiCustomizer(
            SecurityIdentityProperties properties) {
        return (operation, handlerMethod) -> {
            boolean identityRequired = java.util.Arrays.stream(handlerMethod.getMethodParameters())
                    .anyMatch(parameter -> parameter.getParameterType() == RequestIdentity.class);
            if (!identityRequired) {
                return operation;
            }
            if (operation.getParameters() != null) {
                operation.getParameters().removeIf(parameter ->
                        REQUEST_IDENTITY_FIELDS.contains(parameter.getName()));
            }
            operation.addParametersItem(identityHeader(
                    RequestIdentityArgumentResolver.TENANT_HEADER,
                    "租户编号，由可信网关认证后注入；本地可省略",
                    properties.isRequireHeaders(), properties.getDefaultTenantId()));
            operation.addParametersItem(identityHeader(
                    RequestIdentityArgumentResolver.USER_HEADER,
                    "登录用户编号，由可信网关认证后注入；本地可省略",
                    properties.isRequireHeaders(), properties.getDefaultUserId()));
            operation.addParametersItem(identityHeader(
                    RequestIdentityArgumentResolver.CUSTOMER_HEADER,
                    "当前业务客户编号；本地可省略",
                    false, properties.getDefaultCustomerId()));
            operation.addParametersItem(identityHeader(
                    RequestIdentityArgumentResolver.OPERATOR_HEADER,
                    "实际操作员编号；本地可省略",
                    false, properties.getDefaultOperatorId()));
            return operation;
        };
    }

    /** 创建与 RequestIdentity 白名单约束一致的 OpenAPI Header 参数。 */
    private Parameter identityHeader(String name,
                                     String description,
                                     boolean required,
                                     String example) {
        return new Parameter()
                .name(name)
                .in("header")
                .description(description)
                .required(required)
                .schema(new StringSchema()
                        .maxLength(64)
                        .pattern("^[A-Za-z0-9._@+-]{1,64}$")
                        .example(example));
    }
}
