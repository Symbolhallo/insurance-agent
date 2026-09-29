package com.xxx.insurance.ai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AgentScope {@link Model} 流式执行适配器。
 *
 * <p>上下文对齐、意图识别、产品实体解析和会话摘要不需要 ReAct/Tool 循环，直接调用
 * AgentScope Model。组件从同一条模型流提取 {@link TextBlock}、实时发布工作流 Token、聚合
 * 完整文本并执行受控 JSON 边界修复，避免这些确定性节点继续依赖 Spring AI ChatModel。</p>
 */
@Component
public class AgentScopeModelExecutor {

    private final Model model;

    private final AgentTokenStreamSink tokenStreamSink;

    private final ObjectMapper objectMapper;

    /** 创建全局模型执行器，组合 AgentScope Model、可靠 Token 出口和 JSON 校验器。 */
    public AgentScopeModelExecutor(Model model,
                                   AgentTokenStreamSink tokenStreamSink,
                                   ObjectMapper objectMapper) {
        this.model = model;
        this.tokenStreamSink = tokenStreamSink;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行一次无 Tool 的 AgentScope 模型流。
     *
     * <p>每个 {@link ChatResponse} 可能包含多个内容块；这里只聚合正文 TextBlock，思考或其他
     * 模态块不会进入业务 JSON。存在 streamContext 时，每个正文增量同时写入现有可靠 SSE
     * 通道；流结束后刷新尾批次，异常时中止当前 streamId。</p>
     */
    public String execute(List<Msg> messages, AgentTokenStreamContext streamContext) {
        String streamId = "stream-" + UUID.randomUUID().toString().replace("-", "");
        AtomicLong chunkIndex = new AtomicLong();
        StringBuilder completeContent = new StringBuilder();
        try {
            model.stream(List.copyOf(messages), List.of(), null)
                    .doOnNext(response -> appendResponse(
                            response, completeContent, streamContext, streamId, chunkIndex))
                    .blockLast();
            if (!StringUtils.hasText(completeContent)) {
                throw new IllegalStateException("AgentScope Model stream returned blank content");
            }
            if (streamContext != null) {
                tokenStreamSink.complete(streamContext, streamId, chunkIndex.get());
            }
            return repairMissingObjectStart(completeContent.toString());
        }
        catch (RuntimeException ex) {
            if (streamContext != null) {
                tokenStreamSink.abort(streamContext, streamId);
            }
            throw ex;
        }
    }

    /** 提取一个模型响应中的全部正文块，并按原始顺序聚合和发布。 */
    private void appendResponse(ChatResponse response,
                                StringBuilder completeContent,
                                AgentTokenStreamContext streamContext,
                                String streamId,
                                AtomicLong chunkIndex) {
        if (response == null || response.getContent() == null) {
            return;
        }
        for (ContentBlock block : response.getContent()) {
            if (block instanceof TextBlock textBlock && !textBlock.getText().isEmpty()) {
                completeContent.append(textBlock.getText());
                if (streamContext != null) {
                    tokenStreamSink.publishToken(
                            streamContext, streamId, chunkIndex.incrementAndGet(), textBlock.getText());
                }
            }
        }
    }

    /**
     * 修复部分 OpenAI-compatible 流只丢失对象起始花括号的已知兼容问题。
     *
     * <p>仅当正文以属性名开始、以对象结束符结束，且补齐后可被 Jackson 严格解析成对象时
     * 才修复；其他非法输出原样返回，由结构化输出转换器拒绝。</p>
     */
    private String repairMissingObjectStart(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("\"") || !trimmed.endsWith("}")) {
            return content;
        }
        String candidate = "{" + trimmed;
        try {
            JsonNode node = objectMapper.readTree(candidate);
            return node.isObject() ? candidate : content;
        }
        catch (JsonProcessingException ex) {
            return content;
        }
    }
}
