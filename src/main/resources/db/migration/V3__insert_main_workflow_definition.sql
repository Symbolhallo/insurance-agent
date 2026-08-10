alter table ai_workflow_step
    modify column step_type varchar(32) not null comment '步骤类型：SYSTEM、MODEL、AGENT、TOOL、RETRIEVAL、HUMAN_CONFIRM';

insert into ai_workflow_definition (
    workflow_code,
    workflow_name,
    description,
    definition_json,
    status,
    version
) values (
    'main-workflow-v1',
    '主工作流 v1',
    'Phase2 主工作流模板，基于 Spring AI Alibaba Graph 串联上下文对齐、意图识别、Planner Agent、产品分析智能体调用和结果汇总节点。',
    '{"graph":"main-workflow-v1","nodes":["context-alignment","intent-recognition","planner-agent","product-analysis-agent","summary"],"edges":[["START","context-alignment"],["context-alignment","intent-recognition"],["intent-recognition","planner-agent"],["planner-agent","product-analysis-agent"],["product-analysis-agent","summary"],["summary","END"]]}',
    'ENABLED',
    1
) on duplicate key update
    workflow_name = values(workflow_name),
    description = values(description),
    definition_json = values(definition_json),
    status = values(status),
    version = values(version),
    updated_at = current_timestamp;
