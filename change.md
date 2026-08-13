# 工程变更记录

## 2026-08-13：SSE Token 低延迟合并落库

- 保留 Spring AI / Spring AI Alibaba 的真实增量模型流；每个 `streamId` 的首块仍在模型回调线程同步持久化并发送，前端首字响应不等待批处理窗口。
- `WorkflowAgentTokenStreamSink` 对后续小块按“默认最多80毫秒或累计128字符”双阈值合并，达到任一条件立即写入 `ai_workflow_sse_event` 并推送；流结束强制刷新尾部正文。
- `agent_stream.chunkIndex` 继续表示本批次最后一个原始块序号，新增 `firstChunkIndex`、`sourceChunkCount` 供观测；`streamId + chunkIndex` 去重、Last-Event-ID、OceanBase事实表和多实例Poller协议不变，现有测试页面无需修改。
- 新增独立 `workflow-token-flush-*` 调度器，Token 定时刷新不占用Lease心跳和通用`@Scheduled`任务线程；模型异常时刷新已生成正文并释放批次，但不发送正常结束标记。
- 新增首块即时发布、时间阈值、字符阈值、尾部刷新、并行流隔离、异常清理和1,000个小块压缩测试；量化用例将1,000个单字符块压缩为10个发布事件且正文长度保持1,000。
- 真实压测发现 Spring 会把唯一的 `ScheduledExecutorService` 自动用作全局 `@Scheduled` 调度器；新增显式 `taskScheduler`，将 SSE 数据库轮询、清理和租约任务固定到 `workflow-maintenance-*`，确保 `workflow-token-flush-*` 只负责低延迟模型流刷新。
- 使用真实 DeepSeek `deepseek-chat` 与本机 OceanBase 完成端到端测量：单路首 SSE 事件80毫秒、首模型正文1.538秒、总耗时13.045秒，747个原始模型块合并为62条 `agent_stream` 事件，Token事件写入减少约91.7%。
- 初次严格5路并发压测发现 SSE 执行器虽配置 `maxPoolSize=8`，但64容量队列使其长期只使用2个核心线程，后三路首事件排队约10秒/10秒/20秒。将 SSE 执行器改为零容量直接交付：最多8路立即启动，容量耗尽时快速拒绝，避免连接建立后静默排队；该问题与Token合并算法无关。
- 修复后使用同一进程严格同时发起5路请求：首 SSE 事件全部为108毫秒，首模型正文为1.689～1.902秒，5路均以 `SUCCESS/complete` 收口；2,092个原始模型块合并为202条流事件，Token事件写入减少约90.3%，OceanBase全事件写入峰值43条/秒、`agent_stream`峰值33条/秒。
- `./gradlew clean test` 全量163项测试通过，0失败、0错误、0跳过；覆盖Token合并器、SSE立即扩容与满载拒绝、调度线程隔离、配置绑定、ReactAgent/ChatModel流式执行器、SSE事件服务及完整Spring上下文。
- 项目仍以 Java 21 为编译目标；本机当前仅安装 Java 25/26，本次真实压测 JVM 为 Java 26.0.1。正式环境性能基线需在 Java 21 Runtime 再复测一次。

## 2026-08-12：DeepSeek 与 OceanBase 真实链路验收

