update ai_workflow_definition
set description = 'Phase2 主工作流：产品实体解析和 Human Confirm 后完成上下文对齐，并在产品分析与保险业务知识问答子智能体之间进行单任务路由。',
    definition_json = '{"graph":"main-workflow-v1","nodes":["resolve-product-reference","retrieve-product-candidates","human-confirm-product","context-alignment","intent-recognition","planner-agent","agent-invoke","summary"],"edges":[["START","resolve-product-reference"],["retrieve-product-candidates","human-confirm-product"],["human-confirm-product","context-alignment"],["context-alignment","intent-recognition"],["intent-recognition","planner-agent"],["planner-agent","agent-invoke"],["agent-invoke","summary"],["summary","END"]],"conditionalEdges":[{"source":"resolve-product-reference","decisionState":"productRecallDecision","routes":{"recall":"retrieve-product-candidates","skip":"context-alignment"}}],"supportedIntents":["PRODUCT_ANALYSIS","KNOWLEDGE_QA"],"supportedAgents":["product-analysis-agent","knowledge-qa-agent"],"interruptBefore":["human-confirm-product"]}',
    version = 5,
    updated_at = current_timestamp
where workflow_code = 'main-workflow-v1';
