create table if not exists ai_conversation (
    conversation_id varchar(64) not null comment '会话编号，外部请求传入或系统生成',
    user_id varchar(64) not null default 'mock-user' comment '用户编号，当前阶段使用 mock 值',
    customer_id varchar(64) not null default 'mock-customer' comment '客户编号，当前阶段使用 mock 值',
    operator_id varchar(64) not null default 'mock-operator' comment '操作员编号，当前阶段使用 mock 值',
    session_type varchar(32) not null default 'INTERNAL_TEST' comment '会话类型，例如 INTERNAL_TEST、CUSTOMER_SERVICE',
    agent_name varchar(128) not null comment '会话归属智能体名称',
    title varchar(256) null comment '会话标题',
    status varchar(32) not null default 'ACTIVE' comment '会话状态：ACTIVE、CLOSED',
    created_at timestamp not null default current_timestamp comment '创建时间',
    updated_at timestamp not null default current_timestamp comment '更新时间',
    primary key (conversation_id),
    key idx_ai_conversation_user_id (user_id),
    key idx_ai_conversation_agent_status (agent_name, status),
    key idx_ai_conversation_updated_at (updated_at)
) default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = 'AI 会话表';

create table if not exists ai_chat_memory (
    message_id varchar(64) not null comment '消息编号，系统生成',
    conversation_id varchar(64) not null comment '会话编号，对应 Spring AI ChatMemory conversationId',
    message_order int not null comment '当前会话窗口内消息顺序，从 0 开始',
    message_type varchar(32) not null comment 'Spring AI 消息类型：USER、ASSISTANT、SYSTEM、TOOL',
    text_content longtext not null comment '消息文本内容',
    metadata_json longtext null comment 'Spring AI Message metadata，JSON 字符串',
    created_at timestamp not null default current_timestamp comment '创建时间',
    primary key (message_id),
    unique key uk_ai_chat_memory_conversation_order (conversation_id, message_order),
    key idx_ai_chat_memory_conversation_id (conversation_id),
    key idx_ai_chat_memory_created_at (created_at)
) default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = 'Spring AI ChatMemory 消息窗口表';

create table if not exists ai_agent_invocation (
    invocation_id varchar(64) not null comment '单次 Agent 调用编号',
    conversation_id varchar(64) not null comment '会话编号',
    agent_name varchar(128) not null comment '智能体名称',
    trace_id varchar(64) null comment 'HTTP 链路追踪编号',
    workflow_instance_id varchar(64) null comment '关联工作流实例编号',
    workflow_step_id varchar(64) null comment '关联工作流步骤编号',
    model_provider varchar(64) null comment '模型供应商或协议，例如 openai-compatible',
    model_name varchar(128) null comment '模型名称，例如 deepseek-chat、qwen-plus',
    user_id varchar(64) not null default 'mock-user' comment '用户编号，当前阶段使用 mock 值',
    customer_id varchar(64) not null default 'mock-customer' comment '客户编号，当前阶段使用 mock 值',
    operator_id varchar(64) not null default 'mock-operator' comment '操作员编号，当前阶段使用 mock 值',
    user_message longtext null comment '用户输入，当前允许明文存储',
    assistant_answer longtext null comment '智能体回答，当前允许明文存储',
    duration_ms bigint null comment 'Agent 调用耗时，单位毫秒',
    answer_length int null comment '回答字符长度',
    output_format_valid tinyint(1) null comment '回答是否满足 Skill 输出格式合同',
    missing_sections longtext null comment '缺失输出小标题，JSON 字符串',
    status varchar(32) not null comment '调用状态：SUCCESS、FAILED',
    error_code varchar(64) null comment '错误码',
    error_message varchar(1024) null comment '错误信息',
    created_at timestamp not null default current_timestamp comment '创建时间',
    primary key (invocation_id),
    key idx_ai_agent_invocation_conversation_created (conversation_id, created_at),
    key idx_ai_agent_invocation_trace_id (trace_id),
    key idx_ai_agent_invocation_agent_status (agent_name, status),
    key idx_ai_agent_invocation_workflow_step (workflow_instance_id, workflow_step_id)
) default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = 'Agent 单次调用观测与审计表';

create table if not exists ai_conversation_summary (
    summary_id varchar(64) not null comment '摘要编号，系统生成',
    conversation_id varchar(64) not null comment '会话编号',
    agent_name varchar(128) not null comment '智能体名称',
    summary longtext not null comment '会话摘要内容',
    source_message_start_id varchar(64) null comment '摘要覆盖的起始消息编号',
    source_message_end_id varchar(64) null comment '摘要覆盖的结束消息编号',
    created_at timestamp not null default current_timestamp comment '创建时间',
    primary key (summary_id),
    key idx_ai_conversation_summary_conversation_created (conversation_id, created_at),
    key idx_ai_conversation_summary_agent (agent_name)
) default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = 'AI 会话摘要表';

