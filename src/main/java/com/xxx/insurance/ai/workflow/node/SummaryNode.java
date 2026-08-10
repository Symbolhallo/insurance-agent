package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.knowledge.agent.KnowledgeQaAgent;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 结果汇总节点。
 */
@Component
public class SummaryNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(SummaryNode.class);

    /**
     * 读取 DAG 聚合结果并将最终文本写入 Graph State 的 finalAnswer 字段。
     */
    @Override
    public Map<String, Object> apply(OverAllState state) {
        DagExecutionResult result = state.value(
                        MainWorkflowStateKeys.DAG_EXECUTION_RESULT,
                        DagExecutionResult.class)
                .orElseThrow(() -> new IllegalStateException("Missing DAG execution result in graph state"));
        // 主工作流链路 11：按计划顺序汇总成功结果，并明确披露失败或依赖跳过的任务。
        String finalAnswer = aggregate(result);
        log.info("[Workflow] node=summary action=aggregate successCount={} failedCount={} skippedCount={}",
                result.successCount(), result.failedCount(), result.skippedCount());
        return Map.of(MainWorkflowStateKeys.FINAL_ANSWER, finalAnswer);
    }

    /**
     * 按任务顺序拼接成功回答，并追加失败或依赖跳过信息。
     */
    private String aggregate(DagExecutionResult result) {
        List<AgentTaskExecutionResult> successfulTasks = result.taskResults().stream()
                .filter(task -> task.status() == AgentTaskStatus.SUCCESS)
                .toList();
        if (successfulTasks.size() == 1 && result.failedCount() == 0 && result.skippedCount() == 0) {
            return successfulTasks.getFirst().response().answer();
        }

        StringBuilder answer = new StringBuilder();
        successfulTasks.forEach(task -> answer
                .append("## ").append(sectionName(task.agentName())).append('\n')
                .append(task.response().answer()).append("\n\n"));
        List<AgentTaskExecutionResult> incompleteTasks = result.taskResults().stream()
                .filter(task -> task.status() != AgentTaskStatus.SUCCESS)
                .toList();
        if (!incompleteTasks.isEmpty()) {
            answer.append("## 未完成任务\n");
            incompleteTasks.forEach(task -> answer
                    .append("- ").append(task.taskId()).append(" (")
                    .append(task.agentName()).append(")：")
                    .append(task.errorMessage() == null ? task.status().name() : task.errorMessage())
                    .append('\n'));
        }
        if (answer.isEmpty()) {
            return "本次请求的子智能体任务均未成功完成，请稍后重试或联系人工支持。";
        }
        return answer.toString().trim();
    }

    /**
     * 将 Agent 技术名称转换为用户可读的结果章节名称。
     */
    private String sectionName(String agentName) {
        if (ProductAnalysisAgent.AGENT_NAME.equals(agentName)) {
            return "产品分析结果";
        }
        if (KnowledgeQaAgent.AGENT_NAME.equals(agentName)) {
            return "保险知识问答结果";
        }
        return "智能体执行结果";
    }
}
