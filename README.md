# insurance-agent

基于 Spring AI Alibaba 的保险产品管理智能体技术验证项目。

当前目标是以单模块 Gradle 工程验证金融保险智能体平台的基础架构，后续可演进为多智能体、多模型、多 Workflow 的银行金融智能体平台。

## 技术栈

- Java 21
- Spring Boot 3.5.8
- Gradle
- Spring AI 1.1.2
- Spring AI Alibaba 1.1.2.0
- Lombok

## 当前阶段

Phase1 聚焦 `ProductAnalysisAgent` 单智能体闭环。

已完成：

- Phase1-Task1：工程初始化
- Phase1-Task2：Skill 基础设施
- Phase1-Task3：ProductAnalysisAgent 骨架与 ReactAgent 装配
- Phase1-Task4：产品分析领域模型、Mock Service、Formatter、受控调用边界
- Phase1-Task5：ProductAnalysisTool 与 ReactAgent Tool Calling 集成
- Phase1-Task6：产品分析智能体受控模型调用 API
- Phase1-Task7：统一 API 响应、异常处理与 TraceId 边界
- Phase1-Task8：AI 模型联调状态 API
- Phase1-Task9：Skill 输出格式约束与 DeepSeek 手工联调指南
- Phase1-Task10：Agent 调用耗时与回答格式检查
- Phase1-Task11：Agent 单次调用标识、回答时间与回答长度观测
- Phase2-Task1：Memory / Workflow 前置设计与本地数据库 Flyway 初始化
- Phase2-Task2：Spring AI ChatMemory 本地数据库接入
- Phase2-Task3：长期记忆表与成功请求双写一致性
- Phase2-Task4：Agent 调用流水持久化
- Phase2-Task5：会话主表生命周期维护
- Phase2-Task6：会话记忆快照 Swagger 查询 API
- Phase2-Task7：会话记忆查询侧迁移为 MyBatis Mapper
- Phase2-Task8：会话主表、调用流水、长期记忆写入侧迁移为 MyBatis Mapper
- Phase2-Task9：Spring AI ChatMemoryRepository 适配层迁移为 MyBatis Mapper
- Phase2-Task10：调用模型生成 Conversation Summary 并保存到本地数据库
- Phase2-Task11：Spring AI Alibaba Graph 主工作流 v1 骨架
- Phase2-Task12：Context Alignment 上下文对齐与 Planner Agent v0
- Phase2-Task13：OceanBase Graph Checkpoint Saver 与状态恢复
- Phase2-Task14：上下文对齐召回判断、Mock 产品召回与召回审计
- Phase2-Task15：产品实体前置解析、会话级产品确认与 Graph Checkpoint 暂停恢复
- Phase2-Task16：保险业务知识问答 Agent 与双意图单任务路由
- Phase2-Task17：动态 DAG 执行、任务依赖校验与失败传播
- Phase2-Task18：行内输出审核网关与审核节点
- Phase2-Task19：多子智能体结果总结 Agent
- Phase2-Task20：SSE 持久化事件、断线重连与 Last-Event-ID
- Phase2-Task21：Spring AI Alibaba 1.1.2.0 独立任务子图、并行调度与 Checkpoint 恢复
- Phase2-Task22：主工作流主动恢复 API，以及保单/资产 Agent 的隔离 Skill/Tool 扩展骨架

## 架构边界

当前保持单模块工程，但包结构按未来多模块演进设计：

```text
com.xxx.insurance
├── ai
│   ├── agent
│   ├── config
│   ├── controller
│   ├── memory
│   ├── model
│   ├── service
│   ├── skill
│   ├── tool
│   └── workflow
├── product
│   ├── agent
│   ├── config
│   ├── controller
│   ├── formatter
│   ├── model
│   ├── service
│   ├── skill
│   └── tool
└── common
    ├── config
    ├── exception
    ├── result
    └── util
```

## Skill 路径

不同子智能体的 Skill 必须隔离，避免一个 Agent 加载其他 Agent 的 Skill。

当前产品分析智能体 Skill 路径：

```text
src/main/resources/skills/product-analysis
├── limited-product-analysis
│   └── SKILL.md
└── batch-product-analysis
    └── SKILL.md
```

后续其他智能体可以平级扩展：

```text
skills/
├── product-analysis/
├── policy-query/
├── knowledge-qa/
└── asset-query/
```

## 本地运行

启动测试：

```bash
./gradlew test
```

本地启动：

```bash
AI_API_KEY=test-api-key ./gradlew bootRun
```

使用 DeepSeek：

```text
AI_API_KEY=你的DeepSeek API Key
AI_BASE_URL=https://api.deepseek.com
AI_MODEL=deepseek-chat
```

