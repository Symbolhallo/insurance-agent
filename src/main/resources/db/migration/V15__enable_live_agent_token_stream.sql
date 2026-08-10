-- Phase2：模型增量内容实时通过 SSE 发布，完整 Summary 生成后再执行最终输出审核。
update ai_workflow_definition
set description = 'Phase2 主工作流：子智能体与 Summary 实时发布模型增量内容，完整 Summary 生成后执行最终输出审核。',
    updated_at = current_timestamp
where workflow_code = 'main-workflow-v1';
