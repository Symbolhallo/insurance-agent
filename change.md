# 工程变更记录

## 2026-09-29：历史会话增加批量删除管理

- 历史侧栏增加“管理”模式，支持逐项选择、全选/取消全选、已选数量提示和批量删除确认；工作流运行或等待人工确认期间继续禁用会话管理。
- 批量操作复用既有单会话 `DELETE /api/v1/ai/memory/conversations/{conversationId}`，按顺序逐条调用，使每个会话继续独立执行租户权限、活跃 Workflow 和 conversation lease 校验，不新增绕过安全边界的批量 SQL。
- 删除结果支持部分成功：成功项立即从列表移除，失败项保持选中并显示汇总错误；若当前正在查看的会话删除成功，页面自动切换到新会话。
- 确认弹窗明确说明删除仍是软删除，长期记忆和审计数据继续保留；单条悬浮删除入口保持不变。
- React/Vite 生产构建通过；页面确认新“管理”入口已加载。由于本地会话列表接口在浏览器验证时返回 HTTP 500，本轮未对真实历史数据执行删除操作。

## 2026-09-29：修复流式阅读抖动并按实际路径展示工作流

- React 自动跟随改为同步引用控制：只有向上滚轮或向下拖动触摸等明确“阅读更早内容”的手势才暂停，并取消尚未执行的滚动帧；已经到底后继续向下滚动、程序滚动和 Markdown/历史消息造成的 DOM 重排不改变跟随状态，避免按钮与对话区域反复闪动。
- 历史助手消息、已结束的模型流和最终答案统一接入 `react-markdown + remark-gfm`，支持标题、列表、表格、引用、链接及代码块；关闭原始 HTML 解析，外部链接使用安全的新窗口属性，不引入 `dangerouslySetInnerHTML`。
- 运行详情不再静态展示全部主流程节点，只根据实际收到的 `stage`、模型流阶段、人工确认事件以及动态 DAG `taskId` 投影本次执行路径；不涉及产品召回的意图不会展示候选召回和人工确认节点。
- 为固定路径 `/workflow-test/assets/app.js` 增加仅限联调页的 `Cache-Control: no-store` 资源配置，避免 Spring Boot 页面继续执行浏览器缓存中的旧 bundle。
- 更新 React 页面回归测试，覆盖可取消自动滚动、Markdown 安全渲染、动态节点投影和静态资源禁用缓存；浏览器真实 DeepSeek 链路确认知识问答只展示实际节点和 `knowledge-qa-agent`，未展示产品召回/人工确认，并确认最终回答产生标题、列表等语义化 Markdown DOM。

## 2026-09-29：兼容 Planner 的 JSON Schema 外壳输出

- 修复 RuntimeContext 空值后，通过 IDEA 启动的真实 DeepSeek + OceanBase SSE 链路继续验证，发现 Planner 偶发把实际计划放入 `{"type":"object","properties":{...}}`，导致严格反序列化失败。
- 统一结构化输出边界仅识别并解包这一种明确的 object/properties 外壳；解包后仍执行原目标类型反序列化以及任务数量、Agent 白名单、依赖和无环校验，不放宽动态 DAG 安全规则。
- Planner 指令明确要求根对象直接输出 objective、tasks、rationale，禁止返回 type/properties Schema 元数据；增加直接 JSON 与 Schema 外壳两类回归测试。
- 使用 IDEA 运行配置中的 DeepSeek 与本地 OceanBase 完成真实 SSE 回归：知识问答请求依次通过产品实体解析、上下文对齐、意图识别、Planner、动态 DAG、KnowledgeQaAgent、Summary 和 Review，最终产生 59 个有序事件并以 `SUCCESS`/`complete` 收口。

## 2026-09-28：修复人工确认恢复后 Planner RuntimeContext 空值异常

