alter table ai_conversation
    add column tenant_id varchar(64) not null default 'local-tenant' comment '租户编号，作为会话数据隔离根边界' after conversation_id,
    add key idx_ai_conversation_tenant_user_status (tenant_id, user_id, status);

alter table ai_workflow_instance
    add column tenant_id varchar(64) not null default 'local-tenant' comment '工作流所属租户编号' after conversation_id,
    add column user_id varchar(64) not null default 'mock-user' comment '工作流所属用户编号' after tenant_id,
    add column customer_id varchar(64) not null default 'MOCK-CUSTOMER-001' comment '工作流关联客户编号' after user_id,
    add column operator_id varchar(64) not null default 'mock-operator' comment '发起工作流的操作员编号' after customer_id,
    add key idx_ai_workflow_instance_tenant_user_created (tenant_id, user_id, created_at);

alter table ai_agent_invocation
    add column tenant_id varchar(64) not null default 'local-tenant' comment '调用流水所属租户编号' after conversation_id,
    add key idx_ai_agent_invocation_tenant_user_created (tenant_id, user_id, created_at);

alter table ai_long_term_memory
    add column tenant_id varchar(64) not null default 'local-tenant' comment '长期记忆所属租户编号' after conversation_id,
    add key idx_ai_long_term_memory_tenant_user_occurred (tenant_id, user_id, occurred_at);
