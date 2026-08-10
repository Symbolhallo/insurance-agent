update ai_workflow_definition
set description = 'Phase2 主工作流模板，基于 Spring AI Alibaba Graph 串联上下文对齐、意图识别、产品召回判断、Mock 候选召回、Planner Agent、产品分析智能体调用和结果汇总节点。',
    definition_json = '{"graph":"main-workflow-v1","nodes":["context-alignment","intent-recognition","check-product-recall","retrieve-product-candidates","planner-agent","product-analysis-agent","summary"],"edges":[["START","context-alignment"],["context-alignment","intent-recognition"],["intent-recognition","check-product-recall"],["retrieve-product-candidates","planner-agent"],["planner-agent","product-analysis-agent"],["product-analysis-agent","summary"],["summary","END"]],"conditionalEdges":[{"source":"check-product-recall","routes":{"recall":"retrieve-product-candidates","skip":"planner-agent"}}]}',
    version = 2,
    updated_at = current_timestamp
where workflow_code = 'main-workflow-v1';
