package com.xxx.insurance.ai.workflow.client;

import com.xxx.insurance.ai.workflow.model.OutputReviewDecision;
import com.xxx.insurance.ai.workflow.model.OutputReviewRequest;
import com.xxx.insurance.ai.workflow.model.OutputReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 本地开发阶段的行内输出审核 Gateway Mock。
 *
 * <p>该实现只模拟远程方法合同，不复制行内审核规则。非空候选答案直接通过；空答案返回
 * 安全降级文案，便于验证 Graph 的 BLOCK 分支和 fail-closed 边界。</p>
 */
public class MockOutputReviewGateway implements OutputReviewGateway {

    private static final Logger log = LoggerFactory.getLogger(MockOutputReviewGateway.class);

    private static final String SAFE_FALLBACK = "本次回答未通过输出审核，暂不对外展示，请联系人工服务进一步核实。";

    /**
     * 模拟调用行内审核方法并返回稳定的本地结果。
     */
    @Override
    public OutputReviewResult review(OutputReviewRequest request) {
        Instant startedAt = Instant.now();
        boolean publishable = StringUtils.hasText(request.candidateAnswer());
        OutputReviewResult result = new OutputReviewResult(
                request.reviewRequestId(),
                publishable ? OutputReviewDecision.PASS : OutputReviewDecision.BLOCK,
                publishable ? request.candidateAnswer() : SAFE_FALLBACK,
                publishable ? List.of("Mock output review passed") : List.of("Candidate answer is blank"),
                true,
                Duration.between(startedAt, Instant.now()).toMillis(),
                Instant.now());
        log.info("[Workflow] node=output-review action=review status={} reviewRequestId={} mock=true",
                result.decision(), result.reviewRequestId());
        return result;
    }
}
