package com.xxx.insurance.ai.workflow.sse.model;

/**
 * 主工作流对前端公开的 SSE 事件类型。
 */
public enum WorkflowSseEventType {

    START("start"),
    STAGE("stage"),
    HUMAN_CONFIRM("human_confirm"),
    AGENT_START("agent_start"),
    AGENT_COMPLETE("agent_complete"),
    SUMMARY("summary"),
    REVIEW("review"),
    AGENT_STREAM("agent_stream"),
    COMPLETE("complete"),
    ERROR("error");

    private final String eventName;

    WorkflowSseEventType(String eventName) {
        this.eventName = eventName;
    }

    /** 返回写入 SSE event 字段的稳定协议名称。 */
    public String eventName() {
        return eventName;
    }
}