- IDEA 真实链路在“产品召回 -> 人工确认 -> 上下文对齐 -> 意图识别 -> Planner”阶段复现 NPE；根因是 Planner、Summary 等非 DAG Token 流允许 `taskId` 为空，而 `ReactAgentStreamingExecutor` 将空值写入 AgentScope `RuntimeContext.Builder` 的 `ConcurrentHashMap`。
- RuntimeContext 扩展字段改为仅写入非空文本；workflowInstanceId、agentName 等已有链路元数据保持不变，可选 taskId 缺失时直接省略，不改变 AgentScope 事件流、SSE 或最终消息提取语义。
- 新增空 taskId 回归测试，确认 Planner 上下文可以执行、必要元数据仍存在且 RuntimeContext 不包含空键值；Planner、Summary 和流式执行器针对性测试通过。

## 2026-09-28：修复人工确认恢复后的会话查询映射错误

- 在 IDEA 使用 `local-debug` Profile 复现并定位人工确认恢复链路异常：`AgentMemoryQueryMapper.findConversation()` 仍按旧版 9 字段构造会话记录，而 V21 租户隔离已为 `AgentConversationRecord` 增加 `tenantId`，MyBatis 因找不到匹配的 Record 构造器抛出 `NoSuchMethodException`。
- `findConversation()` 查询和 `@ConstructorArgs` 同步补充 `tenant_id`，字段顺序与 `AgentConversationRecord` 的 10 个构造参数严格一致；不改变 Memory、Graph、Checkpoint、SSE 或人工确认流程。
- 新增 Mapper 注解回归测试，固定 SQL 投影与构造参数顺序，避免后续 Record 字段演进时出现运行期映射错误。
- IDEA Debug 启动验证通过：OceanBase 连接成功、21 个 Flyway 迁移校验通过、Tomcat 8080 正常启动；使用此前失败的真实 conversationId 查询聚合会话接口返回 HTTP 200，针对性测试通过。

## 2026-09-28：参考成熟 AI 产品重构会话工作台

- 参考 Vercel Chatbot 的会话优先、持久历史和稳定 Composer 模式，以及 LangSmith Studio 将运行过程独立观测的交互方式，重新设计 React 流式联调页；不修改后端 API、SSE 协议和工作流状态语义。
- 页面调整为“历史会话侧栏 + 中央对话 + 可开关运行详情抽屉”：输入框固定在独立底部区域，不再和长输出、节点列表竞争首屏空间；桌面抽屉可并列显示，窄屏使用遮罩抽屉。
- 产品候选确认改为对话内消息卡片，确认后沿原 Checkpoint 恢复接口继续接收流；前置模型和子智能体 Token 在助手消息中实时追加，Graph 节点、动态 DAG 任务和原始事件在运行详情中同步展示。
- 新增空会话建议问题、清晰的连接状态、移动端历史抽屉和运行抽屉；问题发送后立即清空 Composer。自动跟随继续在用户滚动时暂停，回到底部或点击按钮后恢复。
- 移除旧的多面板工作台覆盖样式，统一为单一 `styles.css`；Vite 构建通过。Playwright 模拟产品确认、确认后续流、双 Agent 任务和最终回答，并验证 1440×900、1366×650、1024×768、390×844、375×667、844×390 视口下输入框始终可见、无整页滚动或横向溢出。

## 2026-09-28：修复工作台输入框首屏不可见

- 移除桌面560px最小高度和手机整页纵向展开，将页面约束为100dvh，输入区置顶且不参与收缩，长正文仅在内容区滚动。
- 1250px及以下历史会话改为按钮展开；对话、处理流程、产品确认使用工作区切换，确认到达后自动展示候选，提交后返回输出。
- 输入区增加清晰问题标签和字数统计，保留请求编号为悬停信息，减少技术字段占用。
- Playwright模拟SSE验证1440×900、1366×650、1024×768、390×844、375×667、844×390六种视口：初始及长输出后输入框与发送按钮完整处于视口，页面不产生整体纵向滚动；确认、续流及手机流程切换通过。


