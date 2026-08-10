alter table ai_workflow_instance
    modify column status varchar(32) not null
        comment '实例状态：RUNNING、SUCCESS、PARTIAL_SUCCESS、FAILED、REVIEW_BLOCKED、WAITING_CONFIRM';

update ai_workflow_definition
set description = 'Phase2 主工作流：DAG Executor 完成任务汇聚后调用行内输出审核节点，只有 publishableAnswer 可以进入 Summary 和最终会话记忆。',
    definition_json = '{"graph":"main-workflow-v1","nodes":["resolve-product-reference","retrieve-product-candidates","human-confirm-product","context-alignment","intent-recognition","planner-agent","dag-executor","output-review","summary"],"edges":[["START","resolve-product-reference"],["retrieve-product-candidates","human-confirm-product"],["human-confirm-product","context-alignment"],["context-alignment","intent-recognition"],["intent-recognition","planner-agent"],["planner-agent","dag-executor"],["dag-executor","output-review"],["output-review","summary"],["summary","END"]],"conditionalEdges":[{"source":"resolve-product-reference","decisionState":"productRecallDecision","routes":{"recall":"retrieve-product-candidates","skip":"context-alignment"}}],"supportedIntents":["PRODUCT_ANALYSIS","KNOWLEDGE_QA","MULTI_INTENT"],"supportedAgents":["product-analysis-agent","knowledge-qa-agent"],"plannerVersion":2,"executor":"BOUNDED_DYNAMIC_DAG","outputReview":"LINE_SERVICE_GATEWAY","interruptBefore":["human-confirm-product"]}',
    version = 7,
    updated_at = current_timestamp
where workflow_code = 'main-workflow-v1';
