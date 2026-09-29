package com.xxx.insurance.ai.agui.controller;

import com.xxx.insurance.ai.workflow.config.WorkflowExecutionConfig;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.xxx.insurance.common.security.RequestIdentity;
import com.xxx.insurance.common.security.ResourceAccessService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicReference;

/** AgentScope AG-UI 标准协议入口，供支持 AG-UI 的前端直接订阅领域 Agent 事件流。 */
@RestController
@RequestMapping("/api/v1/agui/agents")
@Tag(name = "AgentScope AG-UI", description = "AgentScope HarnessAgent 的标准 AG-UI SSE 协议")
public class AgentScopeAguiController {

    private final AguiAgentRegistry registry;

    private final AguiAdapterConfig adapterConfig;

    private final AguiEventEncoder eventEncoder;

    private final TaskExecutor taskExecutor;

    private final ResourceAccessService resourceAccessService;

    public AgentScopeAguiController(
            AguiAgentRegistry registry,
            AguiAdapterConfig adapterConfig,
            AguiEventEncoder eventEncoder,
            @Qualifier(WorkflowExecutionConfig.WORKFLOW_SSE_TASK_EXECUTOR) TaskExecutor taskExecutor,
            ResourceAccessService resourceAccessService) {
        this.registry = registry;
        this.adapterConfig = adapterConfig;
        this.eventEncoder = eventEncoder;
        this.taskExecutor = taskExecutor;
        this.resourceAccessService = resourceAccessService;
    }

    /**
     * 将 AG-UI 输入交给注册的 HarnessAgent，并把 AgentScope 生命周期、正文 Token、Tool 调用、
     * Tool 结果、状态和终态事件逐条编码为 SSE。运行放入有界线程池，Servlet 线程只负责建立连接；
     * 客户端断开、超时或正常结束时都会取消 Reactor subscription，避免模型流继续占用资源。
     *
     * <p>该接口是领域 Agent 的标准协议入口，不替代 Main Graph 的 OceanBase Outbox、
     * Last-Event-ID 和跨实例重放接口。</p>
     */
    @Operation(
            summary = "运行 AgentScope AG-UI Agent",
            description = "agentId 支持 product-analysis-agent、knowledge-qa-agent、policy-query-agent、asset-query-agent；返回标准 AG-UI SSE 事件。")
    @PostMapping(value = "/{agentId}/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@PathVariable String agentId,
                          @RequestBody RunAgentInput input,
                          RequestIdentity identity) {
        validate(input);
        Agent agent = registry.getAgent(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported AG-UI agentId: " + agentId));
        // AG-UI threadId 与普通 Agent conversationId 共享所有权根，不能跨租户或用户复用。
        resourceAccessService.claimConversation(identity, input.getThreadId());
        SseEmitter emitter = new SseEmitter(adapterConfig.getRunTimeout().toMillis());
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        Runnable cancel = () -> {
            Disposable disposable = subscription.get();
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());

        try {
            taskExecutor.execute(() -> {
                RuntimeContext runtimeContext = RuntimeContext.builder()
                        .userId(identity.namespacedUserId())
                        .build();
                Disposable disposable = new AguiAgentAdapter(agent, adapterConfig)
                        .run(input, runtimeContext)
                        .subscribe(
                                event -> send(emitter, event),
                                emitter::completeWithError,
                                emitter::complete);
                subscription.set(disposable);
            });
        }
        catch (TaskRejectedException ex) {
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    /** 使用官方编码器生成 AG-UI JSON，由 SseEmitter 添加 data 前缀和事件分隔符。 */
    private void send(SseEmitter emitter, AguiEvent event) {
        try {
            emitter.send(SseEmitter.event().data(eventEncoder.encodeToJson(event)));
        }
        catch (IOException ex) {
            throw new UncheckedIOException("Failed to send AG-UI SSE event", ex);
        }
    }

    private void validate(RunAgentInput input) {
        if (input == null
                || input.getThreadId() == null || input.getThreadId().isBlank()
                || input.getRunId() == null || input.getRunId().isBlank()) {
            throw new IllegalArgumentException("threadId and runId must not be blank");
        }
        if (!input.hasMessages() && !input.hasResume()) {
            throw new IllegalArgumentException("messages or resume must not be empty");
        }
    }
}