## 2026-09-28：专业工作台与实时流程视图

- React 页面改为浅色工作台布局，保留历史会话导航，将输入区固定在桌面底部，流程与正文分别滚动。
- 新增 `WorkflowProgress.jsx`，按实际 stage 事件展示九个主节点，按 taskId 展示并行 Agent 状态；未知、未经过和失败不冒充成功。
- 新增“对话结果 / 实时过程”视图切换，前置模型增量在实时过程中完整可见；中间回答标记待最终审核，审核后最终回答保持可见。
- 产品确认面板仅在 WAITING_CONFIRM 时出现；保留确认续流、Last-Event-ID 和历史会话功能。阶段事件按 eventId 去重，自动跟随优先定位活动节点，手动滚动仍暂停跟随。
- 新增 `workbench.css` 响应式工作台样式；Vite `/api` 代理到本地8080，方便前后端分开调试。
- Playwright + 本机 Chrome 模拟 SSE 验证确认、恢复、并行任务、正文输出和1440/1024/390像素布局；无页面脚本错误、无横向溢出。此轮未进行真实模型调用。


## 2026-09-28：可信身份贯穿与租户级资源隔离

- 新增统一 `RequestIdentity`、Spring MVC 参数解析器和 `insurance.security.identity.*` 配置。本地继续使用稳定默认身份；生产可通过 `IDENTITY_HEADERS_REQUIRED=true` 强制可信网关注入租户和用户请求头。
- 新增 `ResourceAccessService` 与 OceanBase Mapper。首次使用 conversationId 时原子创建所有权占位，主键冲突绝不覆盖既有 owner；会话和工作流越权统一返回404，避免暴露其他租户资源。
- 同步/流式 Main Workflow、SSE 重连、产品确认、主动恢复、Memory 查询/摘要/删除、产品分析、知识问答和 AgentScope AG-UI 入口均接入统一身份边界。
- Swagger/OpenAPI 自动为使用身份边界的接口展示四个真实 Header；本地标为可选，严格模式将租户和用户标为必填，不再把 RequestIdentity 误展示为普通查询对象。
- `RequestIdentity` 贯穿 Main Graph State、Checkpoint、动态 DAG 子任务、领域 Agent、最终 Memory、长期记忆和调用审计；修复 Checkpoint 恢复时身份字段因 Jackson 只读配置丢失的问题。
- 新增 Flyway V21：为 `ai_conversation`、`ai_workflow_instance`、`ai_agent_invocation` 和 `ai_long_term_memory` 增加 tenant 字段及组合索引，不新增业务表。
- 会话列表和软删除 SQL 增加 tenantId+userId 条件；历史占位记录在产生有效长期消息前不展示。`ai_conversation` 后续 upsert 不再修改首次认领的租户、用户、客户或操作员。
- 新增身份严格模式、非法标识符、越权404、所有权不可覆盖、租户持久化 SQL、V21 迁移和 Checkpoint 身份往返测试。
- `./gradlew clean test` 全量通过，React/Vite 生产构建通过，`git diff --check` 无空白错误；仓库未检出硬编码 `sk-*` 密钥。

## 2026-09-28：React 测试台补齐 SSE 自动恢复与人工确认暂停态