- 使用进程级环境变量接入 DeepSeek `deepseek-chat`，密钥未写入代码、配置文件、日志或本文档。
- 产品分析接口真实调用成功：模型先调用 `read_skill` 和 `product_analysis`，再生成结构化分析结果；`modelInvoked=true`、输出格式校验通过。
- 知识问答接口真实调用成功：模型先调用 `read_skill` 和 `insurance_knowledge_search`，再生成知识回答；两次 Agent 调用均未触发8次模型调用上限。
- `local-db` Profile 连接 OceanBase 成功，Flyway 校验19个迁移，Schema V19 无待执行脚本。
- 同步 Main Workflow 真实执行知识、保单、资产三个无依赖子任务，动态 DAG 并行调度成功，Summary 真实调用模型，审核结果为 `PASS`，工作流最终为 `SUCCESS`。
- SSE 人工确认链路真实执行成功：首段事件从1递增到98并以 `human_confirm` 结束；选择 `PA-001` 后从99连续恢复到1137，共收到1022个 `agent_stream` Token 事件，最后依次收到 `summary`、`review`、`complete`。
- OceanBase 一致性核验通过：两个 Graph Thread 均为 `COMPLETED`，Checkpoint 分别保留8和11个版本；确认产品仅写入当前 `conversationId`；短期和长期记忆均保存一问一答，长期记忆使用 `wfa-{workflowInstanceId}` 稳定幂等键。
- 工作流终态后 `execution_owner` 与 `lease_until` 均已释放；SSE 事件按10分钟配置生成 `expire_at`，继续由现有清理任务物理删除。

## 2026-08-12：Spring AI Alibaba 1.1.2.0 原生能力优化

### 已采用

- Main Graph 注册原生 `GraphLifecycleListener`，迁移 Graph/Node 起止日志、耗时和 Stage SSE。
- 原 Recorder 收敛并改名为 `WorkflowNodeExecutionGuard`，只保留步骤状态 CAS、结果审计与 Lease/Fence 强制门禁。
- 四个领域 ReactAgent 接入原生 `ModelCallLimitHook`，默认单次运行最多8次模型调用，超限按异常进入既有审计和失败链路。

### 经源码核对后保留现状

- `streamMessages()` 在1.1.2.0只透出模型增量和Tool完成消息，过滤最终完成消息与Graph State；继续使用单次 `stream()` 同时获得Token和权威最终回答，避免二次执行Tool。
- `Store` 是覆盖式 namespace/key KV，与 `ai_long_term_memory` 追加审计语义不一致，不增加形式化适配层。
- 官方 `MysqlSaver` 缺少版本CAS、State Schema、Lease/Fence、Retention和工作流状态机联动，继续保留 `OceanBaseCheckpointSaver`。
- ToolCallLimit、ToolRetry、ToolContextHelper、ContextEditing与并行Tool执行当前均无安全替换收益，未启用。

### 文档与测试

- 新增 `docs/spring-ai-alibaba/07-native-capability-adoption-report.md`，记录本地1.1.2.0源码依据和最终职责边界。
- 新增 Agent调用上限隔离测试、Graph生命周期事件测试和流式最终State保护测试。
- `./gradlew test` 全量153项测试通过，0失败、0错误、0跳过。
- 当前终端未配置模型密钥，因此未执行真实DeepSeek网络调用；本次没有修改模型Prompt、Tool业务结果或最终回答格式。

## 2026-08-12：默认 Profile HTTP 启动验收

- 确认 Spring AI OpenAI 自动配置在启动阶段强制要求非空 `AI_API_KEY`；修正 YAML 和项目理解文档中“空Key可装配”的旧说明。
- 使用仅存在于进程环境的无效占位Key启动默认profile，不发起任何模型请求，不把占位值写入代码或配置文件。
- 应用在8080启动成功，四个领域SkillRegistry分别加载2/1/1/1个Skill。
- `/actuator/health` 返回200和UP，`/api/v1/ai/model/status` 返回200，`/v3/api-docs` 返回200，Swagger入口返回302到UI页面，`/workflow-test/index.html` 返回200。
- 验收完成后已停止测试进程。当前终端没有真实模型密钥且OceanBase 2881未监听，因此未执行local-db、真实Tool Calling和Main Workflow SSE网络验收。

## 2026-08-12：修复 HUMAN_CONFIRM 落库后提前关闭 SSE

