package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.model.OutputReviewDecision;
import com.xxx.insurance.ai.workflow.model.OutputReviewResult;
import com.xxx.insurance.ai.workflow.model.WorkflowSseEventType;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 将审核通过或改写后的唯一可发布答案转换为持久化 agent_stream 事件。
 *
 * <p>原始 ReactAgent Token 只在服务端消费；本组件位于输出审核之后，确保前端永远不会
 * 在 BLOCK 决策前看到未审核正文。分片按 Unicode code point 切分，避免截断代理字符。</p>
 */
@Service
public class ReviewedAnswerStreamPublisher {

    private static final int MAX_CHUNK_CODE_POINTS = 512;

    private final WorkflowEventPublisher workflowEventPublisher;

    /** 创建审核后文本发布器并注入 profile 对应的 SSE 事件端口。 */
    public ReviewedAnswerStreamPublisher(WorkflowEventPublisher workflowEventPublisher) {
        this.workflowEventPublisher = workflowEventPublisher;
    }

    /**
     * SSE 模式下分片发布审核后的正文；BLOCK 决策或普通同步请求不发布。
     */
    public void publish(String workflowInstanceId,
                        String conversationId,
                        boolean tokenStreamingEnabled,
                        OutputReviewResult reviewResult) {
        if (!tokenStreamingEnabled || reviewResult.decision() == OutputReviewDecision.BLOCK) {
            return;
        }
        String answer = reviewResult.publishableAnswer();
        int codePointCount = answer.codePointCount(0, answer.length());
        int chunkCount = Math.max(1, (codePointCount + MAX_CHUNK_CODE_POINTS - 1) / MAX_CHUNK_CODE_POINTS);
        int charOffset = 0;
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int remainingCodePoints = answer.codePointCount(charOffset, answer.length());
            int currentCodePoints = Math.min(MAX_CHUNK_CODE_POINTS, remainingCodePoints);
            int nextOffset = answer.offsetByCodePoints(charOffset, currentCodePoints);
            workflowEventPublisher.publish(
                    workflowInstanceId,
                    conversationId,
                    WorkflowSseEventType.AGENT_STREAM,
                    "output-review",
                    Map.of(
                            "agentName", "main-workflow",
                            "content", answer.substring(charOffset, nextOffset),
                            "chunkIndex", chunkIndex + 1,
                            "chunkCount", chunkCount,
                            "last", chunkIndex + 1 == chunkCount,
                            "deliveryMode", "BUFFERED_UNTIL_REVIEW"));
            charOffset = nextOffset;
        }
    }
}
