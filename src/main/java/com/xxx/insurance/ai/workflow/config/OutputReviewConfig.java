package com.xxx.insurance.ai.workflow.config;

import com.xxx.insurance.ai.workflow.client.MockOutputReviewGateway;
import com.xxx.insurance.ai.workflow.client.OutputReviewGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 输出审核 Gateway 装配配置。
 *
 * <p>当前工程未获得行内微应用协议，因此默认注册 Mock。后续真实 HTTP、Feign 或 SDK 实现
 * 注册为 OutputReviewGateway Bean 后，条件装配会自动停止创建 Mock。</p>
 */
@Configuration
public class OutputReviewConfig {

    public static final String OUTPUT_REVIEW_GATEWAY = "outputReviewGateway";

    /**
     * 创建本地输出审核 Mock Gateway。
     *
     * <p>OutputReviewNode 只依赖 OutputReviewGateway 接口；该 Bean 仅在容器中不存在真实实现时创建。</p>
     *
     * @return 本地开发阶段使用的输出审核方法适配器
     */
    @Bean(OUTPUT_REVIEW_GATEWAY)
    @ConditionalOnMissingBean(OutputReviewGateway.class)
    public OutputReviewGateway outputReviewGateway() {
        return new MockOutputReviewGateway();
    }
}
