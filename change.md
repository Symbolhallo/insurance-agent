# 工程变更记录

## 2026-08-10：新增工作流实时流式测试页面

### 页面入口

- Spring Boot 启动后访问 `/workflow-test/index.html`；使用明确静态文件路径，避免依赖子目录欢迎页映射。
- 页面使用同源接口，不新增 CORS、Node.js 构建流程或前端运行时依赖。

### 主要能力

- 输入问题和 `conversationId`，通过 `fetch + ReadableStream` 调用 `POST /api/v1/workflows/main/runs/stream`。
- 解析带 `id`、`event`、多行 `data` 的标准 SSE 帧，按 `streamId + chunkIndex` 追加并去重模型内容。
- 分开展示 Graph 阶段事件、各模型/子智能体增量流和审核后的最终答案。
- 收到 `human_confirm` 后展示脱敏候选产品，提交选择时携带 `Last-Event-ID` 调用确认流接口，并继续消费恢复后的 SSE。
- 支持主动清空和中止当前浏览器请求；全部服务端文本通过 `textContent` 渲染，不执行模型返回的 HTML。
- 页面包含桌面、平板和移动端响应式布局。

## 2026-08-10：修复 DeepSeek 结构化流缺失 JSON 起始边界

### 问题现象

- 本地 SSE 运行到 `context-alignment` 时，`BeanOutputConverter` 收到以 JSON 属性名开头、以 `}` 结尾的残缺文本。
- Graph、OceanBase Checkpoint 和 SSE 广播均正常，失败点是结构化模型流聚合后的 JSON 缺少首个 `{`。

### 修复内容

- `ChatModelStreamingExecutor` 改用 Spring AI 1.1.2 官方 `MessageAggregator` 聚合最终 `ChatResponse`，增量块仍同步发布到 SSE。
- 聚合后使用 Jackson 严格验证已知边界故障；仅当文本以 JSON 属性名开始、以 `}` 结束，并且补一个 `{` 后能解析成 JSON Object 时才修复。
- 不提取任意文本中的 JSON，不修复其他语法错误，无法严格解析的输出继续由 `BeanOutputConverter` 拒绝。
- 新增 DeepSeek/OpenAI-compatible 首块缺失对象起始符的回归测试。
- `./gradlew test` 全量 117 项测试通过，`git diff --check` 通过。
- 未执行 Git 提交或推送。

## 2026-08-10：工程目录职责整理

### 检查结论

- `product`、`knowledge`、`policy`、`asset` 四个业务域的 Agent、Tool、Service 和 Model 分类合理。
- `ai/memory`、`ai/retrieval`、`ai/workflow/checkpoint` 已形成清晰的基础设施边界，无需拆分。
- `resources/skills/{agent-domain}` 已按子智能体隔离，Flyway、静态测试页面和环境配置位置合理。
- 工作流 DTO 当前集中在 `ai/workflow/model`，数量虽多但都属于主图 State、API 或持久化合同；本次不做低收益的细粒度拆包。

### 目录调整

- 删除 Workflow 层的混合配置 `ai/workflow/config/CustomerQueryAgentConfig`。
- 新增 `policy/config/PolicyQueryAgentConfig`，归属保单 ReactAgent、ToolCallback 和业务门面 Bean。
- 新增 `asset/config/AssetQueryAgentConfig`，归属资产 ReactAgent、ToolCallback 和业务门面 Bean。
- 将 `WorkflowPersistenceCleanupJob` 从 `ai/workflow/service` 移到 `ai/workflow/job`。
- 测试目录同步生产代码包结构移动，并更新 Bean Qualifier 常量引用。

### 兼容性

- 保留原有 ReactAgent、ToolCallback 和业务 Agent Bean 名称。
- 不改变 SkillRegistry、Graph Workflow、SSE、Checkpoint、数据库表或 REST API。
- 本次仅调整源码归属和包结构，没有改变业务执行逻辑。

### 验证结果

- 旧包名引用扫描通过，未产生空目录。
- `./gradlew test` 全量测试通过，四个领域 Agent Bean、Qualifier 和清理任务装配正常。
- `git diff --check` 通过。
- 未执行 Git 提交或推送。

## 2026-08-10：前置模型全链路流式输出与人工确认后流式恢复

### 变更目标

