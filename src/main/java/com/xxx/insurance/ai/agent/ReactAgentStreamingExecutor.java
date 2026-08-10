package com.xxx.insurance.ai.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring AI Alibaba ReactAgent 流式执行适配器。
 *
 * <p>该组件消费完整 {@link ReactAgent#stream(String)}，并从最后 Graph State 提取最终
 * AssistantMessage。原始模型 Token 不在这里外发，避免绕过主工作流输出审核。</p>
 */
@Component
public class ReactAgentStreamingExecutor {

    /** 使用字符串输入执行 ReactAgent 流，并返回最终助手消息。 */
    public AssistantMessage execute(ReactAgent reactAgent, String input) throws Exception {
        AtomicReference<OverAllState> lastState = new AtomicReference<>();
        reactAgent.stream(input)
                .doOnNext(output -> rememberState(output, lastState))
                .blockLast();
        return validateFinalMessage(extractAssistantMessage(lastState.get()));
    }

    /** 使用历史消息列表执行 ReactAgent 流，并返回最终助手消息。 */
    public AssistantMessage execute(ReactAgent reactAgent, List<Message> input) throws Exception {
        AtomicReference<OverAllState> lastState = new AtomicReference<>();
        reactAgent.stream(input)
                .doOnNext(output -> rememberState(output, lastState))
                .blockLast();
        return validateFinalMessage(extractAssistantMessage(lastState.get()));
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
}