- 确认原链路存在时序窗口：`WorkflowPauseService` 在事务内写入 `human_confirm` 事实事件后，`waitingConfirmResponse()` 直接调用 `completeSubscribers()`，可能早于500ms数据库 Poller 的实际发送。
- `WorkflowEventPublisher` 增加 `flushPersistedEvents()` 端口；暂停事务提交后立即从 OceanBase 读取尚未投递事件。
- 删除人工确认路径上的强制关闭调用；`sendOrRemove()` 在 `emitter.send(human_confirm)` 成功后按既有终止事件规则自动完成并移除连接。
- 若即时 flush 查询或发送失败，不提前关闭连接，由现有数据库 Poller 和 Last-Event-ID 重放继续补偿。
- 未改变 Main Graph、事件表、SSE协议、多实例轮询或断线重连机制。

## 2026-08-11：Execution Lease Fencing Token 加固

### 执行权代次

- Flyway V19 为 `ai_workflow_instance` 增加 `execution_fence_token`。新工作流从1开始，产品确认抢占和故障恢复接管时原子递增；heartbeat 只续租，不改变 fencing token。
- `execution_owner` 标识 JVM 实例，`execution_fence_token` 标识本次执行权代次，`state_version` 继续记录实例行状态变化，三者职责分离。
- token 进入 Main Graph State、`RunnableConfig` metadata、动态 DAG 任务上下文和模型 Token 流上下文；旧执行分支不会通过重新查询拿到新 token。

### 写入门禁

- Workflow 终态、失败、人工暂停和步骤审计写入统一校验 owner、fencing token、未过期 lease 与允许状态。
- OceanBase Checkpoint 在原有 `ai_graph_thread.version` 乐观锁之外，联表校验 Workflow owner、fencing token 和 lease；旧执行者不能推进 thread version 或写入 Checkpoint。
- 执行期 SSE sequence 分配校验 owner、token 和 lease；终态及 WAITING_CONFIRM 事件校验对应状态和 token。
- 新增 `WorkflowPauseService`，将步骤暂停、实例 WAITING_CONFIRM、conversation lock 续期和 `human_confirm` 事实事件放在同一事务中。
- 最终收口继续保持实例终态、Memory、Checkpoint 状态和终态 SSE Outbox 的单事务提交，并增加 fencing token 校验。

### 验证

- 新增 Workflow、Checkpoint、SSE Mapper 门禁测试，以及旧 token 无法写 Checkpoint/SSE 的服务测试。
- `./gradlew test` 全量测试通过。
- 本次未执行 Git 提交或推送。

## 2026-08-11：以 SSE 入口重新校准 Main Workflow 链路

### 链路校准

- 将 `POST /api/v1/workflows/main/runs/stream` 明确为主工作流实时入口，统一关键代码注释为17步：SSE受理与预订阅、启动事务、Main Graph、产品实体解析、可选召回/人工确认、恢复节点校验、上下文对齐、意图、Planner、动态DAG、Summary、输出审核和原子收口。
- 同步接口 `/runs` 保留为兼容入口，但不再占用“主工作流链路1”的编号，也不启用模型 Token SSE。
- 新增入口顺序测试，固定“预分配 workflowInstanceId -> 注册 SSE -> 提交后台任务 -> tokenStreamingEnabled=true”的时序。

### 旧内容修复

- 修复人工确认恢复和上下文对齐重复标为第7步的问题，并补齐 SSE 入口、interruptBefore、确认续流和最终收口序号。
- 修正文档中“上下文对齐先做召回判断”的旧图；当前唯一召回判断节点是 `resolve-product-reference`，上下文对齐只在产品实体确定后执行。
- 删除当前 Main Graph 中并不存在的固定 `route_agents`、`join_results`、`finish` 节点描述；四个领域 Agent 实际由 `dag-executor` 通过任务子图动态调度。
- 修正顺序为 `summary -> output-review`，并更新实际 SSE 事件类型、Human Confirm 请求字段、State Keys、OceanBase Saver/动态DAG现状和README缺失的 `requestId`。

### 范围

