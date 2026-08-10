package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DAG 中单个子智能体任务的终态。
 */
@Schema(description = "DAG 子智能体任务状态")
public enum AgentTaskStatus {

    PENDING,

    READY,

    RUNNING,

    SUCCESS,

    FAILED,

    SKIPPED_DEPENDENCY_FAILED
}
