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

import reactor.core.publisher.Flux;

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

    /**
     * 用一次 ReactAgent.stream 完成完整 ReAct/Tool 循环：持续保存最新 Graph State，只发布无 ToolCall 的
     * AGENT_MODEL_STREAMING 助手正文，流结束后从最终 messages 提取并校验权威 AssistantMessage，再刷新
     * Token Sink 的尾批次和结束标记。任何异常都会 abort 当前 streamId、保留已生成正文且不伪造正常结束。
     */
    public AssistantMessage execute(ReactAgent reactAgent,
                                    String input,
                                    AgentTokenStreamContext streamContext) throws Exception {
        return executeStream(reactAgent.stream(input), streamContext);
    }

    /** 使用历史消息列表执行 ReactAgent 流，并返回最终助手消息。 */
    public AssistantMessage execute(ReactAgent reactAgent, List<Message> input) throws Exception {
        return execute(reactAgent, input, null);
    }

    /**
     * 使用历史消息执行与字符串入口相同的单次 ReactAgent 流、Tool 循环、Token 过滤、最终 State 提取和
     * 正常/异常资源收口；不会为获取最终回答再次 call，从而避免 Tool 重复执行。
     */
    public AssistantMessage execute(ReactAgent reactAgent,
                                    List<Message> input,
                                    AgentTokenStreamContext streamContext) throws Exception {
        return executeStream(reactAgent.stream(input), streamContext);
    }

    /** 统一处理不同输入形式产生的同一类 ReactAgent 输出流，确保成功与异常收口逻辑只有一份。 */
    private AssistantMessage executeStream(Flux<NodeOutput> outputStream,
                                           AgentTokenStreamContext streamContext) throws Exception {
        AtomicReference<OverAllState> lastState = new AtomicReference<>();
        StreamPublication publication = new StreamPublication(streamContext);
        try {
            outputStream
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

    /**
     * 对每个 NodeOutput 先保存最新 State，再仅接受 AGENT_MODEL_STREAMING、AssistantMessage、无 ToolCall、
     * 非空正文的增量块；Tool 请求、Tool 完成、Hook 事件和空块不进入面向用户的 Token SSE。
     */
    private void handleOutput(NodeOutput output,
                              AtomicReference<OverAllState> lastState,
                              StreamPublication publication) {
        rememberState(output, lastState);
        if (!(output instanceof StreamingOutput<?> streamingOutput)) {
            return;
        }
        if (streamingOutput.getOutputType() != OutputType.AGENT_MODEL_STREAMING) {
            return;
        }

        Object rawMessage = streamingOutput.message();
        if (!(rawMessage instanceof AssistantMessage assistantMessage)) {
            return;
        }
        if (assistantMessage.hasToolCalls()) {
            return;
        }

        String text = assistantMessage.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        publication.publish(text);
    }

    /** 保存每个 NodeOutput 携带的最新 State，最终 END State 包含完整消息列表。 */
    private void rememberState(NodeOutput output, AtomicReference<OverAllState> lastState) {
        if (output == null) {
            return;
        }
        OverAllState outputState = output.state();
        if (outputState == null) {
            return;
        }
        lastState.set(outputState);
    }

    /** 按 ReactAgent.call 的同等语义提取消息列表中最后一个 AssistantMessage。 */
    private AssistantMessage extractAssistantMessage(OverAllState state) {
        if (state == null) {
            throw new IllegalStateException("ReactAgent stream returned no graph state");
        }
        Object rawMessages = state.value("messages")
                .orElseThrow(() -> new IllegalStateException("ReactAgent stream returned no messages"));
        List<?> messages = (List<?>) rawMessages;
        for (int index = messages.size() - 1; index >= 0; index--) {
            Object message = messages.get(index);
            if (message instanceof AssistantMessage assistantMessage) {
                return assistantMessage;
            }
        }
        throw new IllegalStateException("ReactAgent stream returned no AssistantMessage");
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

    /**
     * 维护单次模型调用的稳定 streamId 和单调 chunkIndex，使并行子智能体可独立拼接；仅存在工作流上下文
     * 时调用 Sink，正常结束强制刷新尾批次并发结束标记，异常结束只刷新正文和释放临时批次。
     */
    private final class StreamPublication {

        private final AgentTokenStreamContext context;
        private final String streamId;
        private final AtomicLong chunkIndex = new AtomicLong();

        private StreamPublication(AgentTokenStreamContext context) {
            this.context = context;
            this.streamId = "stream-" + UUID.randomUUID().toString().replace("-", "");
        }

        private void publish(String content) {
            if (context == null) {
                return;
            }
            tokenStreamSink.publishToken(context, streamId, chunkIndex.incrementAndGet(), content);
        }

        private void complete() {
            if (context == null) {
                return;
            }
            tokenStreamSink.complete(context, streamId, chunkIndex.get());
        }

        private void abort() {
            if (context == null) {
                return;
            }
            tokenStreamSink.abort(context, streamId);
        }
    }
}