- 未改变 Main Graph 拓扑、Checkpoint、SSE事件表、Memory、领域 Agent 或动态DAG业务逻辑。
- 同步更新 `README.md`、`AGENTS.md`、项目理解文档及 Spring AI Alibaba项目落地文档。
- SSE入口、Human Confirm拓扑、Summary/Review定向测试通过；`./gradlew test` 全量139项测试通过，0失败、0跳过。
- 未执行Git提交或推送。

## 2026-08-11：会话锁回收与执行租约 Heartbeat

### 过期会话锁回收

- `WorkflowStartService` 在抢占 conversation 前先执行数据库条件删除，只清理 `lease_until <= now` 且已失效的旧锁，随后仍由 `conversation_id` 主键保证多实例只会有一个启动事务成功。
- `WorkflowLeaseRecoveryJob` 每30秒批量物理删除过期失效锁，避免没有新请求触发时残留数据长期堆积。
- 清理 SQL 不会删除未过期锁，也不会删除仍有有效 execution lease 的锁；执行租约已失效但仍有未过期 Graph Thread 的工作流继续保留锁，供现有 Checkpoint 恢复链路接管。
- 产品确认 claim 必须持有未过期 conversation lock；主动恢复 claim 必须仍存在该 workflow 对应的 conversation lock，避免已失去会话所有权的旧工作流重新执行。

### 执行租约续租

- `WorkflowLifecycleProperties` 新增可配置 `heartbeatInterval`，默认1分钟，并校验它短于15分钟 execution lease 和2分钟 claim lease。
- heartbeat 使用一条 OceanBase `UPDATE JOIN` 同时刷新 `ai_workflow_instance.lease_until` 和 `ai_conversation_workflow_lock.lease_until`。
- SQL 同时校验当前 `execution_owner`、RUNNING/CONFIRMING/RESUMING 状态以及旧租约仍未过期；终态、失去 owner、已过期或已被其他实例接管的记录更新行数为0。
- JVM 宕机后 heartbeat 自然停止；租约到期后原有恢复机制才可由其他实例 claim，旧 owner 不能再续租新 owner 的记录。

### 配置与验证

```yaml
insurance.ai.workflow.lifecycle.execution-lease: 15m
insurance.ai.workflow.lifecycle.claim-lease: 2m
insurance.ai.workflow.lifecycle.waiting-confirm-lease: 24h
insurance.ai.workflow.lifecycle.heartbeat-interval: 1m
```

- 定向测试覆盖过期锁启动前回收、未过期锁冲突、owner 条件续租、租约到期后恢复条件、旧 owner 隔离和产品确认锁过期保护。
- `./gradlew test` 全量138项测试通过，0失败、0跳过。
- 使用本地 OceanBase `EXPLAIN` 验证联合续租和过期锁删除 SQL 均兼容 MySQL 模式；校验过程未写入数据。
- `local-db` profile 在18083端口启动成功，Flyway 18个迁移校验通过，新增配置绑定和定时 Bean 装配正常；验证后已停止进程。
- 未修改 Main Graph、Checkpoint、SSE、Memory、Human Confirm 或动态 DAG 业务逻辑，未新增数据库迁移。
- 未执行 Git 提交或推送。

## 2026-08-12：补全应用配置说明

### 变更目标

- 为 `application.yml` 和 `application-local-db.yml` 的每个配置组及关键属性补充中文说明。
- 明确环境变量覆盖、默认 profile 与 `local-db` profile 的装配差异。
- 说明 Checkpoint、SSE、定时清理和执行租约配置值对应的实际生命周期语义。

### 变更内容

- `application.yml` 增加端口、优雅停机、模型连接、禁用模型类型、Actuator、Swagger和日志注释。
- `application-local-db.yml` 增加 OceanBase/MySQL连接、Flyway、MyBatis、Checkpoint、SSE、维护任务和租约注释。
- `docs/project-understanding-guide.md` 新增配置文件速查表，并强调 SSE 与 Checkpoint 保留期相互独立。
- 所有配置值保持不变，没有写入 API Key 或数据库密码。

## 2026-08-10：新增项目理解与开发地图

### 文档目标