- `POST /api/v1/workflows/main/runs/stream` 在建立流时通过 `X-Workflow-Instance-Id` 响应头立即返回预分配实例编号，前端不再依赖首个 SSE 事件才能获得恢复主键。
- React 测试台把 `conversationId`、`requestId`、`workflowInstanceId`、`Last-Event-ID`、运行状态和产品候选保存在 `sessionStorage`；连接意外结束后使用既有 `GET /runs/{workflowInstanceId}/events` 自动重放并继续实时跟随。
- 自动重连采用500毫秒起步、最高5秒的退避；短时网络失败不结束业务运行，HTTP 410 重放缺口则明确终止本地恢复，避免展示不完整事件链路。
- `human_confirm` 改为独立 `WAITING_CONFIRM` 页面状态。等待期间保留并恢复候选及勾选结果，锁定新建、切换、删除、清空和再次发起操作；用户提交确认后恢复为运行态并继续消费第二段 SSE。
- 工作流 `complete/error` 后清理浏览器恢复游标；刷新页面时，运行中的实例自动重连，等待确认的实例直接恢复确认面板，不重复启动 Graph。
- 保留 OceanBase SSE 事实表、多实例 Poller、Last-Event-ID、Graph Checkpoint 和 Human Confirm 后端语义；未新增数据库表或修改 Main Graph。
- React/Vite 生产构建成功；SSE Service、Controller 响应头和静态页面定向测试通过；`./gradlew clean test` 全量144项测试通过，0失败、0错误、0跳过。

## 2026-09-11：运行配置迁移为 properties 并保留 YAML

- 新增 `application.properties`、`application-local-db.properties` 和 `application-debug-timing.properties`，逐项迁移端口、模型、Swagger、OceanBase、Flyway、MyBatis、Checkpoint、SSE、维护任务和 Lease 配置，环境变量占位符及默认值保持不变。
- 原有三份 `.yml` 文件原样保留为迁移对照和低优先级兼容副本；同目录同名配置由 Spring Boot 优先采用 `.properties`。
- `local-db` properties 使用空 `spring.autoconfigure.exclude` 恢复 DataSource/Flyway 自动配置，继续保持默认 Profile 无数据库、`local-debug -> local-db + debug-timing` 的加载语义。
- 配置绑定测试切换到 properties，并新增 Spring ConfigData 优先级测试，验证基础配置和 local-db properties 均高于同名 YAML。
- `./gradlew test` 全量176项测试通过，0失败、0错误、0跳过。

## 2026-08-27：React 测试台增加历史会话管理

- 左侧新增历史会话栏，按 OceanBase `ai_conversation.updated_at` 倒序展示标题、长期消息数量和更新时间；选中后按需读取最多200条永久长期记忆，并按时间正序显示用户/助手消息。
- 新建对话在浏览器生成新的 conversationId，不提前创建空数据库记录；首次成功问答仍由既有 Workflow Finalization/Memory 事务写入，完成后自动刷新历史列表和持久化消息。
- 新增会话列表和软删除 API。删除通过数据库条件 UPDATE 将 `ai_conversation.status` 标记为 `DELETED`，聊天、长期记忆、调用流水、Checkpoint 和 Workflow 审计不做物理删除。
- 运行中、恢复中、等待确认或仍有有效 conversation lease 的会话不能删除并返回409；不存在或已经删除的会话保持幂等。
- 删除确认使用页面内对话框，明确提示审计保留语义；桌面使用固定左栏，移动端将会话栏收敛为顶部可滚动区域。
- 保留当前 SSE、Last-Event-ID、Human Confirm 和自动跟随行为；历史消息与本轮模型输出共享同一阅读区域。
- React 生产构建成功；浏览器完成桌面/390px移动端、历史切换、新建及删除确认测试；`./gradlew test` 全量174项通过，0失败、0错误、0跳过。

## 2026-08-22：流式测试页面迁移 React 并增加可控自动跟随

- 将 `/workflow-test/index.html` 从原生 DOM 脚本迁移为 React 18 + Vite 源码工程，继续由 Spring Boot 静态资源目录直接提供生产构建产物，不改变部署入口或后端 API。
- 完整保留 Main Workflow 启动、fetch ReadableStream SSE 解析、`streamId + chunkIndex` 去重、产品候选确认、`Last-Event-ID` 续流和最终答案展示。
- 执行阶段与模型输出默认自动跟随最新内容；内容溢出后，鼠标滚轮、指针或触摸干预会暂停自动滚动，用户滚回底部或点击图标按钮后恢复，避免持续 Token 输出打断历史内容阅读。
- 页面输入上限同步后端调整为2000字符，使用 Lucide 图标和桌面三栏/移动端单列响应式布局；生产运行不依赖 CDN。
- 增加 React 源码、固定依赖版本、静态产物、SSE 协议、自动跟随事件和危险 DOM API 的回归检查；浏览器实测默认跟随距离为0，手动干预后保持阅读位置，恢复后重新回到底部。
- `npm audit --audit-level=moderate` 为0项漏洞；`./gradlew test` 全量168项测试通过，0失败、0错误、0跳过。

