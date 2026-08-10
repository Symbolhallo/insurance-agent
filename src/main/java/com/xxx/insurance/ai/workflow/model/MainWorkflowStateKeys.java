package com.xxx.insurance.ai.workflow.model;

import java.util.Set;

/**
 * Main Graph 状态键。
 */
public final class MainWorkflowStateKeys {

    public static final String REQUEST = "request";

    public static final String ALIGNED_CONTEXT = "alignedContext";

    public static final String PRODUCT_REFERENCE_RESOLUTION = "productReferenceResolution";

    public static final String INTENT_ROUTING_RESULT = "intentRoutingResult";

    public static final String PRODUCT_RECALL_DECISION = "productRecallDecision";

    public static final String PRODUCT_RECALL_RESULT = "productRecallResult";

    public static final String RESOLVED_PRODUCTS = "resolvedProducts";

    public static final String HUMAN_CONFIRM_REQUIRED = "humanConfirmRequired";

    public static final String WORKFLOW_PLAN = "workflowPlan";

    public static final String DAG_EXECUTION_RESULT = "dagExecutionResult";

    public static final String SUMMARY_RESULT = "summaryResult";

    public static final String OUTPUT_REVIEW_RESULT = "outputReviewResult";

    public static final String FINAL_ANSWER = "finalAnswer";

    public static final String WORKFLOW_INSTANCE_ID = "workflowInstanceId";

    public static final String WORKFLOW_STEP_IDS = "workflowStepIds";

    public static final String TOKEN_STREAMING_ENABLED = "tokenStreamingEnabled";

    private static final Set<String> ALL_KEYS = Set.of(
            REQUEST,
            ALIGNED_CONTEXT,
            PRODUCT_REFERENCE_RESOLUTION,
            INTENT_ROUTING_RESULT,
            PRODUCT_RECALL_DECISION,
            PRODUCT_RECALL_RESULT,
            RESOLVED_PRODUCTS,
            HUMAN_CONFIRM_REQUIRED,
            WORKFLOW_PLAN,
            DAG_EXECUTION_RESULT,
            SUMMARY_RESULT,
            OUTPUT_REVIEW_RESULT,
            FINAL_ANSWER,
            WORKFLOW_INSTANCE_ID,
            WORKFLOW_STEP_IDS,
            TOKEN_STREAMING_ENABLED);

    private MainWorkflowStateKeys() {
    }

    public static Set<String> all() {
        return ALL_KEYS;
    }
}
