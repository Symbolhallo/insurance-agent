package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.xxx.insurance.ai.agent.ReactAgentStreamingExecutor;
import com.xxx.insurance.ai.workflow.agent.WorkflowSummaryAgent;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.ai.workflow.model.WorkflowSummaryResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SummaryNodeTests {

    @Test
    void writesSummaryResultToGraphState() throws Exception {
        SummaryNode node = new SummaryNode(new WorkflowSummaryAgent(
                mock(ReactAgent.class), mock(ReactAgentStreamingExecutor.class)));

        Map<String, Object> result = node.apply(new OverAllState(Map.of(
                MainWorkflowStateKeys.DAG_EXECUTION_RESULT, singleTaskResult())));

        assertThat(result.get(MainWorkflowStateKeys.SUMMARY_RESULT))
                .isInstanceOfSatisfying(WorkflowSummaryResult.class, summary -> {
                    assertThat(summary.modelInvoked()).isFalse();
                    assertThat(summary.answer()).isEqualTo("知识问答结果");
                });
    }

    private DagExecutionResult singleTaskResult() {
        SubAgentExecutionResult response = new SubAgentExecutionResult(
                "knowledge-qa-agent", "conversation-001", "kqa-001", "知识问答结果",
                true, 100, Instant.parse("2026-08-10T00:00:01Z"), 6, false, 0);
        return DagExecutionResult.from(List.of(new AgentTaskExecutionResult(
                "task-1", 1, "knowledge-qa-agent", AgentTaskStatus.SUCCESS, response,
                null, null, Instant.parse("2026-08-10T00:00:00Z"),
                Instant.parse("2026-08-10T00:00:01Z"), 100)));
    }
}
