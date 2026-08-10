alter table ai_workflow_instance
    add column event_sequence bigint not null default 0
        comment '当前工作流已分配的 SSE 事件最大序号';

create table if not exists ai_workflow_sse_event (
    event_id varchar(96) not null comment '事件编号，格式为 workflowInstanceId:sequenceNo',
    workflow_instance_id varchar(64) not null comment '关联工作流实例编号',
    conversation_id varchar(64) not null comment '关联会话编号',
    sequence_no bigint not null comment '工作流内单调递增事件序号',
    event_type varchar(32) not null comment '事件类型：start、stage、human_confirm、summary、review、complete、error',
    node_code varchar(128) null comment '关联 Graph 节点编码，工作流级事件为空',
    payload_json longtext not null comment '已脱敏的前端事件数据 JSON',
    created_at timestamp not null default current_timestamp comment '事件发生时间',
    expire_at timestamp not null comment '事件重放过期时间，默认完成后保留七天',
    primary key (event_id),
    unique key uk_ai_workflow_sse_event_instance_sequence (workflow_instance_id, sequence_no),
    key idx_ai_workflow_sse_event_expire_at (expire_at),
    key idx_ai_workflow_sse_event_conversation_created (conversation_id, created_at)
) default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = 'Workflow SSE 持久化重放事件表';

update ai_workflow_definition
set description = 'Phase2 主工作流：支持单/多智能体汇总、行内输出审核，以及可通过 Last-Event-ID 重放的 OceanBase 阶段级 SSE。',
    version = 9,
    updated_at = current_timestamp
where workflow_code = 'main-workflow-v1';
