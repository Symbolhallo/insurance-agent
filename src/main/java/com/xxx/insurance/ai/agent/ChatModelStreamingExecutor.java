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

    /** 创建结构化模型流执行器，组合可靠 Token 发布端口和严格 JSON 边界修复所需的 ObjectMapper。 */
    public ChatModelStreamingExecutor(AgentTokenStreamSink tokenStreamSink,
                                      ObjectMapper objectMapper) {
        this.tokenStreamSink = tokenStreamSink;
        this.objectMapper = objectMapper;
    }

    /**
     * 流式执行前置结构化模型节点。为本次调用生成 streamId，逐块提取助手文本并发布带序号 Token，同时
     * 使用 Spring AI MessageAggregator 聚合完整 ChatResponse；拒绝空结果，只对已验证的兼容模式缺失左
     * 花括号做受控修复，然后刷新 Token 尾批次并返回给 BeanOutputConverter。异常时 abort 当前流，不发送
     * 正常结束标记，业务服务继续负责 JSON Schema 转换和确定性校验。
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
        if (response == null) {
            return "";
        }
        if (response.getResult() == null) {
            return "";
        }
        if (response.getResult().getOutput() == null) {
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
