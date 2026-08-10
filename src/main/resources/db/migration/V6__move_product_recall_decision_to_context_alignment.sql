update ai_workflow_definition
set description = 'Phase2 主工作流模板：上下文对齐统一完成话题关系、问题改写、确认信息提取和产品候选召回判断，再执行意图识别、Mock 候选召回、Planner Agent、产品分析智能体和结果汇总。',
    definition_json = '{"graph":"main-workflow-v1","nodes":["context-alignment","intent-recognition","retrieve-product-candidates","planner-agent","product-analysis-agent","summary"],"edges":[["START","context-alignment"],["context-alignment","intent-recognition"],["retrieve-product-candidates","planner-agent"],["planner-agent","product-analysis-agent"],["product-analysis-agent","summary"],["summary","END"]],"conditionalEdges":[{"source":"intent-recognition","decisionState":"productRecallDecision","routes":{"recall":"retrieve-product-candidates","skip":"planner-agent"}}]}',
    version = 3,
    updated_at = current_timestamp
where workflow_code = 'main-workflow-v1';