## 2026-08-22：动态 DAG 下游输入预算与确定性失败重试优化

- 修复 Planner 原始任务问题不超过2000字符，但 `WorkflowSubAgentRouter` 追加多个上游完整答案后仍可能超过领域 Agent 输入上限的问题。
- 子智能体任务原始问题保持最高优先级；剩余2000字符预算由已确认产品和明确依赖结果使用，多个依赖按剩余任务数公平分配，连续空白先压缩，超长回答增加 `...[truncated]` 标记。
- 依赖注入继续只包含当前任务明确声明的 `dependsOn` 结果，不引入其他并行分支、聊天历史或完整 Graph State。
- `AgentInvokeNode` 将直接 `IllegalArgumentException` 识别为确定性请求错误并立即失败，不再重复调用；模型或外部依赖产生的其他异常继续按 Planner `maxRetries` 重试。
- 失败日志区分准备再次执行的 `retry/scheduled` 和不会再重试的 `attempt-failed/terminal`，任务结果中的 `attempts` 改为真实调用次数。
- 新增多上游超长答案预算测试与参数错误不重试测试；Main Graph 拓扑、DAG dependsOn 调度、失败传播、Checkpoint 和 SSE 协议均未改变。

## 2026-08-17：降低 IDEA 事务断点对 Lease Heartbeat 的干扰

- 确认本地异常来自断点事务长期持有 `ai_workflow_instance`/conversation lock 行，后台联表续租等待同一行后触发 `CannotAcquireLockException`；不是 Graph、Checkpoint 或模型执行失败。
- `WorkflowLeaseRecoveryJob` 只对明确的数据库锁竞争降级为 `WARN/deferred`，放弃当前 heartbeat 并由下一调度周期继续按 owner 条件续租；连接、SQL、Schema 等其他异常仍向外传播。
- `local-debug` 的 heartbeat 从5分钟推迟到3小时，仍严格短于4小时 execution/claim lease，减少 IDEA 长断点期间后台续租与业务事务争抢数据库行。
- 增加锁竞争降级测试并同步本地调试配置绑定、README 和项目理解文档；普通 `local-db` 与生产默认的15分钟租约、1分钟 heartbeat 均未改变。

## 2026-08-17：全项目高收益可读性重构

