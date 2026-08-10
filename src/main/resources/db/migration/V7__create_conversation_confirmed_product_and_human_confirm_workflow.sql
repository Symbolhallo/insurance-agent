create table if not exists ai_conversation_confirmed_product (
    confirmation_id varchar(64) not null comment '产品确认记录编号',
    conversation_id varchar(64) not null comment '确认结果生效的会话编号',
    product_code varchar(64) not null comment '用户确认的标准产品编码',
    product_name varchar(256) not null comment '用户确认的标准产品名称',
    product_type varchar(128) null comment '产品类型',
    insurer_name varchar(256) null comment '保险公司名称',
    source_clue varchar(512) null comment '触发候选召回的原始产品线索',
    retrieval_call_id varchar(64) null comment '关联产品召回调用编号',
    workflow_instance_id varchar(64) null comment '执行人工确认的工作流实例编号',
    status varchar(32) not null default 'ACTIVE' comment '会话确认状态：ACTIVE、INACTIVE',
    confirmed_at timestamp not null comment '用户确认时间',
    last_used_at timestamp not null comment '该产品在当前会话中的最近使用时间',
    created_at timestamp not null default current_timestamp comment '创建时间',
    updated_at timestamp not null default current_timestamp comment '更新时间',
    primary key (confirmation_id),
    unique key uk_ai_conversation_confirmed_product (conversation_id, product_code),
    key idx_ai_confirmed_product_conversation_status (conversation_id, status, last_used_at),
    key idx_ai_confirmed_product_workflow (workflow_instance_id),
    key idx_ai_confirmed_product_retrieval (retrieval_call_id)
) default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = '会话级用户确认产品表';

alter table ai_workflow_instance
    modify column status varchar(32) not null comment '实例状态：RUNNING、WAITING_CONFIRM、SUCCESS、FAILED';

alter table ai_workflow_step
    modify column status varchar(32) not null comment '步骤状态：PENDING、RUNNING、WAITING_CONFIRM、SUCCESS、FAILED、SKIPPED';

update ai_workflow_definition
set description = 'Phase2 主工作流：先解析当前会话确认产品；首次、模糊或无法映射的产品线索进入候选召回和 Human Confirm，确认后携带标准产品进入上下文对齐、意图识别和执行编排。',
    definition_json = '{"graph":"main-workflow-v1","nodes":["resolve-product-reference","retrieve-product-candidates","human-confirm-product","context-alignment","intent-recognition","planner-agent","product-analysis-agent","summary"],"edges":[["START","resolve-product-reference"],["retrieve-product-candidates","human-confirm-product"],["human-confirm-product","context-alignment"],["context-alignment","intent-recognition"],["intent-recognition","planner-agent"],["planner-agent","product-analysis-agent"],["product-analysis-agent","summary"],["summary","END"]],"conditionalEdges":[{"source":"resolve-product-reference","decisionState":"productRecallDecision","routes":{"recall":"retrieve-product-candidates","skip":"context-alignment"}}],"interruptBefore":["human-confirm-product"]}',
    version = 4,
    updated_at = current_timestamp
where workflow_code = 'main-workflow-v1';
