alter table ai_workflow_sse_event
    modify column event_type varchar(32) not null
        comment '事件类型：start、stage、human_confirm、summary、review、agent_stream、complete、error';

update ai_workflow_definition
set description = 'Phase2 主工作流：SSE 内部使用 ReactAgent.stream 执行，未经审核的模型 Token 不外发，仅在输出审核后发布 agent_stream。',
    version = 10,
    updated_at = current_timestamp
where workflow_code = 'main-workflow-v1';
