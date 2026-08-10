alter table ai_graph_thread
    drop index uk_ai_graph_thread_workflow_instance;

create index idx_ai_graph_thread_workflow_instance
    on ai_graph_thread (workflow_instance_id);