- 在 `simplify` 分支按“流式输出 → 主工作流与安全门禁 → 动态 DAG 与 Agent → 模型输出校验”的顺序整理高复杂度代码，并在每组修改后运行对应测试。
- `ReactAgentStreamingExecutor` 合并字符串/消息列表两套重复流处理，模型增量过滤改为逐步 Guard Clause，最终 AssistantMessage 改为从最新 Graph State 反向查找；仍只执行一次 ReactAgent 流，不改变 Tool Calling 或最终答案来源。
- `WorkflowAgentTokenStreamSink` 将字符阈值和最大延迟提取为具名判断；首块即时投递、80ms/128字符批次、chunkIndex、异常 abort 和尾部 flush 语义保持不变。
- `LocalDbWorkflowSseEventService` 将实例校验、重放缺口判断和历史事件发送拆成明确阶段，实时事件继续以 OceanBase 为事实源，并保持 sequence、Last-Event-ID、多实例 Poller 和终止事件关闭规则。
- `LocalDbMainWorkflowService` 明确 `graphConfig`、`initialState`、`graphOutput` 三个执行阶段；Lifecycle Listener、Node Guard 和 Lease/Fence 校验改用靠左主路径与逐项条件检查，数据库 CAS 和事务边界未调整。
- `WorkflowDagExecutor`、`WorkflowTaskGraphRunner`、`AgentInvokeNode` 明确就绪任务、最小任务上下文、Checkpoint 恢复、重试成功/失败结果的构造步骤；动态 dependsOn 调度、并行释放后继、失败传播和成功任务恢复去重不变。
- 意图识别、上下文对齐和输出审核的模型合同校验改为逐字段 Guard Clause，错误类型和业务拒绝边界保持一致。
- 有意保留 `GraphCheckpointConfig` 的版本化序列化、`OceanBaseCheckpointSaver` 的事务/乐观锁、Mapper SQL 的 Lease/Fence/CAS 条件，以及 `WorkflowFinalizationService` 的单事务收口结构；这些复杂度属于生产可靠性协议，不做表面简化。
- 未修改 API、Graph 拓扑、数据库表与 SQL、配置值、Prompt、Skill、Tool、Memory 写入边界、SSE 协议或最终审核答案语义。

## 2026-08-13：主工作流关键方法注释与真实链路对齐

- 重新审阅主工作流 HTTP/SSE 入口、应用门面、生命周期 Listener、启动/暂停/收口事务、节点安全门禁和 OceanBase SSE 事件服务的关键复合方法。
- 外层注释不再只描述“调用发布器/调用服务”，而是概括实际下游链路：owner/lease/fencing token 校验、数据库 CAS、sequence 分配、事实表落库、即时 emitter 投递、跨实例 Poller 与 Last-Event-ID 补偿。
- 明确区分事务内完成的状态一致性和事务提交后的网络交付，避免把 Outbox 已落库误写为前端已经收到；同时记录失败是否回滚业务 Graph、何时保留游标、何时关闭连接。
- 补全工作流启动、人工确认、Checkpoint 恢复、动态执行收口和迟到异常幂等保护的入口级说明；普通 getter、简单 DTO 转换和显而易见代码保持简洁。
- 清理 ProductAnalysisAgent、SkillConfig、AiConfig、Memory 等 Phase1 遗留描述，修正“尚未接入 Workflow/Memory/Tool”与当前四领域 Agent、动态 DAG、MyBatis/OceanBase 实现不一致的问题。
- 本次只优化注释与项目理解信息，不改变 API、Bean、Graph、SQL、SSE 协议或业务执行逻辑。

## 2026-08-13：增加 IDEA 长断点调试 Profile

- 新增 `local-debug` Profile Group，按顺序组合 `local-db` 与 `debug-timing`；IDEA 只需在 Active profiles 填写 `local-debug`。
- 新增 `application-debug-timing.yml`：SSE 连接与事件重放、execution lease、claim lease 放宽到4小时，人工确认租约放宽到7天，避免 `Suspend All` 暂停 heartbeat 后工作流在观察代码期间过期。
- 数据库事件轮询仍为500ms、Token 合并仍为80ms/128字符，保证调试期间前端首响应和持续流式体验不变。
- Checkpoint/SSE 物理清理首次执行和周期放宽到4小时，恢复扫描为5分钟；普通 `local-db` 与生产默认时间配置没有改变。
- 新增配置绑定和 Profile Group 顺序测试，并同步 README、AGENTS 与项目理解文档。

## 2026-08-13：按业务职责整理 Workflow 包结构

