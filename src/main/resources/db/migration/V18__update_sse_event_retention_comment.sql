alter table ai_workflow_sse_event
    modify column expire_at timestamp not null
        comment '事件重放过期时间，由应用配置计算，当前默认保留十分钟';
