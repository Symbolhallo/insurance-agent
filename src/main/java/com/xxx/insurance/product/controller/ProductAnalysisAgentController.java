package com.xxx.insurance.product.controller;

import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import com.xxx.insurance.product.model.ProductAnalysisChatRequest;
import com.xxx.insurance.product.model.ProductAnalysisChatResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 产品分析智能体受控调用 API。
 *
 * <p>该 Controller 只暴露 ProductAnalysisAgent 的自然语言调用入口，用于 Phase1
 * 单 Agent 闭环本地验证。当前不提供 Workflow、Planner、Memory 或跨智能体调用 API。</p>
 */
@Tag(name = "ProductAnalysisAgent", description = "保险产品分析智能体受控调用接口")
@RestController
@RequestMapping("/api/v1/product-analysis-agent")
public class ProductAnalysisAgentController {

    private final ProductAnalysisAgent productAnalysisAgent;

    public ProductAnalysisAgentController(ProductAnalysisAgent productAnalysisAgent) {
        this.productAnalysisAgent = productAnalysisAgent;
    }

    /**
     * 调用产品分析智能体。
     *
     * <p>该接口会触发 ReactAgent 模型调用。使用 DeepSeek 本地联调时，需要在 IDEA 或终端
     * 配置 AI_API_KEY、AI_BASE_URL、AI_MODEL。</p>
     */
    @Operation(
            summary = "调用产品分析智能体",
            description = "触发 ProductAnalysisAgent 的 ReactAgent 调用，用于本地验证 Skill 与 product_analysis Tool 的单 Agent 闭环。")
    @PostMapping("/chat")
    public ProductAnalysisChatResponse chat(@Valid @RequestBody ProductAnalysisChatRequest request) {
        return productAnalysisAgent.chat(request);
    }
}