- 产品线索解析、上下文对齐、意图识别和 Planner 全部输出真实模型增量 Token。
- 产品候选人工确认后重新建立 SSE，后续 Graph 从 OceanBase Checkpoint 恢复并继续流式输出。
- 保留现有同步运行、同步确认和 `Last-Event-ID` 历史重放接口。

### 主要实现

- 新增 `ChatModelStreamingExecutor`，消费 Spring AI `ChatModel.stream(Prompt)`，逐块发布文本并聚合完整 JSON，完整结果继续交给 `BeanOutputConverter` 和本地确定性校验。
- `resolve-product-reference`、`context-alignment`、`intent-recognition` 分别使用 `PRODUCT_REFERENCE_RESOLUTION`、`CONTEXT_ALIGNMENT`、`INTENT_RECOGNITION` phase。
- Planner 复用 `ReactAgentStreamingExecutor`，使用 `PLANNER` phase 输出结构化任务计划增量。
- `WorkflowAgentTokenStreamSink` 将每个 phase 映射回实际 Graph 节点编码；每次模型调用仍使用独立 `streamId`。
- 新增 `POST /api/v1/workflows/main/runs/{workflowInstanceId}/product-confirmations/stream`。
- 确认流接口先校验实例为 `WAITING_CONFIRM`，在同一实例锁内重放 `Last-Event-ID` 之后的事件并注册订阅，然后才在有界线程池中恢复 Graph。
- `human_confirm` 事件增加脱敏候选明细，前端无需读取完整 Checkpoint 即可展示并提交产品编码。
- 确认恢复时把 `tokenStreamingEnabled=true` 写回 Graph State，确保后续前置节点、子智能体和 Summary 均沿用真实模型流。
- 原同步确认接口保持不变，并显式使用非流式模型调用。

### 前端分段规则

- 第一段调用 `/runs/stream`；不需要人工确认时，该连接持续到 `complete` 或 `error`。
- 需要人工确认时，第一段以 `human_confirm` 结束，前端保存该事件的 `eventId` 和候选产品。
- 用户选择产品后，调用 `/product-confirmations/stream`，请求头 `Last-Event-ID` 使用前一段最后成功处理的事件 ID。
- 第二段先补发遗漏事件，再实时返回恢复后的 `agent_stream`、`stage`、`summary`、`review` 和 `complete`。
- 前置节点的 `agent_stream.content` 是结构化 JSON 增量，仅用于过程展示；业务状态仍以节点完成后的 Graph State 和最终 `complete.finalAnswer` 为准。

### 验证结果

- 已依据项目内 Spring AI Alibaba 1.1.2.0 文档和 Spring AI 1.1.2 本地源码核对 `ReactAgent.stream(...)`、`StreamingOutput.message()` 与 `ChatModel.stream(Prompt)`。
- 定向测试覆盖 ChatModel 增量聚合、前置 phase 路由、WAITING_CONFIRM 订阅、错误状态拒绝，以及订阅先于后台恢复执行。
- `./gradlew test` 全量测试通过，`git diff --check` 通过。
- 未执行 Git 提交或推送。

## 2026-08-10：大模型实时增量输出与最终 Summary 审核

### 变更目标

- 子智能体和 Summary 直接输出 Spring AI Alibaba `AGENT_MODEL_STREAMING` 增量文本。
- 中间 Token 不经过输出审核。
- 仅在完整 Summary 生成后执行 `output-review`。
- 最终以 `complete.finalAnswer` 作为审核后的权威答案。

### 主要实现

- 新增 `AgentTokenStreamContext` 和 `AgentTokenStreamSink`，隔离 Agent 核心执行与 SSE 传输。
- 新增 `WorkflowAgentTokenStreamSink`，把模型增量内容转换为可持久化和重放的 `agent_stream` 事件。
- `ReactAgentStreamingExecutor` 严格过滤 `OutputType.AGENT_MODEL_STREAMING`，从 `StreamingOutput.message()` 读取增量内容。
- 每次模型调用生成独立 `streamId`；并行子智能体通过 `streamId + taskId + agentName` 独立拼接。
- 流结束时发送 `last=true` 的空正文事件，避免重复完整答案。
- ProductAnalysisAgent、KnowledgeQaAgent 和 WorkflowSummaryAgent 均接入实时 Token 发布。
- 删除审核后的 `ReviewedAnswerStreamPublisher` 分片逻辑，避免 Summary Token 与审核后伪流式正文重复。
- Main Graph 顺序保持 `dag-executor -> summary -> output-review -> END`。

### SSE 协议变化

`agent_stream.data` 主要字段：

