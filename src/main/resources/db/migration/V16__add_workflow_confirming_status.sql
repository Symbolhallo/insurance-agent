alter table ai_workflow_instance
    modify column status varchar(32) not null
        comment '实例状态：RUNNING、WAITING_CONFIRM、CONFIRMING、RESUMING、SUCCESS、PARTIAL_SUCCESS、FAILED、REVIEW_BLOCKED';
