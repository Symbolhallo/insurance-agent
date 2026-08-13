package com.xxx.insurance.ai.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring AI Alibaba ReactAgent 流式执行适配器。
 *
 * <p>该组件消费完整 {@link ReactAgent#stream(String)}，将 AGENT_MODEL_STREAMING 的增量
 * Message 实时交给 Token Sink，同时从最终 Graph State 提取完整 AssistantMessage。</p>
 *
 * <p>1.1.2.0 的 streamMessages() 只透出模型增量和 Tool 完成消息，会过滤
 * AGENT_MODEL_FINISHED 及最终 Graph State。当前调用必须在一次 Tool/ReAct 执行内同时获得
 * Token 和权威最终消息，因此不能改成 streamMessages() 后再调用 call()，否则 Tool 会重复执行。</p>
 */
@Component
public class ReactAgentStreamingExecutor {

    private final AgentTokenStreamSink tokenStreamSink;

    /** 创建流式执行器并注入与具体 SSE 实现解耦的 Token 发布端口。 */
    public ReactAgentStreamingExecutor(AgentTokenStreamSink tokenStreamSink) {
        this.tokenStreamSink = tokenStreamSink;
    }

    /** 使用字符串输入执行 ReactAgent 流，并返回最终助手消息。 */
    public AssistantMessage execute(ReactAgent reactAgent, String input) throws Exception {
        return execute(reactAgent, input, null);
    }

    /** 执行字符串输入，并在存在工作流上下文时实时发布模型增量内容。 */
    public AssistantMessage execute(ReactAgent reactAgent,
                                    String input,
                                    AgentTokenStreamContext streamContext) throws Exception {
        AtomicReference<OverAllState> lastState = new AtomicReference<>();
        StreamPublication publication = new StreamPublication(streamContext);
        try {
            reactAgent.stream(input)
                    .doOnNext(output -> handleOutput(output, lastState, publication))
                    .blockLast();
            AssistantMessage finalMessage = validateFinalMessage(extractAssistantMessage(lastState.get()));
            publication.complete();
            return finalMessage;
        }
        catch (Exception ex) {
            publication.abort();
            throw ex;
        }
    }

    /** 使用历史消息列表执行 ReactAgent 流，并返回最终助手消息。 */
    public AssistantMessage execute(ReactAgent reactAgent, List<Message> input) throws Exception {
        return execute(reactAgent, input, null);
    }

    /** 执行历史消息输入，并在存在工作流上下文时实时发布模型增量内容。 */
    public AssistantMessage execute(ReactAgent reactAgent,
                                    List<Message> input,
                                    AgentTokenStreamContext streamContext) throws Exception {
        AtomicReference<OverAllState> lastState = new AtomicReference<>();
        StreamPublication publication = new StreamPublication(streamContext);
        try {
            reactAgent.stream(input)
                    .doOnNext(output -> handleOutput(output, lastState, publication))
                    .blockLast();
            AssistantMessage finalMessage = validateFinalMessage(extractAssistantMessage(lastState.get()));
            publication.complete();
            return finalMessage;
        }
        catch (Exception ex) {
            publication.abort();
            throw ex;
        }
    }

    /** 同时维护最终 State，并严格过滤可向前端发布的模型增量事件。 */
    private void handleOutput(NodeOutput output,
                              AtomicReference<OverAllState> lastState,
                              StreamPublication publication) {
        rememberState(output, lastState);
        if (!(output instanceof StreamingOutput<?> streamingOutput)
                || streamingOutput.getOutputType() != OutputType.AGENT_MODEL_STREAMING
                || !(streamingOutput.message() instanceof AssistantMessage message)
                || message.hasToolCalls()
                || message.getText() == null
                || message.getText().isEmpty()) {
            return;
        }
        publication.publish(message.getText());
    }

    /** 保存每个 NodeOutput 携带的最新 State，最终 END State 包含完整消息列表。 */
    private void rememberState(NodeOutput output, AtomicReference<OverAllState> lastState) {
        if (output != null && output.state() != null) {
            lastState.set(output.state());
        }
    }

    /** 按 ReactAgent.call 的同等语义提取消息列表中最后一个 AssistantMessage。 */
    private AssistantMessage extractAssistantMessage(OverAllState state) {
        if (state == null) {
            throw new IllegalStateException("ReactAgent stream returned no graph state");
        }
        return state.value("messages")
                .stream()
                .flatMap(messages -> ((List<?>) messages).stream())
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException("ReactAgent stream returned no AssistantMessage"));
    }

    /**
     * 将 1.1.2.0 AgentLlmNode 流式分支包装成文本的模型异常恢复为失败终态。
     */
    private AssistantMessage validateFinalMessage(AssistantMessage message) {
        if (!StringUtils.hasText(message.getText())) {
            throw new IllegalStateException("ReactAgent stream returned blank AssistantMessage");
        }
        if (message.getText().startsWith("Exception:")) {
            throw new IllegalStateException("ReactAgent streaming model call failed");
        }
        return message;
    }

    /** 保存单次模型调用的流编号和块序号，支持并行子智能体独立拼接。 */
    private final class StreamPublication {

        private final AgentTokenStreamContext context;
        private final String streamId;
        private final AtomicLong chunkIndex = new AtomicLong();

        private StreamPublication(AgentTokenStreamContext context) {
            this.context = context;
            this.streamId = "stream-" + UUID.randomUUID().toString().replace("-", "");
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
