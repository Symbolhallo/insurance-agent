package com.xxx.insurance.ai.workflow.controller;

import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.service.WorkflowSseService;
import com.xxx.insurance.product.model.ProductConfirmationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Main Workflow 实时 SSE 启动、人工确认续流与断线重连接口。
 */
@Tag(name = "MainWorkflowSse", description = "主工作流 SSE 运行、持久化重放和断线重连")
@RestController
@Profile("local-db")
@RequestMapping("/api/v1/workflows/main")
public class MainWorkflowSseController {

    private final WorkflowSseService workflowSseService;

    /** 创建 SSE Controller 并注入工作流流式应用服务。 */
    public MainWorkflowSseController(WorkflowSseService workflowSseService) {
        this.workflowSseService = workflowSseService;
    }

    /** 启动新的后台 Main Graph，并持续返回带 id 和 event 名称的阶段事件。 */
    @Operation(
            summary = "以 SSE 启动 Main Graph",
            description = "先建立SSE订阅，再异步执行产品实体解析、可选候选确认、上下文对齐、意图识别、"
                    + "Planner、动态DAG、Summary和输出审核；返回start、stage、human_confirm、agent_start、"
                    + "agent_stream、agent_complete、summary、review、complete或error事件，全部写入OceanBase。")
    @PostMapping(value = "/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRun(@Valid @RequestBody MainWorkflowRequest request) {
        // 主工作流链路 1：校验请求后进入 SSE 应用服务；本 HTTP 线程不直接执行模型或 Graph。
        return workflowSseService.start(request);
    }

    /** 使用 Last-Event-ID 重放遗漏事件；实例仍运行时自动衔接后续实时事件。 */
    @Operation(
            summary = "重连 Main Graph SSE",
            description = "Last-Event-ID 格式为 workflowInstanceId:sequence；缺省时从第一条尚未过期事件开始重放。")
    @GetMapping(value = "/runs/{workflowInstanceId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reconnect(
            @PathVariable String workflowInstanceId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        return workflowSseService.reconnect(workflowInstanceId, lastEventId);
    }

    /** 提交产品确认，并从人工中断点继续实时返回后续工作流事件。 */
    @Operation(
            summary = "确认产品候选并以 SSE 恢复 Main Graph",
            description = "建议携带 human_confirm 的事件 ID；服务先原子抢占确认权，再重放遗漏事件、建立订阅并从 Checkpoint 恢复执行。")
    @PostMapping(
            value = "/runs/{workflowInstanceId}/product-confirmations/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter confirmProducts(
            @PathVariable String workflowInstanceId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @Valid @RequestBody ProductConfirmationRequest request) {
        return workflowSseService.confirmProducts(workflowInstanceId, request, lastEventId);
    }
}
