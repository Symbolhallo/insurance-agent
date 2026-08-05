package com.xxx.insurance.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置。
 *
 * <p>当前只配置最基础的接口文档元信息，方便本地通过 Swagger UI 验证
 * ProductAnalysisAgent 的受控调用入口。这里不加入鉴权、分组、网关地址等生产部署细节，
 * 避免在 Phase1 单 Agent 闭环阶段扩大范围。</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI insuranceAgentOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Insurance Agent API")
                        .description("保险产品管理智能体接口文档")
                        .version("v1"));
    }
}