IDEA 中请把这些配置放到 `Environment variables`，不要放到 `Active profiles`。

Swagger UI：

```text
http://localhost:8080/swagger-ui.html
```

DeepSeek 真实联调步骤：

```text
docs/manual-tests/product-analysis-agent-deepseek.md
```

建议先调用模型状态接口，确认 DeepSeek 环境变量已生效：

```bash
curl -X GET http://localhost:8080/api/v1/ai/model/status \
  -H 'X-Trace-Id: local-model-status-001'
```

本地调用产品分析智能体：

```bash
curl -X POST http://localhost:8080/api/v1/product-analysis-agent/chat \
  -H 'X-Trace-Id: local-test-trace-001' \
  -H 'Content-Type: application/json' \
  -d '{"message":"请分析 PA-001 是否适合长期保障规划","conversationId":"local-test-001"}'
```

查询会话记忆快照：

```bash
curl -X GET 'http://localhost:8080/api/v1/ai/memory/conversations/local-test-001?limit=50' \
  -H 'X-Trace-Id: local-memory-query-001'
```

调用模型生成会话摘要：

```bash
curl -X POST http://localhost:8080/api/v1/ai/memory/conversations/local-test-001/summaries \
  -H 'X-Trace-Id: local-summary-001' \
  -H 'Content-Type: application/json' \
  -d '{"maxMemories":100}'
```

运行主工作流 Main Graph v1：

```bash
curl -X POST http://localhost:8080/api/v1/workflows/main/runs \
  -H 'X-Trace-Id: local-workflow-001' \
  -H 'Content-Type: application/json' \
  -d '{"message":"鑫享人生收益怎么样？","conversationId":"local-test-001","requestId":"req-local-workflow-001"}'
```

当前记忆查询侧使用 MyBatis Mapper 实现；会话主表、调用流水、长期记忆写入侧也已迁移为 MyBatis Mapper。
Spring AI `ChatMemoryRepository` 适配层同样已迁移为 MyBatis Mapper，并保留 `saveAll` 覆盖当前窗口消息列表的语义。

接口统一响应格式：

```json
{
  "success": true,
  "code": "0",
  "message": "success",
  "data": {},
  "traceId": "local-test-trace-001",
  "timestamp": "2026-08-05T00:00:00Z"
}
```

产品分析智能体响应中的 `data` 会包含：

- `invocationId`：单次 Agent 调用标识，可用于对齐响应和日志
- `durationMs`：模型调用耗时，单位毫秒
- `answeredAt`：模型回答生成完成时间
- `answerLength`：模型回答字符长度
- `memoryEnabled`：本次调用是否启用 ChatMemory
- `memoryMessageCount`：调用模型时携带的历史消息数量
- `outputFormatValid`：回答是否满足当前 Skill 输出格式合同
- `missingSections`：缺失的小标题

## 项目记忆

长期开发约束和阶段记录维护在：

```text
AGENTS.md
```

后续使用 Codex/Cursor/IDEA 开发时，请优先遵守该文件中的阶段边界、依赖规则、Skill 规范和禁止事项。

## Phase2 设计

Memory / Workflow 前置设计文档：

```text
docs/design/memory-workflow-pre-design.md
```

Memory 主线采用 Spring AI 原生抽象：

```text
ChatMemory
↓
MessageWindowChatMemory
↓
MyBatisChatMemoryRepository
↓
ai_chat_memory
```

长期记忆单独追加保存：

```text
ProductAnalysisAgent
↓
LongTermMemoryService
↓
ai_long_term_memory
```

启用 `local-db` profile 后，每次成功请求会更新 `ai_chat_memory`，向 `ai_long_term_memory` 追加 USER 和 ASSISTANT 两条长期记忆，upsert `ai_conversation` 会话主记录，并向 `ai_agent_invocation` 追加 SUCCESS 调用流水。
这些表由 `AgentMemoryService` 在同一个事务内协调写入，任一写入失败都会整体回滚。

会话摘要通过手动接口触发，读取 `ai_long_term_memory` 历史消息，调用全局 `ChatModel` 生成结构化摘要，并写入 `ai_conversation_summary`。

当前 Workflow 已接入 Main Graph v1：通过 Spring AI Alibaba Graph 定义 `START -> resolve-product-reference -> (retrieve-product-candidates -> human-confirm-product) -> context-alignment -> intent-recognition -> planner-agent -> dag-executor -> summary -> output-review -> END`。