- 为当前单模块保险智能体建立从目录、文件、函数/Bean 到数据库关系的统一导航。
- 帮助开发者按层次理解 Agent、Skill、Tool、Memory、Graph、Checkpoint、SSE 和领域代码。
- 为后续 Codex 开发提供稳定入口，减少重复扫描和错误归类。
- 后续架构、工作流、持久化、API、目录或业务能力优化必须在同一次变更中同步更新本文档。

### 新增内容

- 新增 `docs/project-understanding-guide.md`。
- 按根目录、AI 公共层、Memory、Retrieval、Workflow、Product、Knowledge、Policy、Asset、Common 逐层说明文件职责。
- 对行为类列出主要函数和 Bean；对 DTO、Enum、Mapper 分别说明数据合同、状态和数据库操作。
- 通过本地 OceanBase `show tables` 核对当前共 14 张表：13 张项目表和 1 张 Flyway 管理表。
- 补充会话记忆、Workflow、Graph Checkpoint、SSE、召回审计之间的软关联 ER 图。
- 补充主工作流、动态 DAG、人工确认、跨实例 SSE、Profile、API 和后续文件放置规则。
- `AGENTS.md` 增加本文入口，要求后续结构、Workflow 和持久化任务优先阅读。

### 说明

- 本次只新增和更新文档，没有修改业务代码、数据库结构或 API。
- 未执行 Git 提交或推送。

## 2026-08-11：Graph Checkpoint 生命周期调整

### 保留策略

- `GraphCheckpointProperties.activeRetention` 和 `application-local-db.yml` 默认改为7天，适用于 ACTIVE/RUNNING 对应的活动线程及 FAILED 排障现场。
- `completedRetention` 默认改为24小时，适用于 COMPLETED 和 Graph release 后的线程。
- SSE Event 继续保持10分钟保留、30秒清理，不与 Checkpoint 生命周期混用。

### 物理清理与测试

- 保留现有 `expires_at` 和 `OceanBaseCheckpointSaver.purgeExpired()` 架构，不新增表或清理执行器。
- 清理事务继续先删除过期 Thread 关联的 `ai_graph_checkpoint`，再删除 `expires_at <= now` 的 `ai_graph_thread`；任一步失败均整体回滚。
- 测试补充默认保留期、COMPLETED/FAILED 状态写入的 expiresAt、子记录优先删除顺序和 SQL 未过期保护条件。
- 项目理解文档和 Spring AI Alibaba 项目参考文档已同步更新。
- Checkpoint 定向测试与 `./gradlew test` 全量测试均通过。
- `local-db` profile 使用新配置启动成功，Flyway V18 校验通过，未新增数据库结构迁移。
- 未执行 Git 提交或推送。

## 2026-08-11：SSE 重放事件默认保留期调整为10分钟

### 修改范围

- `WorkflowSseProperties.eventRetention` 未配置时默认使用 `Duration.ofMinutes(10)`。
- `application-local-db.yml` 的 `insurance.ai.workflow.sse.event-retention` 从 `7d` 调整为 `10m`。
- 事件仍按 `occurredAt + eventRetention` 写入 `expire_at`；重放仍过滤 `expire_at > now`，清理仍删除 `expire_at <= now`。
- SSE 清理从 Checkpoint 小时级任务中拆为独立30秒调度；到期记录最多约30秒后从 `ai_workflow_sse_event` 物理删除，Checkpoint 继续按小时清理。
- 保留现有 Last-Event-ID、多实例数据库扫描、SSE 事件表和清理任务逻辑。
- V12 是已执行历史迁移，未修改其内容；新增 V18 只把数据库 `expire_at` 字段说明同步为当前默认10分钟，不改变字段类型和业务语义。

### 文档与测试

