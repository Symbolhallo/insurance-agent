package com.xxx.insurance.ai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring AI ChatModel 结构化输出流执行器。
 *
 * <p>上下文对齐和意图识别直接使用 Spring AI ChatModel，而不是 ReactAgent。本组件在不改变
 * BeanOutputConverter 校验链路的前提下，将模型增量文本实时发布给工作流 SSE，并聚合出完整
 * JSON 文本供业务服务转换。</p>
 */
@Component
public class ChatModelStreamingExecutor {

    private final AgentTokenStreamSink tokenStreamSink;

    private final ObjectMapper objectMapper;

    /** 创建 ChatModel 流执行器并注入统一 Token 发布端口。 */
    public ChatModelStreamingExecutor(AgentTokenStreamSink tokenStreamSink,
                                      ObjectMapper objectMapper) {
        this.tokenStreamSink = tokenStreamSink;
        this.objectMapper = objectMapper;
    }

    /**
     * 流式调用模型，逐块发布文本，并返回可供结构化转换器解析的完整模型结果。
     */
    public String execute(ChatModel chatModel,
                          List<Message> messages,
                          AgentTokenStreamContext streamContext) {
        String streamId = "stream-" + UUID.randomUUID().toString().replace("-", "");
        AtomicLong chunkIndex = new AtomicLong();
        AtomicReference<ChatResponse> aggregatedResponse = new AtomicReference<>();

        try {
            Flux<ChatResponse> responseFlux = chatModel.stream(new Prompt(messages))
                    .doOnNext(response -> publishChunk(response, streamContext, streamId, chunkIndex));
            new MessageAggregator()
                    .aggregate(responseFlux, aggregatedResponse::set)
                    .blockLast();

            String completeContent = text(aggregatedResponse.get());
            if (!StringUtils.hasText(completeContent)) {
                throw new IllegalStateException("ChatModel stream returned blank content");
            }
            String normalizedContent = repairMissingObjectStart(completeContent);
            tokenStreamSink.complete(streamContext, streamId, chunkIndex.get());
            return normalizedContent;
        }
        catch (RuntimeException ex) {
            tokenStreamSink.abort(streamContext, streamId);
            throw ex;
        }
    }

    /** 发布当前增量块，完整结果由 Spring AI MessageAggregator 独立聚合。 */
    private void publishChunk(ChatResponse response,
                              AgentTokenStreamContext streamContext,
                              String streamId,
                              AtomicLong chunkIndex) {
        String content = text(response);
        if (content != null && !content.isEmpty()) {
            tokenStreamSink.publishToken(
                    streamContext, streamId, chunkIndex.incrementAndGet(), content);
        }
    }

    /** 从 Spring AI ChatResponse 安全提取当前增量助手文本。 */
    private String text(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    /**
     * 修复 OpenAI-compatible 流偶发丢失首个对象起始花括号的问题。
     *
     * <p>只接受“正文以 JSON 属性名开始、以对象结束符结束，并且补一个左花括号后可被
     * Jackson 严格解析为对象”这一种情况；其他非法输出保持原样交给 BeanOutputConverter 拒绝。</p>
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
