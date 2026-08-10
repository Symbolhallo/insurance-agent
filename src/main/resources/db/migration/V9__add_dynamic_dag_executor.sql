alter table ai_workflow_instance
    modify column status varchar(32) not null
        comment '实例状态：RUNNING、SUCCESS、PARTIAL_SUCCESS、FAILED、WAITING_CONFIRM';

update ai_workflow_definition
set description = 'Phase2 主工作流：产品实体确认与上下文对齐后，由 Planner v2 生成一到两个受控任务，DAG Executor 按依赖串行、并行或混合执行并汇总部分失败。',
    definition_json = '{"graph":"main-workflow-v1","nodes":["resolve-product-reference","retrieve-product-candidates","human-confirm-product","context-alignment","intent-recognition","planner-agent","dag-executor","summary"],"edges":[["START","resolve-product-reference"],["retrieve-product-candidates","human-confirm-product"],["human-confirm-product","context-alignment"],["context-alignment","intent-recognition"],["intent-recognition","planner-agent"],["planner-agent","dag-executor"],["dag-executor","summary"],["summary","END"]],"conditionalEdges":[{"source":"resolve-product-reference","decisionState":"productRecallDecision","routes":{"recall":"retrieve-product-candidates","skip":"context-alignment"}}],"supportedIntents":["PRODUCT_ANALYSIS","KNOWLEDGE_QA","MULTI_INTENT"],"supportedAgents":["product-analysis-agent","knowledge-qa-agent"],"plannerVersion":2,"executor":"BOUNDED_DYNAMIC_DAG","interruptBefore":["human-confirm-product"]}',
    version = 6,
    updated_at = current_timestamp
where workflow_code = 'main-workflow-v1';
