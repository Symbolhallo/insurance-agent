package com.xxx.insurance.ai.workflow.model;

import com.xxx.insurance.ai.workflow.agent.WorkflowPlannerAgent;

/**
 * Main Graph v1 节点定义。
 */
public enum WorkflowNodeDefinition {

    PRODUCT_REFERENCE_RESOLUTION(
            "resolve-product-reference", "产品实体解析", "MODEL", "product-reference-resolution"),

    PRODUCT_CANDIDATE_RETRIEVAL(
            "retrieve-product-candidates", "产品候选召回", "RETRIEVAL", "product-recall-service"),

    HUMAN_CONFIRM_PRODUCT(
            "human-confirm-product", "产品候选人工确认", "HUMAN_CONFIRM", "product-confirmation"),

    CONTEXT_ALIGNMENT("context-alignment", "上下文对齐", "MODEL", "context-alignment"),

    INTENT_RECOGNITION("intent-recognition", "意图识别", "SYSTEM", "intent-recognition"),

    PLANNER("planner-agent", "任务规划智能体", "AGENT", WorkflowPlannerAgent.AGENT_NAME),

    DAG_EXECUTOR("dag-executor", "DAG任务执行", "EXECUTOR", "workflow-dag-executor"),

    SUMMARY("summary", "结果汇总", "SYSTEM", "summary");

    private final String code;

    private final String name;

    private final String type;

    private final String target;

    WorkflowNodeDefinition(String code, String name, String type, String target) {
        this.code = code;
        this.name = name;
        this.type = type;
        this.target = target;
    }

    public String code() {
        return code;
    }

    public String nodeName() {
        return name;
    }

    public String type() {
        return type;
    }

    public String target() {
        return target;
    }
}