- 保留 `product`、`knowledge`、`policy`、`asset` 四个业务域现有的 Agent/Config/Model/Service/Tool 垂直划分；这些边界已经合理，没有为追求目录数量做无关拆分。
- 将动态计划校验、DAG 调度、任务子图执行和子智能体路由从通用 `ai.workflow.service` 收敛到 `ai.workflow.execution`。
- 将工作流启动、暂停、事务收口、Lease/Fence 节点门禁和 Graph 生命周期观测收敛到 `ai.workflow.lifecycle`。
- 将 SSE 配置、事件 Mapper、事件模型、可靠投递服务收敛到 `ai.workflow.sse`，内部继续按 `config/mapper/model/service` 分层；OceanBase 事实表、Last-Event-ID、多实例轮询和 Token 合并行为不变。
- `ai.workflow.service` 只保留主工作流应用编排、上下文对齐、意图识别和产品引用解析；`controller/node/job/checkpoint` 继续维持清晰的技术职责。
- 将没有直接使用 JDBC 的 `JdbcAgentMemoryService` 更名为 `LocalDbAgentMemoryService`，准确表达它是 local-db Profile 下协调 Spring AI ChatMemory 与 MyBatis 服务的事务门面。
- 测试包同步镜像生产目录，更新 `AGENTS.md` 与 `docs/project-understanding-guide.md`；本次未改变 Bean 名称、HTTP/SSE API、Graph 拓扑、数据库表或业务行为。

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
- Checkpoint 清理复用 `OceanBaseCheckpointSaver.purgeExpired()`：当前完成实例默认保留 24 小时，活动或失败实例默认保留 7 天。
- SSE 清理删除 `expire_at` 已到期的事件，当前默认保留 10 分钟并每 30 秒扫描一次。
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

## 2026-08-13：数据库操作说明补全

- 扫描全部 MyBatis Mapper、OceanBase Checkpoint/SSE 持久化、ChatMemory Repository 和清理任务。
- 为工作流状态机 SQL 补充 owner、Lease、fencing token、CAS、终态保护及返回 0 的处理语义。
- 为 Checkpoint 补充 Thread 乐观锁、不可变快照、恢复可读条件和先删快照后删 Thread 的清理顺序。
- 为 SSE 补充原子序号、同事务 `last_insert_id()`、Outbox、Last-Event-ID 重放及物理过期清理说明。
- 为短期记忆、长期记忆、调用审计、召回审计和会话确认产品补充覆盖/追加/幂等边界说明。
- 仅优化注释和项目文档，不修改 SQL、事务边界或业务执行逻辑。

## 2026-09-21：AgentScope Java 迁移第一阶段

- 新建 `feature/agentscope-java-migration` 分支，引入 AgentScope Java 2.0.3 Core 与 OpenAI-compatible 模型扩展。
- 产品、知识、保单、资产、Planner 和 Summary 全部改用 AgentScope `ReActAgent`；全局模型使用 `OpenAIChatModel + DeepSeekFormatter`。
- Spring AI Alibaba Agent Framework 与 DashScope 适配依赖已移除；Tool 改用 AgentScope 注解和独立 Toolkit，Skill 改用按业务域隔离的 `ClasspathSkillRepository`。
- `ReactAgentStreamingExecutor` 改为消费 `streamEvents(...)`，只把 `TextBlockDeltaEvent` 发布到既有可靠 SSE，并从同一次流中的 `AgentResultEvent` 获取最终答案。
- ReAct 轮数上限由 AgentScope `maxIters` 承担；既有 ChatMemory、长期记忆、调用审计、Token 批处理和 SSE Outbox 行为保持不变。
- Spring AI `ChatModel` 暂保留给上下文对齐、意图识别等结构化前置节点；Spring AI Alibaba Graph Core 暂保留给主图、Checkpoint、人工确认和动态 DAG，尚未宣称完成 Graph Runtime 迁移。
- 修复独立非工作流 Agent 调用可能向 AgentScope `RuntimeContext.sessionId` 传入空值的问题。
- Spring Boot 3.5.8 依赖管理当前把 AgentScope 声明的 Reactor 3.8.2 和 Jackson 2.21.x 解析为 3.7.13、2.19.4；自动化测试通过，仍需用真实 DeepSeek Tool Calling 与 SSE 做合并前兼容验收。
- `./gradlew test` 全量 138 项测试通过。

