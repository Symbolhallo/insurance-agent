package com.xxx.insurance.ai.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentScope ReActAgent 流式执行适配器。
 *
 * <p>一次 AgentScope 事件流同时承载模型增量、Tool Calling 和最终结果。这里只把正文增量
 * 发布到项目可靠 SSE 通道，并从同一事件流提取权威最终消息，避免重复调用模型或 Tool。</p>
 */
@Component
public class ReactAgentStreamingExecutor {

    private final AgentTokenStreamSink tokenStreamSink;

    public ReactAgentStreamingExecutor(AgentTokenStreamSink tokenStreamSink) {
        this.tokenStreamSink = tokenStreamSink;
    }

    public AssistantMessage execute(HarnessAgent reactAgent, String input) {
        return execute(reactAgent, input, null);
    }

    /** 执行完整 ReAct 事件流，并在正常、异常路径分别完成或中止项目 Token 流。 */
    public AssistantMessage execute(HarnessAgent reactAgent,
                                    String input,
                                    AgentTokenStreamContext streamContext) {
        return executeEvents(reactAgent, List.of(new UserMessage(input)), streamContext);
    }

    /** 使用 AgentScope 消息窗口执行 Agent。 */
    public AssistantMessage execute(HarnessAgent reactAgent, List<Msg> input) {
        return execute(reactAgent, input, null);
    }

    public AssistantMessage execute(HarnessAgent reactAgent,
                                    List<Msg> input,
                                    AgentTokenStreamContext streamContext) {
        return executeEvents(reactAgent, input, streamContext);
    }

    private AssistantMessage executeEvents(HarnessAgent reactAgent,
                                           List<Msg> input,
                                           AgentTokenStreamContext streamContext) {
        AtomicReference<Msg> finalResult = new AtomicReference<>();
        StreamPublication publication = new StreamPublication(streamContext);
        RuntimeContext runtimeContext = runtimeContext(streamContext);
        try {
            reactAgent.streamEvents(input, runtimeContext)
                    .doOnNext(event -> handleEvent(event, finalResult, publication))
                    .blockLast();
            Msg result = finalResult.get();
            if (result == null || !StringUtils.hasText(result.getTextContent())) {
                throw new IllegalStateException("AgentScope ReActAgent returned blank result");
            }
            publication.complete();
            return result instanceof AssistantMessage assistant
                    ? assistant
                    : new AssistantMessage(reactAgent.getName(), result.getTextContent());
        }
        catch (RuntimeException ex) {
            publication.abort();
            throw ex;
        }
        finally {
            // 业务会话历史由 OceanBase ai_chat_memory 统一管理。内部执行器使用一次性 Session，
            // 调用后立即删除 Harness State，避免同一历史被业务窗口和 Harness 重复注入。
            reactAgent.clearStateCache(runtimeContext.getUserId(), runtimeContext.getSessionId());
            if (reactAgent.getStateStore() != null) {
                reactAgent.getStateStore().delete(runtimeContext.getUserId(), runtimeContext.getSessionId());
            }
        }
    }

    /** 只转发正文增量；Tool、思考块和生命周期事件仍留在 AgentScope 事件总线中。 */
    private void handleEvent(AgentEvent event,
                             AtomicReference<Msg> finalResult,
                             StreamPublication publication) {
        if (event instanceof TextBlockDeltaEvent delta && StringUtils.hasText(delta.getDelta())) {
            publication.publish(delta.getDelta());
        }
        else if (event instanceof AgentResultEvent resultEvent) {
            finalResult.set(resultEvent.getResult());
        }
    }

    private RuntimeContext runtimeContext(AgentTokenStreamContext context) {
        String sessionPrefix = context == null
                ? "standalone"
                : context.workflowInstanceId() + ":" + String.valueOf(context.taskId());
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .userId("insurance-platform")
                .sessionId(sessionPrefix + ":" + UUID.randomUUID().toString().replace("-", ""));
        if (context != null) {
            putIfPresent(builder, "workflowInstanceId", context.workflowInstanceId());
            putIfPresent(builder, "taskId", context.taskId());
            putIfPresent(builder, "agentName", context.agentName());
        }
        return builder.build();
    }

    /** AgentScope 使用 ConcurrentHashMap 保存扩展上下文，空的可选链路字段必须省略。 */
    private void putIfPresent(RuntimeContext.Builder builder, String key, String value) {
        if (StringUtils.hasText(value)) {
            builder.put(key, value);
        }
    }

    private final class StreamPublication {

        private final AgentTokenStreamContext context;
        private final String streamId = "stream-" + UUID.randomUUID().toString().replace("-", "");
        private final AtomicLong chunkIndex = new AtomicLong();

        private StreamPublication(AgentTokenStreamContext context) {
            this.context = context;
        }

        private void publish(String content) {
            if (context != null) {
                tokenStreamSink.publishToken(context, streamId, chunkIndex.incrementAndGet(), content);
            }
        }

        private void complete() {
            if (context != null) {
                tokenStreamSink.complete(context, streamId, chunkIndex.get());
            }
        }

        private void abort() {
            if (context != null) {
                tokenStreamSink.abort(context, streamId);
            }
        }
    }
}
