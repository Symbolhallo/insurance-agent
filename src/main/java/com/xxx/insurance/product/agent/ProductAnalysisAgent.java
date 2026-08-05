package com.xxx.insurance.product.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.xxx.insurance.product.formatter.ProductAnalysisFormatter;
import com.xxx.insurance.product.model.ProductAnalysisRequest;
import com.xxx.insurance.product.model.ProductAnalysisResult;
import com.xxx.insurance.product.service.ProductAnalysisService;

import java.util.List;
import java.util.Objects;

/**
 * 产品分析业务智能体入口。
 *
 * <p>当前类是 Phase1-Task3 的业务侧骨架，负责把“产品分析智能体”这个业务概念
 * 与 Spring AI Alibaba 的 {@link ReactAgent} 实例绑定起来。这样做有两个原因：</p>
 *
 * <ul>
 *     <li>业务代码只依赖 ProductAnalysisAgent，不直接散落使用 ReactAgent；</li>
 *     <li>后续接入 Tool、Memory、Formatter 时，可以保持业务入口稳定。</li>
 * </ul>
 *
 * <p>当前阶段提供受控的确定性调用边界：只查询 Mock Service 并格式化返回，
 * 不触发 ReactAgent 模型调用，不启用 Tool Calling。真正的大模型单 Agent 闭环
 * 会在后续任务中基于这个骨架继续扩展。</p>
 */
public class ProductAnalysisAgent {

    public static final String AGENT_NAME = "product-analysis-agent";

    public static final String AGENT_DESCRIPTION = "保险产品条款、保障责任、适用客群和风险提示的结构化分析智能体";

    private final ReactAgent reactAgent;

    private final SkillsAgentHook skillsAgentHook;

    private final ProductAnalysisService productAnalysisService;

    private final ProductAnalysisFormatter productAnalysisFormatter;

    public ProductAnalysisAgent(ReactAgent reactAgent,
                                SkillsAgentHook skillsAgentHook,
                                ProductAnalysisService productAnalysisService,
                                ProductAnalysisFormatter productAnalysisFormatter) {
        this.reactAgent = reactAgent;
        this.skillsAgentHook = skillsAgentHook;
        this.productAnalysisService = productAnalysisService;
        this.productAnalysisFormatter = productAnalysisFormatter;
    }

    public String name() {
        return reactAgent.name();
    }

    public String description() {
        return reactAgent.description();
    }

    /**
     * 受控产品分析入口。
     *
     * <p>该方法当前只执行确定性 Mock 数据查询和格式化，不调用 {@link ReactAgent#call(String)}。
     * 这样可以先稳定产品域模型、Service接口和输出结构，后续再把它包装成
     * ProductAnalysisTool 交给 ReactAgent 调用。</p>
     */
    public ProductAnalysisResult analyze(ProductAnalysisRequest request) {
        validateRequest(request);
        return productAnalysisFormatter.format(productAnalysisService.queryProductAnalysisData(request));
    }

    /**
     * 返回底层 Spring AI Alibaba ReactAgent。
     *
     * <p>该方法主要给后续 Workflow 或测试使用。普通业务层优先通过 ProductAnalysisAgent
     * 暴露的业务方法交互，避免未来替换 Agent 编排方式时扩大改造范围。</p>
     */
    public ReactAgent reactAgent() {
        return reactAgent;
    }

    /**
     * 返回当前智能体绑定的 Skill Hook。
     *
     * <p>保留该访问点是为了在后续阶段验证 Skill 与 Tool 的映射关系，
     * 当前不通过它执行 Tool Calling。</p>
     */
    public SkillsAgentHook skillsAgentHook() {
        return skillsAgentHook;
    }

    private void validateRequest(ProductAnalysisRequest request) {
        Objects.requireNonNull(request, "Product analysis request must not be null");
        List<String> productCodes = request.productCodes();
        if (productCodes == null || productCodes.stream().allMatch(code -> code == null || code.isBlank())) {
            throw new IllegalArgumentException("At least one product code is required");
        }
    }
}
