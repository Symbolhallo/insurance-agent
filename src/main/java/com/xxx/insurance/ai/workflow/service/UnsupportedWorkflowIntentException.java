package com.xxx.insurance.ai.workflow.service;

/** 表示用户请求不属于主工作流当前开放的四类业务意图。 */
public class UnsupportedWorkflowIntentException extends IllegalStateException {

    public static final String USER_MESSAGE =
            "该问题暂不在当前支持范围内。目前支持产品分析、保险知识问答、保单查询和资产查询，其他功能暂不支持。";

    public UnsupportedWorkflowIntentException() {
        super("Workflow intent is not currently supported");
    }
}
