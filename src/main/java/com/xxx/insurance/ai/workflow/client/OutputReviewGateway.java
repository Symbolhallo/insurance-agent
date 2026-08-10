package com.xxx.insurance.ai.workflow.client;

import com.xxx.insurance.ai.workflow.model.OutputReviewRequest;
import com.xxx.insurance.ai.workflow.model.OutputReviewResult;

/**
 * 行内成熟输出审核节点的调用边界。
 *
 * <p>Graph 节点只调用该接口的一个方法，不承载行内审核规则。后续接入真实微应用时，
 * 新增 HTTP、Feign 或 SDK 实现并替换 Mock Bean 即可。</p>
 */
public interface OutputReviewGateway {

    /**
     * 将候选答案和生成依据发送到行内审核节点，并返回唯一可发布文本。
     *
     * @param request 输出审核请求
     * @return 行内审核决策和可发布答案
     */
    OutputReviewResult review(OutputReviewRequest request);
}
