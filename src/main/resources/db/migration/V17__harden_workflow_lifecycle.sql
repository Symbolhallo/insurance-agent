alter table ai_workflow_instance
    add column request_id varchar(64) null
        comment '调用方请求幂等编号，在同一会话内唯一',
    add column execution_owner varchar(128) null
        comment '当前持有工作流执行租约的应用实例编号',
    add column lease_until timestamp null
        comment '当前执行租约截止时间，过期的瞬时抢占可被恢复任务回收',
    add column state_version bigint not null default 0
        comment '工作流业务状态版本，每次状态迁移递增';

alter table ai_workflow_instance
    add unique key uk_ai_workflow_instance_conversation_request (conversation_id, request_id),
    add key idx_ai_workflow_instance_status_lease (status, lease_until);

create table if not exists ai_conversation_workflow_lock (
    conversation_id varchar(64) not null comment '会话编号，同一时间只允许一个顶层工作流持有',
    workflow_instance_id varchar(64) not null comment '当前占用会话的工作流实例编号',
    request_id varchar(64) not null comment '当前工作流请求幂等编号',
    lease_until timestamp not null comment '会话占用租约；活动实例由生命周期任务续期或回收',
    created_at timestamp not null default current_timestamp comment '创建时间',
    updated_at timestamp not null default current_timestamp comment '更新时间',
    primary key (conversation_id),
    unique key uk_ai_conversation_workflow_lock_instance (workflow_instance_id),
    key idx_ai_conversation_workflow_lock_lease (lease_until)
) default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = '会话顶层工作流并发锁表';
