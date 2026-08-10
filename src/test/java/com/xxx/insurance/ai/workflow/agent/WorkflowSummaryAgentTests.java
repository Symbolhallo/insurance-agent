package com.xxx.insurance.ai.workflow.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.xxx.insurance.ai.agent.ReactAgentStreamingExecutor;
import com.xxx.insurance.ai.agent.AgentTokenStreamContext;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.ai.workflow.model.WorkflowSummaryResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowSummaryAgentTests {

    @Test
    void passesThroughSingleSuccessfulTaskWithoutCallingModel() throws Exception {
        ReactAgent reactAgent = mock(ReactAgent.class);
        WorkflowSummaryAgent agent = new WorkflowSummaryAgent(reactAgent, mock(ReactAgentStreamingExecutor.class));

        WorkflowSummaryResult result = agent.summarize(dagResult(List.of(successTask("task-1", 1, "回答一"))));

        assertThat(result.modelInvoked()).isFalse();
        assertThat(result.sourceTaskCount()).isEqualTo(1);
        assertThat(result.successfulTaskCount()).isEqualTo(1);
        assertThat(result.answer()).isEqualTo("回答一");
        verify(reactAgent, never()).call(anyString());
    }

    @Test
    void invokesModelWhenMultipleTaskResultsNeedSynthesis() throws Exception {
        ReactAgent reactAgent = mock(ReactAgent.class);
        when(reactAgent.call(anyString())).thenReturn(AssistantMessage.builder().content("统一汇总回答").build());
        WorkflowSummaryAgent agent = new WorkflowSummaryAgent(reactAgent, mock(ReactAgentStreamingExecutor.class));

        WorkflowSummaryResult result = agent.summarize(dagResult(List.of(
                successTask("task-1", 1, "产品分析回答"),
                successTask("task-2", 2, "知识问答回答"))));

        assertThat(result.modelInvoked()).isTrue();
        assertThat(result.sourceTaskCount()).isEqualTo(2);
        assertThat(result.successfulTaskCount()).isEqualTo(2);
        assertThat(result.answer()).isEqualTo("统一汇总回答");
        verify(reactAgent).call(anyString());
    }

    @Test
    void usesStreamingExecutorForMultiTaskSseRun() throws Exception {
        ReactAgent reactAgent = mock(ReactAgent.class);
        ReactAgentStreamingExecutor streamingExecutor = mock(ReactAgentStreamingExecutor.class);
        when(streamingExecutor.execute(
                eq(reactAgent), anyString(), org.mockito.ArgumentMatchers.any(AgentTokenStreamContext.class)))
                .thenReturn(AssistantMessage.builder().content("流式执行后的完整汇总").build());
        WorkflowSummaryAgent agent = new WorkflowSummaryAgent(reactAgent, streamingExecutor);

        WorkflowSummaryResult result = agent.summarize(dagResult(List.of(
                successTask("task-1", 1, "产品分析回答"),
                successTask("task-2", 2, "知识问答回答"))), true,
                "workflow-001", "conversation-001");

        assertThat(result.answer()).isEqualTo("流式执行后的完整汇总");
        verify(streamingExecutor).execute(
                eq(reactAgent), anyString(), org.mockito.ArgumentMatchers.argThat(context ->
                        "workflow-001".equals(context.workflowInstanceId())
                                && "conversation-001".equals(context.conversationId())
                                && "SUMMARY".equals(context.phase())));
        verify(reactAgent, never()).call(anyString());
    }

    @Test
    void returnsDeterministicMessageWhenAllTasksFailed() throws Exception {
        ReactAgent reactAgent = mock(ReactAgent.class);
        AgentTaskExecutionResult failed = new AgentTaskExecutionResult(
                "task-1", 1, "knowledge-qa-agent", AgentTaskStatus.FAILED, null,
                "AGENT_FAILED", "调用失败", instant(0), instant(1), 100);

        WorkflowSummaryResult result = new WorkflowSummaryAgent(
                reactAgent, mock(ReactAgentStreamingExecutor.class))
                .summarize(dagResult(List.of(failed)));

        assertThat(result.modelInvoked()).isFalse();
        assertThat(result.successfulTaskCount()).isZero();
        assertThat(result.answer()).contains("均未成功完成");
        verify(reactAgent, never()).call(anyString());
    }

    @Test
    void summaryInputDisclosesSuccessfulFailedAndSkippedTasks() throws Exception {
        ReactAgent reactAgent = mock(ReactAgent.class);
        when(reactAgent.call(anyString())).thenReturn(AssistantMessage.builder().content("部分成功汇总").build());
        Instant now = instant(1);
        AgentTaskExecutionResult failed = new AgentTaskExecutionResult(
                "task-2", 2, "policy-query-agent", AgentTaskStatus.FAILED, null,
                "AGENT_FAILED", "保单服务不可用", now, now, 1, 2);
        AgentTaskExecutionResult skipped = new AgentTaskExecutionResult(
                "task-3", 3, "asset-query-agent", AgentTaskStatus.SKIPPED_DEPENDENCY_FAILED, null,
                "DEPENDENCY_FAILED", "上游失败", now, now, 0, 0);

        WorkflowSummaryResult result = new WorkflowSummaryAgent(
                reactAgent, mock(ReactAgentStreamingExecutor.class))
                .summarize(dagResult(List.of(successTask("task-1", 1, "产品回答"), failed, skipped)));

        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(reactAgent).call(input.capture());
        assertThat(input.getValue())
                .contains("task-1", "SUCCESS", "产品回答")
                .contains("task-2", "FAILED", "保单服务不可用")
                .contains("task-3", "SKIPPED_DEPENDENCY_FAILED", "上游失败");
        assertThat(result.successfulTaskCount()).isEqualTo(1);
    }

    private DagExecutionResult dagResult(List<AgentTaskExecutionResult> tasks) {
        return DagExecutionResult.from(tasks);
    }

    private AgentTaskExecutionResult successTask(String taskId, int sequence, String answer) {
        SubAgentExecutionResult response = new SubAgentExecutionResult(
                sequence == 1 ? "product-analysis-agent" : "knowledge-qa-agent",
                "conversation-001", "invocation-" + sequence, answer,
                true, 100, instant(1), answer.length(), false, 0);
        return new AgentTaskExecutionResult(
                taskId, sequence, response.agentName(), AgentTaskStatus.SUCCESS, response,
                null, null, instant(0), instant(1), 100);
    }

    private Instant instant(int second) {
        return Instant.parse("2026-08-10T00:00:0" + second + "Z");
    }
}