- 同步更新 README、AGENTS、项目理解文档、Memory/Workflow 设计文档和 Spring AI Alibaba 项目落地文档中的现行保留策略。
- 测试覆盖默认10分钟、事件落库过期时间、10分钟内重放、过期区间返回410以及现有清理 Mapper 删除路径。
- 未调整 Workflow、SSE 扫描、Checkpoint 或其他业务逻辑。
- SSE 定向测试与 `./gradlew test` 全量测试均通过。
- `local-db` profile 启动成功，Flyway 已将本地 OceanBase 从 V17 升级到 V18，应用随后正常停止。
- 实际运行观察到 SSE 清理任务在启动后第30秒和第60秒触发，日志为 `action=sse-event-purge status=success`；Checkpoint 清理仍按原小时级周期配置。
- 未执行 Git 提交或推送。

## 2026-08-10：工作流最终收口、执行租约与会话并发加固

### 问题确认

- 原 `complete()` 的 Memory、步骤、实例终态、Checkpoint 和 COMPLETE 事件分散提交，进程异常后存在最终问答重复写入及“Memory 成功但实例 FAILED”的窗口。
- 原 `fail()` 无终态条件，迟到异常可以覆盖 `SUCCESS`、`PARTIAL_SUCCESS` 或 `REVIEW_BLOCKED`。
- `CONFIRMING`、`RESUMING` 只有 CAS 抢占，没有 owner、lease 和宕机回收。
- 顶层请求没有 requestId，同一 conversationId 可同时启动多个工作流并覆盖 ChatMemory 完整窗口。

### 最终收口

- 新增 `WorkflowFinalizationService`，在一个 OceanBase 事务内完成实例终态条件更新、最终 Memory、待执行步骤关闭、Checkpoint 收口、终态 SSE Outbox 落库和 conversation 锁释放。
- 最终调用编号固定为 `wfa-{workflowInstanceId}`，不再使用随机 invocationId。
- 正常终态和 FAILED 都使用条件更新；任何既有终态都不能被重复收口或迟到异常覆盖。
- COMPLETE/ERROR 先写 `ai_workflow_sse_event`，事务提交后即时尝试投递，失败时继续由 500ms Poller 补偿。

### 租约与恢复

- Flyway V17 为 `ai_workflow_instance` 增加 `execution_owner`、`lease_until`、`state_version`。
- CONFIRMING/RESUMING 抢占同时写入应用实例 owner 和短租约；进入实际 Graph 前回到带执行租约的 RUNNING。
- 终态更新和人工中断更新校验 `execution_owner`，旧 JVM 在租约换手后不能再以迟到 complete/fail 覆盖新执行者；主动恢复也不能抢占尚未过期的 RUNNING 租约。
- 新增 `WorkflowLeaseRecoveryJob`，默认每 30 秒将过期 CONFIRMING 释放为 WAITING_CONFIRM、过期 RESUMING 释放为 RUNNING，不在定时线程中擅自重跑模型。

### 请求幂等与会话互斥

- `MainWorkflowRequest` 新增必填 `requestId`，数据库增加 `(conversation_id, request_id)` 唯一索引。
- 新增 `ai_conversation_workflow_lock` 和 `WorkflowStartService`，在同一事务内占用 conversation、创建实例及步骤；同会话并发请求或重复 requestId 返回 HTTP 409。
- 会话锁只在最终收口事务中释放；等待人工确认时保留独占，避免下一轮消息覆盖尚未完成的上下文。
- 流式测试页面会为每次新运行自动生成 requestId。

### 配置与验证

```yaml
insurance.ai.workflow.lifecycle.execution-lease: 15m
insurance.ai.workflow.lifecycle.claim-lease: 2m
insurance.ai.workflow.lifecycle.waiting-confirm-lease: 24h
insurance.ai.workflow.maintenance.recovery-interval: 30s
```

- `./gradlew test` 全量 126 项测试通过。
- 使用 `local-db` profile 在 18082 端口启动成功，Flyway 将本地 OceanBase 从 V16 升级到 V17。
- 只读核对确认当前为 14 张项目表 + 1 张 Flyway 表，新增生命周期字段和会话锁表均已生效。
- 未执行 Git 提交或推送。

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
