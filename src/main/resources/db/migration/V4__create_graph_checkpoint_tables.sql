create table if not exists ai_graph_thread (
    thread_id varchar(64) not null comment 'Graph 线程编号，当前使用 Workflow 实例编号',
    workflow_instance_id varchar(64) null comment '关联 Workflow 实例编号',
    conversation_id varchar(64) null comment '关联会话编号，用于审计检索，不作为 Checkpoint 隔离键',
    status varchar(32) not null default 'ACTIVE' comment '线程状态：ACTIVE、COMPLETED、FAILED、RELEASED',
    latest_checkpoint_id varchar(64) null comment '当前线程最新 Checkpoint 编号',
    version bigint not null default 0 comment '线程乐观锁版本，同时作为 Checkpoint 单调序号来源',
    expires_at timestamp not null comment '完整 Checkpoint 数据过期时间',
    released_at timestamp null comment 'Graph release 调用时间',
    created_at timestamp not null default current_timestamp comment '创建时间',
    updated_at timestamp not null default current_timestamp comment '更新时间',
    primary key (thread_id),
    unique key uk_ai_graph_thread_workflow_instance (workflow_instance_id),
    key idx_ai_graph_thread_conversation (conversation_id),
    key idx_ai_graph_thread_status_expires (status, expires_at),
    key idx_ai_graph_thread_updated_at (updated_at)
) default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = 'Spring AI Alibaba Graph Checkpoint 线程表';

create table if not exists ai_graph_checkpoint (
    checkpoint_id varchar(64) not null comment 'Checkpoint 编号，由 Spring AI Alibaba Graph 生成',
    thread_id varchar(64) not null comment 'Graph 线程编号',
    parent_checkpoint_id varchar(64) null comment '父 Checkpoint 编号，用于恢复分支和状态更新追踪',
    checkpoint_version bigint not null comment '线程内单调递增版本号',
    node_id varchar(256) not null comment '生成 Checkpoint 时已完成的节点编号',
    next_node_id varchar(256) not null comment '恢复执行时的下一节点编号',
    state_payload longblob not null comment '使用框架 StateSerializer 序列化后的完整 State 二进制载荷',
    state_content_type varchar(128) not null comment '序列化内容类型，用于反序列化兼容检查',
    state_schema_version int not null default 1 comment '项目 State Schema 版本号',
    created_at timestamp not null default current_timestamp comment 'Checkpoint 创建时间',
    primary key (checkpoint_id),
    unique key uk_ai_graph_checkpoint_thread_version (thread_id, checkpoint_version),
    key idx_ai_graph_checkpoint_thread_created (thread_id, created_at),
    key idx_ai_graph_checkpoint_parent (parent_checkpoint_id)
) default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = 'Spring AI Alibaba Graph Checkpoint 状态表';
