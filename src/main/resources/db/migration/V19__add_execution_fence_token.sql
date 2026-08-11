alter table ai_workflow_instance
    add column execution_fence_token bigint not null default 1
        comment '执行权代次；每次抢占或接管时递增，用于拒绝旧执行者迟到写入'
        after lease_until;

update ai_workflow_instance
set execution_fence_token = greatest(state_version, 1);