create table if not exists ai_workflow_definition (
    workflow_code varchar(128) not null comment '工作流模板编码',
    workflow_name varchar(256) not null comment '工作流模板名称',
    description varchar(1024) null comment '工作流模板描述',
    definition_json longtext not null comment '工作流定义 JSON',
    status varchar(32) not null default 'ENABLED' comment '模板状态：ENABLED、DISABLED',
    version int not null default 1 comment '模板版本号',
    created_at timestamp not null default current_timestamp comment '创建时间',
    updated_at timestamp not null default current_timestamp comment '更新时间',
    primary key (workflow_code)
) default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = 'Workflow 模板定义表';

create table if not exists ai_workflow_instance (
    workflow_instance_id varchar(64) not null comment '工作流实例编号',
    workflow_code varchar(128) not null comment '工作流模板编码',
    conversation_id varchar(64) not null comment '会话编号',
    trace_id varchar(64) null comment 'HTTP 链路追踪编号',
    status varchar(32) not null comment '实例状态：RUNNING、SUCCESS、FAILED、WAITING_CONFIRM',
    input_json longtext null comment '工作流输入 JSON',
    output_json longtext null comment '工作流输出 JSON',
    error_message varchar(1024) null comment '错误信息',
    created_at timestamp not null default current_timestamp comment '创建时间',
    updated_at timestamp not null default current_timestamp comment '更新时间',
    primary key (workflow_instance_id),
    key idx_ai_workflow_instance_conversation_created (conversation_id, created_at),
    key idx_ai_workflow_instance_workflow_status (workflow_code, status),
    key idx_ai_workflow_instance_trace_id (trace_id)
) default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = 'Workflow 执行实例表';

create table if not exists ai_workflow_step (
    workflow_step_id varchar(64) not null comment '工作流步骤编号',
    workflow_instance_id varchar(64) not null comment '工作流实例编号',
    step_code varchar(128) not null comment '步骤编码',
    step_name varchar(256) null comment '步骤名称',
    step_type varchar(32) not null comment '步骤类型：AGENT、TOOL、RETRIEVAL、HUMAN_CONFIRM',
    target varchar(256) not null comment '步骤目标，例如 Agent 名称、Tool 名称或召回端口',
    status varchar(32) not null comment '步骤状态：PENDING、RUNNING、SUCCESS、FAILED、SKIPPED',
    input_json longtext null comment '步骤输入 JSON',
    output_json longtext null comment '步骤输出 JSON',
    error_message varchar(1024) null comment '错误信息',
    started_at timestamp null comment '步骤开始时间',
    ended_at timestamp null comment '步骤结束时间',
    created_at timestamp not null default current_timestamp comment '创建时间',
    updated_at timestamp not null default current_timestamp comment '更新时间',
    primary key (workflow_step_id),
    key idx_ai_workflow_step_instance_status (workflow_instance_id, status),
    key idx_ai_workflow_step_code (step_code),
    key idx_ai_workflow_step_type_target (step_type, target)
) default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = 'Workflow 执行步骤表';

create table if not exists ai_retrieval_call (
    retrieval_call_id varchar(64) not null comment '召回调用编号',
    conversation_id varchar(64) null comment '会话编号',
    invocation_id varchar(64) null comment 'Agent 调用编号',
    workflow_instance_id varchar(64) null comment '工作流实例编号',
    domain varchar(64) not null comment '召回领域：product、policy、knowledge、asset',
    query_text longtext not null comment '召回查询文本',
    top_k int not null comment '召回条数',
    filters_json longtext null comment '过滤条件 JSON',
    result_json longtext null comment '召回结果 JSON',
    duration_ms bigint null comment '召回耗时，单位毫秒',
    status varchar(32) not null comment '调用状态：SUCCESS、FAILED',
    error_message varchar(1024) null comment '错误信息',
    created_at timestamp not null default current_timestamp comment '创建时间',
    primary key (retrieval_call_id),
    key idx_ai_retrieval_call_invocation_id (invocation_id),
    key idx_ai_retrieval_call_workflow_instance_id (workflow_instance_id),
    key idx_ai_retrieval_call_domain_status (domain, status),
    key idx_ai_retrieval_call_created_at (created_at)
) default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = '外部向量召回调用记录表';

insert into ai_workflow_definition (
    workflow_code,
    workflow_name,
    description,
    definition_json,
    status,
    version
) values (
    'product-analysis-only',
    '产品分析单节点工作流',
    'Phase2 初始工作流模板，仅包含 ProductAnalysisAgent 单节点，后续可扩展知识召回、保单查询和人工确认节点。',
    '{"steps":[{"stepCode":"product-analysis","stepType":"AGENT","target":"product-analysis-agent"}]}',
    'ENABLED',
    1
) on duplicate key update
    workflow_name = values(workflow_name),
    description = values(description),
    definition_json = values(definition_json),
    status = values(status),
    version = values(version),
    updated_at = current_timestamp;
