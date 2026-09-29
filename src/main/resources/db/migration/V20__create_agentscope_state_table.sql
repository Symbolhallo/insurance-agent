create table if not exists ai_agentscope_state (
    user_id varchar(128) not null comment 'AgentScope 用户命名空间；匿名用户统一写为 __anon__',
    session_id varchar(256) not null comment 'AgentScope 会话编号，应用会附加 Agent 命名空间以隔离不同智能体',
    state_key varchar(128) not null comment 'AgentScope 状态键，例如 agent_state 或 memory_messages',
    state_payload longtext not null comment 'AgentScope State 或 State 列表的 JSON 载荷',
    list_value tinyint(1) not null default 0 comment '载荷是否为 State 列表：0 单值，1 列表',
    state_version bigint not null default 1 comment '状态乐观锁版本，CAS 更新成功后递增',
    created_at timestamp not null default current_timestamp comment '创建时间',
    updated_at timestamp not null default current_timestamp comment '最近更新时间',
    primary key (user_id, session_id, state_key),
    key idx_ai_agentscope_state_session (user_id, session_id),
    key idx_ai_agentscope_state_updated_at (updated_at)
) default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = 'AgentScope Harness 会话状态表';

alter table ai_chat_memory
    modify column conversation_id varchar(64) not null
        comment '业务会话编号，对应保险智能体 conversationId',
    modify column message_type varchar(32) not null
        comment 'AgentScope 消息角色：USER、ASSISTANT、SYSTEM、TOOL',
    modify column metadata_json longtext null
        comment 'AgentScope Msg metadata，JSON 字符串';

