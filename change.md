# 工程变更记录

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
