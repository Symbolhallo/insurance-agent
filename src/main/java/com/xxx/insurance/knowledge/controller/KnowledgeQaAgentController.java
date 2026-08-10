package com.xxx.insurance.knowledge.controller;

import com.xxx.insurance.common.result.ApiResponse;
import com.xxx.insurance.knowledge.agent.KnowledgeQaAgent;
import com.xxx.insurance.knowledge.model.KnowledgeQaChatRequest;
import com.xxx.insurance.knowledge.model.KnowledgeQaChatResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "KnowledgeQAAgent", description = "保险业务知识问答智能体接口")
@RestController
@RequestMapping("/api/v1/knowledge-qa-agent")
public class KnowledgeQaAgentController {

    private final KnowledgeQaAgent knowledgeQaAgent;

    public KnowledgeQaAgentController(KnowledgeQaAgent knowledgeQaAgent) {
        this.knowledgeQaAgent = knowledgeQaAgent;
    }

    @Operation(
            summary = "调用保险业务知识问答智能体",
            description = "触发 KnowledgeQAAgent 的 ReactAgent 和 insurance_knowledge_search Tool Calling 闭环。")
    @PostMapping("/chat")
    public ApiResponse<KnowledgeQaChatResponse> chat(@Valid @RequestBody KnowledgeQaChatRequest request) {
        return ApiResponse.success(knowledgeQaAgent.chat(request));
    }
}