`resolve-product-reference` 只读取当前 `conversationId` 已确认产品并识别本轮产品线索。首次具体产品、模糊产品名和无法映射的产品追问进入 Mock 候选召回，并在 `human-confirm-product` 前持久化中断；确认接口把标准产品写入 State 后从 OceanBase Checkpoint 恢复。纯条件筛选或唯一映射到当前会话已确认产品的追问直接进入 `context-alignment`。上下文对齐随后读取历史记忆、判断话题延续/切换并完成五步问题改写。意图节点可拆分产品分析、知识问答、保单查询和资产查询，Planner 生成带 `dependsOn` 的受控任务图。`dag-executor` 按单个任务完成事件立即释放后继，不等待无关并行任务；每个任务使用独立 Spring AI Alibaba 子图和 OceanBase Checkpoint thread，恢复时不会重复执行已成功任务。四个领域 Agent 均真实调用全局 ChatModel，并且只能通过各自隔离 Tool 获取 Mock 业务事实。`summary` 对单任务结果直接透传，对多个成功、失败和跳过结果调用独立 ReactAgent 汇总；`output-review` 再通过 `OutputReviewGateway.review(...)` 审核唯一候选答案。

Workflow 调用子智能体时，模型使用 Planner 拆分后的任务指令；子智能体并行阶段只写 `ai_agent_invocation` 审计，Summary 完成后由 Main Workflow 向 `ai_chat_memory` 和 `ai_long_term_memory` 一次性写入用户原话与最终回答，调用流水同时关联 `workflow_instance_id` 与 `workflow_step_id`。

`local-db` profile 还提供 Workflow SSE：`POST /api/v1/workflows/main/runs/stream` 先注册连接，再在独立有界线程池中启动 Graph，发送 `start`、`stage`、`human_confirm`、`agent_start`、`agent_stream`、`agent_complete`、`summary`、`review`、`complete`、`error` 事件；`GET /api/v1/workflows/main/runs/{workflowInstanceId}/events` 可携带 `Last-Event-ID: {workflowInstanceId}:{sequence}` 重放断线后遗漏事件。初始流在 `human_confirm` 后结束，产品确认通过 `/product-confirmations/stream` 建立第二段流并恢复 Checkpoint。产品线索解析、上下文对齐和意图识别通过 `ChatModel.stream(Prompt)` 输出结构化 JSON 增量，Planner、四个领域子智能体和多任务 Summary 通过 `ReactAgent.stream()` 输出模型增量。事件写入 `ai_workflow_sse_event` 并默认保留 10 分钟，到期后由30秒周期清理任务从数据库物理删除；最终答案以审核后的 `complete.finalAnswer` 为准。

需要产品人工确认时，初始 SSE 在 `human_confirm` 后结束。前端提交选择时调用 `POST /api/v1/workflows/main/runs/{workflowInstanceId}/product-confirmations/stream`，并建议把最后处理的事件编号放入 `Last-Event-ID` 请求头；服务会先补发遗漏事件并建立订阅，再从 OceanBase Checkpoint 恢复，后续模型和 Graph 事件继续实时返回。

本地流式联调页面位于 `/workflow-test/index.html`，可直接发起 Main Workflow、查看各阶段模型增量、选择召回候选并继续人工确认后的 SSE。

启用本地数据库：

```text
SPRING_PROFILES_ACTIVE=local-db
DB_URL=jdbc:mysql://127.0.0.1:2881/insurance_agent?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=你的本地数据库密码
```

`local-db` profile 启动后，Flyway 会自动执行：

```text
src/main/resources/db/migration/V1__create_memory_workflow_tables.sql
src/main/resources/db/migration/V2__create_long_term_memory_table.sql
src/main/resources/db/migration/V3__insert_main_workflow_definition.sql
src/main/resources/db/migration/V4__create_graph_checkpoint_tables.sql
src/main/resources/db/migration/V5__add_product_recall_nodes.sql
src/main/resources/db/migration/V6__move_product_recall_decision_to_context_alignment.sql
src/main/resources/db/migration/V7__create_conversation_confirmed_product_and_human_confirm_workflow.sql
src/main/resources/db/migration/V8__add_knowledge_qa_agent_to_main_workflow.sql
src/main/resources/db/migration/V9__add_dynamic_dag_executor.sql
src/main/resources/db/migration/V10__add_output_review_node.sql
src/main/resources/db/migration/V11__add_workflow_summary_agent.sql
src/main/resources/db/migration/V12__add_workflow_sse_events.sql
src/main/resources/db/migration/V13__add_reviewed_agent_stream.sql
src/main/resources/db/migration/V14__support_workflow_task_graph_threads.sql
src/main/resources/db/migration/V15__enable_live_agent_token_stream.sql
```

你只需要手动创建 `insurance_agent` 数据库，业务表由 Flyway 自动创建。