```json
{
  "streamId": "stream-...",
  "taskId": "task-1",
  "agentName": "product-analysis-agent",
  "phase": "SUB_AGENT",
  "content": "增量文本",
  "chunkIndex": 1,
  "last": false,
  "deliveryMode": "LIVE_MODEL_STREAM"
}
```

- Summary 阶段的 `phase` 为 `SUMMARY`，不包含 `taskId`。
- 流式内容是审核前临时内容。
- `review` 事件表示最终 Summary 正在或已经完成审核。
- `complete.finalAnswer` 是审核通过、改写或阻断处理后的最终答案。

### 数据库与兼容性

- 新增 Flyway `V15__enable_live_agent_token_stream.sql`，只更新工作流定义说明，不修改数据库表结构。
- 不修改已经执行的 V13，避免 Flyway checksum 不一致。
- `agent_stream` 继续写入 `ai_workflow_sse_event`，支持 `Last-Event-ID` 重放。
- 现有同步接口行为不变；只有 `/runs/stream` 启用模型增量发布。

### 前端处理规则

- 按 `streamId` 分别维护文本缓冲区，不能按 SSE 全局到达顺序把并行 Agent 内容拼在一起。
- `phase=SUB_AGENT` 用于展示子任务过程，`phase=SUMMARY` 用于展示最终汇总生成过程。
- 收到 `last=true` 后结束对应流的加载状态；该事件的 `content` 为空，不追加正文。
- Summary Token 在审核前已经可见。若审核返回 REWRITE 或 BLOCK，前端必须以随后 `complete.finalAnswer` 替换临时 Summary 内容。
- 断线后使用 `Last-Event-ID` 重连，重放事件仍按相同 `streamId` 拼接。

### 验证结果

- Spring AI Alibaba 1.1.2.0 `StreamingOutput.message()` 和 `OutputType.AGENT_MODEL_STREAMING` 已通过本地依赖源码确认。
- 定向测试覆盖模型增量过滤、Tool/Finished 事件排除、流结束标记、Summary 上下文和审核节点边界。
- `./gradlew test` 全量通过。
- `local-db` 启动成功，Flyway 已在 OceanBase 将 schema 从 V14 更新到 V15。
- 没有执行 Git 提交或推送。

## 2026-08-10：补齐四个领域子智能体的真实模型业务闭环

### 变更目标

- 产品分析、知识问答、保单查询和资产查询统一真实调用全局 `ChatModel`。
- 业务事实来自受控 Mock Service 和领域 Tool，不允许模型自行生成客户数据。
- 保持动态 DAG、逐 Token SSE、失败重试、调用审计、Summary 和最终输出审核链路不变。

### 保单查询 Agent

- 新增 `PolicyInfo`、`PolicyQueryResult`、`PolicyQueryService` 和 `MockPolicyQueryService`。
- 固定测试客户为 `MOCK-CUSTOMER-001`，提供三条脱敏 Mock 保单。
- 新增 `customer_policy_query` Tool，支持按 `IN_FORCE`、`PAID_UP` 筛选。
- Skill 升级为 `customer-policy-query`，强制模型先调用 Tool，并按查询结论、保单明细和数据说明输出。
- PolicyQueryAgent 从静态字符串 Mock 改为真实 ReactAgent 调用。

### 资产查询 Agent

- 新增 `AssetPosition`、`AssetQueryResult`、`AssetQueryService` 和 `MockAssetQueryService`。
- 固定测试客户为 `MOCK-CUSTOMER-001`，提供存款、理财和基金三类脱敏 Mock 持仓。
- 新增 `customer_asset_query` Tool，支持按 `DEPOSIT`、`WEALTH_MANAGEMENT`、`FUND` 筛选。
- Skill 升级为 `customer-asset-query`，强制金额、账号、日期和风险等级只能来自 Tool。
- AssetQueryAgent 从静态字符串 Mock 改为真实 ReactAgent 调用。

### 公共执行能力

- 新增 `AuditedReactAgentExecutor`，统一保单和资产 Agent 的同步/流式模型调用、成功流水和失败流水。
- WorkflowSubAgentRouter 将同一个 `AgentExecutionContext` 传入四个领域 Agent。
- SSE 模式下保单和资产模型内容同样以 `phase=SUB_AGENT` 实时发布。
- DAG 子任务只保存调用审计，最终会话仍由主工作流在 Summary 审核完成后统一写入。

### 安全边界

