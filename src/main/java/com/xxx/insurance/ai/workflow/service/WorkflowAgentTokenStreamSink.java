package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.agent.AgentTokenStreamContext;
import com.xxx.insurance.ai.agent.AgentTokenStreamSink;
import com.xxx.insurance.ai.workflow.model.WorkflowSseEventType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 将模型增量内容转换为可持久化、可重放的工作流 SSE 事件。 */
@Component
public class WorkflowAgentTokenStreamSink implements AgentTokenStreamSink {

    private final WorkflowEventPublisher workflowEventPublisher;

    /** 创建 Token 流适配器并注入 profile 对应的事件发布端口。 */
    public WorkflowAgentTokenStreamSink(WorkflowEventPublisher workflowEventPublisher) {
        this.workflowEventPublisher = workflowEventPublisher;
    }

    /** 实时发布一个模型文本块；不等待 Summary 或输出审核。 */
    @Override
    public void publishToken(AgentTokenStreamContext context,
                             String streamId,
                             long chunkIndex,
                             String content) {
        workflowEventPublisher.publish(
                context.workflowInstanceId(),
                context.conversationId(),
                WorkflowSseEventType.AGENT_STREAM,
                node(context),
                eventData(context, streamId, chunkIndex, content, false));
    }

    /** 发布空正文的结束标记，避免重复最终完整回答。 */
    @Override
    public void complete(AgentTokenStreamContext context,
                         String streamId,
                         long chunkCount) {
        workflowEventPublisher.publish(
                context.workflowInstanceId(),
                context.conversationId(),
                WorkflowSseEventType.AGENT_STREAM,
                node(context),
                eventData(context, streamId, chunkCount, "", true));
    }

    /** 构造支持并行 Agent 独立拼接的稳定事件字段。 */
    private Map<String, Object> eventData(AgentTokenStreamContext context,
                                          String streamId,
                                          long chunkIndex,
                                          String content,
                                          boolean last) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("streamId", streamId);
        data.put("agentName", context.agentName());
        data.put("phase", context.phase());
        if (context.taskId() != null) {
            data.put("taskId", context.taskId());
        }
        data.put("content", content);
        data.put("chunkIndex", chunkIndex);
        data.put("last", last);
        data.put("deliveryMode", "LIVE_MODEL_STREAM");
        return Map.copyOf(data);
    }

    private String node(AgentTokenStreamContext context) {
        return switch (context.phase()) {
            case AgentTokenStreamContext.PHASE_PRODUCT_REFERENCE_RESOLUTION -> "resolve-product-reference";
            case AgentTokenStreamContext.PHASE_CONTEXT_ALIGNMENT -> "context-alignment";
            case AgentTokenStreamContext.PHASE_INTENT_RECOGNITION -> "intent-recognition";
            case AgentTokenStreamContext.PHASE_PLANNER -> "planner";
            case AgentTokenStreamContext.PHASE_SUMMARY -> "summary";
            default -> "agent-invoke";
        };
    }
}