## 2026-09-24：AgentScope 原生能力迁移收口

### Agent 与模型

- 引入 `agentscope-harness` 和 `agentscope-extensions-agui`；产品、知识、保单、资产、Planner、Summary 统一装配为 `HarnessAgent`。
- 前置产品实体解析、上下文对齐、意图识别和会话摘要改用 `AgentScopeModelExecutor`；结构化输出改用 `JsonSchemaUtils + AgentScopeStructuredOutput`。
- 删除 Spring AI `ChatModelStreamingExecutor`、`ChatMemoryConfig` 和直接 OpenAI Model Starter；业务主源码不再导入 `org.springframework.ai.*`。
- 模型配置从 `spring.ai.openai.*` 收口为 `insurance.ai.model.*`，仍通过 `AI_API_KEY`、`AI_BASE_URL`、`AI_MODEL`、`AI_TEMPERATURE` 环境变量覆盖。

### Message、Memory 与 State

- `ai_chat_memory` 窗口仓库直接读写 AgentScope `Msg`，保留完整窗口覆盖、事务双写和 Main Workflow 最终收口语义。
- 新增 `AgentScopeStateStore` 的 OceanBase 实现、数据库 CAS 和 Agent 名称命名空间；默认 profile 使用内存 Store，`local-db` 使用 OceanBase。
- 新增 Flyway V20 `ai_agentscope_state`。工作流内部 Harness 调用使用一次性 session 并在结束后删除，AG-UI 稳定 threadId 可跨请求续接。
- AgentScope State、业务短期窗口、永久历史和 Alibaba Graph Checkpoint 保持四种不同数据语义，不做互相覆盖式替换。

### AG-UI

- 新增 `POST /api/v1/agui/agents/{agentId}/runs`，使用官方 `AguiAgentAdapter` 和 `AguiEventEncoder` 输出标准 SSE。
- 公共 Registry 只开放四个领域 Agent，Planner/Summary 不公开；采用 `AGENT_ONLY` 拒绝前端 Tool 注入，关闭 reasoning 暴露。
- `insurance.ai.agui.run-timeout` 默认10分钟；客户端完成、异常和超时会取消 Reactor subscription，线程池拒绝会结束 SSE。
- AG-UI 只提供领域 Agent 标准实时协议，不替代 Main Graph 的 OceanBase Outbox、Poller、Last-Event-ID 与人工确认恢复。

### Graph 保留边界

- Spring AI Alibaba 1.1.2.0 Graph Core 继续承载 StateGraph、动态 DAG、Checkpoint、Human Confirm 和 GraphLifecycleListener。
- OceanBase CheckpointSaver、Execution Lease/Heartbeat/Fence、conversation lock、可靠 SSE 和最终事务收口均保留；Harness/AG-UI 当前没有等价生产语义。
- Graph Checkpoint 只保存真实工作流业务 State；AgentScope 消息由短期窗口和 AgentStateStore 管理，不复制进 Graph State。

### 验证

- 新增 Agent 状态命名空间隔离、OceanBase StateStore CAS、AG-UI Agent 白名单和协议策略测试。
- `./gradlew clean test` 全量 143 项测试通过；业务主源码扫描确认不存在 `org.springframework.ai.*` 或非 Graph 的 Spring AI Alibaba import。
- 对照 AgentScope Harness 2.0.3 源码确认：统一工厂保留默认上下文溢出压缩与 Tool 结果淘汰，显式关闭文件系统、Shell、子 Agent、自学习 Memory Hook 等不符合当前金融业务边界的能力。
- 运行时依赖解析确认 Reactor 为 3.7.13、Jackson 为 2.19.4、OpenTelemetry 为 1.49.x，低于 AgentScope 2.0.3 声明版本；单元测试通过，真实环境仍需专项验证。
- 真实 DeepSeek Tool Calling、AG-UI 长连接和 local-db V20 迁移仍需在本机运行环境验收。