- 当前只允许查询固定 Mock 客户，其他 customerId 直接拒绝。
- 保单号、账号和姓名均为脱敏 Mock 字段，返回结果携带 Mock 来源。
- 当前 customerId 由模型按固定值填写只用于技术验证；生产接入必须改为 ToolContext 或服务端身份上下文注入，并在微应用侧再次鉴权。
- 不新增真实客户接口，不接核心、保单或资产微应用。

### 验证结果

- 保单和资产 ToolCallback 已使用模型同格式 JSON 参数执行，能够返回结构化脱敏 Mock 数据。
- `AuditedReactAgentExecutor` 测试确认同步模式调用 `ReactAgent.call`，SSE 模式调用 `ReactAgent.stream` 并携带 workflowInstanceId、taskId 和 agentName。
- Mock Service 测试覆盖状态/类型筛选、资产汇总金额和非 Mock 客户拒绝。
- `./gradlew test` 全量 111 项测试通过。
- `local-db` 在 8081 验证启动成功，OceanBase schema 为 V15，四个领域 SkillRegistry 均正常加载且无旧目录告警。
- 本阶段没有新增数据库迁移，没有执行 Git 提交或推送。

## 2026-08-10：人工确认并发保护、跨实例 SSE 与持久化清理

### 变更目标

- 防止多个产品确认请求从同一个 OceanBase Checkpoint 重复恢复工作流。
- 以 `ai_workflow_sse_event` 作为 SSE 唯一事件事实源，支持跨 JVM 实例实时跟随。
- 自动清理超过保留期的 Graph Checkpoint 和 SSE 重放事件。

### 人工确认并发保护

- `WorkflowExecutionMapper` 新增条件更新，将实例从 `WAITING_CONFIRM` 原子抢占为 `CONFIRMING`。
- 只有数据库影响行数为 `1` 的请求可以保存确认产品、更新 Checkpoint 和恢复 Graph。
- 抢占失败使用 `WORKFLOW-409` 返回 HTTP 409，不再进入模型、Tool 或记忆写入链路。
- SSE 确认入口在建立响应前同步抢占；成功后才订阅并提交后台恢复，避免失败请求进入共享订阅集合。
- 后台任务提交失败时通过条件更新把 `CONFIRMING` 退回 `WAITING_CONFIRM`，允许安全重试。
- Flyway V16 更新工作流实例状态字段注释，补充 `CONFIRMING` 和 `RESUMING`。

### OceanBase SSE 跨实例交付

- 每个本地 `SseClient` 维护最后成功发送的 `sequenceNo`。
- 本机发布完成后不直接发送内存事件，而是立即从 OceanBase 读取该客户端游标之后的事件并按序发送。
- 定时任务默认每 500ms 增量读取活跃连接对应的数据库事件，实例 B 可以收到实例 A 写入的后续事件。
- 本机即时读取与后台轮询通过客户端游标幂等去重，不依赖并行返回顺序。
- `human_confirm`、`complete` 和 `error` 在远端实例被读取后同样会结束当前 SSE 连接。
- `Last-Event-ID` 重放和实时追踪共用同一个序号游标，重放与注册之间的新事件由下一次增量读取补齐。

### Checkpoint 与 SSE 清理

- 新增 `WorkflowPersistenceCleanupJob`，`local-db` profile 下默认启动一分钟后执行，之后每小时执行一次。
- Checkpoint 清理复用 `OceanBaseCheckpointSaver.purgeExpired()`：完成实例默认保留 30 天，活动或失败实例默认保留 90 天。
- SSE 清理删除 `expire_at` 已到期的事件，默认保留 7 天。
- 两类清理独立捕获异常和记录日志，一类失败不会阻塞另一类。
- 长期对话记忆、ChatMemory 和业务审计数据不在本次清理范围内。

### 配置项

```yaml
insurance:
  ai:
    workflow:
      sse:
        database-poll-interval: 500ms
      maintenance:
        cleanup-initial-delay: 1m
        cleanup-interval: 1h
```

### 验证结果

- 定向测试覆盖确认抢占失败、SSE 抢占后订阅、跨实例事件游标推进、Checkpoint/SSE 清理及清理失败隔离。
- `./gradlew test` 全量测试通过。
- `local-db` profile 在 18080 端口启动成功，Flyway 成功校验 16 个迁移并将 OceanBase schema 从 V15 升级到 V16。
- 未执行 Git 提交或推送。
