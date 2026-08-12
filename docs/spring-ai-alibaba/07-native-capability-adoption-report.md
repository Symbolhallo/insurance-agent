# Spring AI Alibaba 1.1.2.0 原生能力适配报告

## 1. 结论摘要

本轮严格以本地 Gradle Cache 中 `spring-ai-alibaba-agent-framework:1.1.2.0` 和
`spring-ai-alibaba-graph-core:1.1.2.0` sources JAR 为准。最终安全采用两项原生能力：

1. Main Graph 使用 `GraphLifecycleListener` 承担通用生命周期日志、耗时和 Stage SSE。
2. 四个领域 ReactAgent 使用 `ModelCallLimitHook` 限制单次运行模型调用次数。

`streamMessages()`、Graph `Store`、`MysqlSaver`、`ToolCallLimitHook`、`ToolRetryInterceptor`、
`ToolContextHelper` 和 `ContextEditingInterceptor` 均完成源码核对，但当前不能在不降低既有语义的
前提下直接替换或启用。

## 2. ReactAgent 流式处理

### 当前版本源码事实

`Agent.streamMessages(...)` 内部仍调用 `stream(...)`，然后由私有 `extractMessages(...)` 过滤输出。
`1.1.2.0` 只保留：

- `OutputType.AGENT_MODEL_STREAMING`
- `OutputType.AGENT_TOOL_FINISHED`

它会过滤 `AGENT_MODEL_FINISHED` 和普通最终 `NodeOutput`，也不会向调用方暴露最终
`OverAllState`。因此 `streamMessages()` 可以观察模型增量和 Tool 完成消息，但不能单独提供与
`ReactAgent.call()` 等价的最终 `AssistantMessage`。

### 项目决策

保留 `ReactAgentStreamingExecutor` 对一次 `reactAgent.stream(...)` 的消费：

- `AGENT_MODEL_STREAMING` 继续发布 Token SSE；
- 最终 Graph State 继续提取最后一个 `AssistantMessage`；
- Tool Calling 和 Tool 返回后模型续写仍在同一次 ReAct 执行内完成；
- 保留空结果与 `Exception:` 包装文本检查。

不能采用“先 `streamMessages()`、再 `call()`”的组合，因为它会再次执行模型和 Tool，造成重复查询、
重复外部副作用和审计不一致。项目测试锁定了单次低层流这一约束。

## 3. GraphLifecycleListener

### 已采用能力

Main Graph 在 `CompileConfig.Builder.withLifecycleListener(...)` 注册
`MainWorkflowLifecycleListener`，由框架回调：

- `onStart`：Graph 开始日志；
- `before`：Node 开始日志、计时和 RUNNING Stage SSE；
- `after`：Node 成功日志、耗时和 SUCCESS Stage SSE；
- `onError`：脱敏失败日志、耗时和 FAILED Stage SSE；
- `onComplete`：Graph 完成日志。

### 必须保留的安全门禁

`GraphRunnerContext.doListeners(...)` 会捕获并记录 Listener 异常，不会让异常成为 Graph 的安全边界。
因此原节点包装职责被收敛为 `WorkflowNodeExecutionGuard`：

- `ai_workflow_step` 开始/结果状态 CAS；
- `execution_owner`、lease 和 fencing token 校验；
- Lease/Fence 丢失时抛错并拒绝旧 Graph 分支继续；
- 节点输出 JSON 与失败原因审计。

Listener 只观测，Guard 才执行生产安全控制。

## 4. 长期记忆 Store 评估

框架 `Store` 是 `namespace + key` 唯一定位的可变 KV：同键 `putItem` 表示覆盖更新，并公开
`deleteItem()` 和 `clear()`。当前 `ai_long_term_memory` 是按 invocation/message 追加的永久历史，
同时服务于会话生命周期、摘要和审计，不允许同键覆盖或全量清空。

本阶段不增加 `OceanBaseLongTermStoreAdapter`，原因是仅做接口包装无法消除语义差异，反而容易让
Agent 误用覆盖/删除操作。未来若新增“客户偏好、事实画像”等明确 KV 数据，可建立独立表实现 Store，
但不能复用或替换 `ai_long_term_memory`。

此外，`DatabaseStore` 在 `1.1.2.0` 的 `putItem` 使用 H2 风格
`MERGE INTO ... KEY(id)`，不应直接作为 OceanBase MySQL 模式生产实现。

## 5. Checkpoint Saver 对照

| 能力 | 官方 `MysqlSaver` | `OceanBaseCheckpointSaver` |
| --- | --- | --- |
| Graph State 持久化/恢复 | 支持 | 支持 |
| Checkpoint 历史 | 支持基础列表 | 支持并关联线程状态 |
| parentCheckpointId | 未持久化 | 支持 |
| checkpointVersion/stateSchemaVersion | 不支持 | 支持 |
| 多实例乐观锁 | 不支持 | `ai_graph_thread.version` CAS |
| owner/lease/fencing token 写门禁 | 不支持 | 支持 |
| ACTIVE/COMPLETED/FAILED 生命周期 | 仅 released 布尔值 | 支持 |
| expires_at 与定时清理 | 不支持 | 支持 |
| Workflow/Task 子图元数据 | 不支持 | 支持 |

官方实现还继承 `MemorySaver`，运行过程中维护 JVM 内 Checkpoint 列表，数据库主要承担首次加载和写入。
它无法覆盖当前多实例恢复和旧执行者拒绝写入要求，因此保留 OceanBase Saver，不做替换。

## 6. Agent 安全能力

### 已启用 ModelCallLimitHook

四个领域 Agent 共用不可变的 `ModelCallLimitHook` Bean，计数存放在每次
`RunnableConfig.context()`，不会在并行 Agent 之间共享。默认 `runLimit=8`，超限使用 `ERROR`：

- 正常“一次 Tool 调用 + Tool 后续模型回答”通常使用两次模型调用；
- 失控 ReAct 循环会在后续模型调用前被阻止；
- 异常进入现有 Agent 调用审计和 DAG 失败/重试机制；
- 不把框架英文限流提示当成最终金融回答。

配置项：`insurance.ai.agent.safety.model-call-limit`。

### 暂不启用的能力

| 能力 | 结论 |
| --- | --- |
| `ToolCallLimitHook` | 在模型已生成 Tool Calls 后计数，不能阻止当前批次超限 Tool 执行；暂不作为权限或额度控制。 |
| `ToolRetryInterceptor` | 使用同步 `Thread.sleep` 退避；当前 Tool 异常主要为业务参数错误，不应重试。未来微应用接入后仅对白名单瞬时网络异常启用。 |
| `ToolContextHelper` | 当前 Tool 未接收 ToolContext，无重复手工解析可替换；生产身份注入时应优先采用。 |
| `ContextEditingInterceptor` | 会清除旧 Tool 输入/结果；在金融事实留存、豁免 Tool 和 Token 阈值未确定前不启用。 |
| Parallel Tool Execution | 当前每个领域 Agent 只有一个业务 Tool，且动态 DAG 已负责跨 Agent 并行，无直接收益。 |

## 7. 最终职责边界

Spring AI Alibaba `1.1.2.0` 负责 ReactAgent/ReAct、Tool Calling、Skill Hook、Graph Runtime、
Human Interrupt、GraphLifecycleListener 和模型调用上限。

insurance-agent 生产增强层继续负责动态 DAG、OceanBase Checkpoint、Lease/Heartbeat/Fence、
Conversation Lock、步骤状态机、长期追加记忆、SSE Outbox/Poller/Replay、审计和最终输出审核。
