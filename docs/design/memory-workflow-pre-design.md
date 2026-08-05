# Memory / Workflow Pre-Design

本文档定义 `insurance-agent` 在 ProductAnalysisAgent Phase1 完成后的 Memory 与 Workflow 前置设计。

当前阶段只做设计定界，不实现 Memory、Workflow、Planner、DAG Executor、Human Confirm 或向量数据库。

## 目标

- 为后续多智能体协同建立可落地的数据边界。
- 使用本地数据库保存会话记忆、调用记录和 Workflow 执行状态。
- 向量召回不在本项目内实现，只预留接口和数据入参。
- 保持当前单模块工程，但包结构按未来多模块拆分准备。

## 非目标

- 不实现 Graph Workflow。
- 不实现 DAG Executor。
- 不实现 Human Confirm。
- 不实现 Vector Database。
- 不把长期记忆直接塞进 ProductAnalysisAgent。
- 不把知识库召回和 Memory 持久化混在一起。

## 总体分层

```text
API
↓
Agent Facade
↓
Workflow Orchestrator
↓
Agent Node
↓
Memory Context Provider
↓
Local Memory Store
```

Spring AI Memory 主线：

```text
ProductAnalysisAgent
↓
ChatMemory.get(conversationId)
↓
history messages + current UserMessage
↓
ReactAgent.call(List<Message>)
↓
ChatMemory.add(conversationId, user + assistant)
↓
JdbcChatMemoryRepository
```

注意：

- `ChatClient` 场景可以通过 `MessageChatMemoryAdvisor` 和 `ChatMemory.CONVERSATION_ID` 注入会话编号。
- 当前项目主调用入口是 Spring AI Alibaba `ReactAgent`。
- `ReactAgent` 支持 `call(List<Message>)`，因此第一版 Memory 接入优先使用 `ChatMemory` 显式读写消息列表。
- `AgentInvocation` 只做审计和观测，不作为 Memory 主表。

向量召回作为外部能力接入：

```text
Agent / Workflow
↓
Retrieval Port
↓
External Vector Retrieval Service
```

## Memory 分类

### 1. Conversation Memory

保存一次对话内的消息历史。

用途：

- 多轮追问时恢复上下文。
- 后续接入 ReactAgent Memory Hook。
- 对齐 `conversationId` 与用户请求。

建议字段：

| 字段 | 说明 |
| --- | --- |
| conversation_id | 会话编号 |
| user_id | 用户编号，当前可为空或 mock |
| agent_name | 智能体名称 |
| title | 会话标题 |
| status | ACTIVE / CLOSED |
| created_at | 创建时间 |
| updated_at | 更新时间 |

### 2. Chat Memory

基于 Spring AI 原生 `ChatMemoryRepository` 保存会话窗口消息。

用途：

- 多轮上下文拼接。
- 后续接入 `MessageWindowChatMemory`。
- 与 Spring AI `ChatMemory` 抽象保持一致。

建议字段：

| 字段 | 说明 |
| --- | --- |
| message_id | 消息编号 |
| conversation_id | 会话编号 |
| message_order | 当前会话窗口内顺序 |
| message_type | USER / ASSISTANT / SYSTEM / TOOL |
| text_content | 消息正文 |
| metadata_json | Spring AI Message metadata |
| created_at | 创建时间 |

### 3. Agent Invocation Memory

保存单次 Agent 调用观测数据。

用途：

- 根据 `invocationId` 对齐 Swagger 响应、日志、消息历史。
- 记录模型调用耗时、输出格式检查、错误信息。
- 支持后续指标看板。

建议字段：

| 字段 | 说明 |
| --- | --- |
| invocation_id | 单次 Agent 调用标识 |
| conversation_id | 会话编号 |
| agent_name | 智能体名称 |
| trace_id | HTTP TraceId |
| model_provider | openai-compatible |
| model_name | deepseek-chat / qwen-plus |
| user_message | 用户输入 |
| assistant_answer | 智能体回答 |
| duration_ms | 调用耗时 |
| output_format_valid | 是否满足 Skill 输出格式 |
| missing_sections | 缺失小标题，JSON 字符串 |
| status | SUCCESS / FAILED |
| error_code | 错误码 |
| error_message | 错误信息 |
| created_at | 创建时间 |

### 4. Long-Term Memory

永久保存历史对话记录和后续抽取出的重要事实。

用途：

- 长期保留完整对话历史，不受 `MessageWindowChatMemory` 裁剪影响。
- 后续支持用户偏好、关键事实、风险提示等长期记忆沉淀。
- 支持按 `conversationId`、`invocationId`、用户、客户、智能体和记忆类型查询。

与 `ai_chat_memory` 的区别：

| 表 | 定位 | 写入方式 | 是否会被窗口裁剪 |
| --- | --- | --- | --- |
| ai_chat_memory | Spring AI 窗口记忆 | 当前会话窗口覆盖保存 | 是 |
| ai_long_term_memory | 永久历史记忆 | 追加保存历史流水 | 否 |

当前接入策略：

- `local-db` profile 下，`ai_chat_memory` 和 `ai_long_term_memory` 都会随成功请求变化。
- `ai_chat_memory` 保存当前会话窗口，可能被 `MessageWindowChatMemory` 裁剪和覆盖。
- `ai_long_term_memory` 每次成功请求追加写入 USER 和 ASSISTANT 两条记录。
- 两张表必须在同一个事务中写入，由 `AgentMemoryService.saveSuccessfulExchange(...)` 统一协调。
- 如果窗口记忆或长期记忆任意一方写入失败，本次记忆写入整体回滚，避免两张表状态不一致。
- 默认 profile 下使用 `NoOpAgentMemoryService`，不写本地数据库。

建议字段：

| 字段 | 说明 |
| --- | --- |
| memory_id | 长期记忆编号 |
| conversation_id | 会话编号 |
| invocation_id | 关联 Agent 调用编号 |
| agent_name | 智能体名称 |
| user_id | 用户编号 |
| customer_id | 客户编号 |
| operator_id | 操作员编号 |
| memory_type | MESSAGE / SUMMARY / PREFERENCE / FACT / RISK_NOTE |
| role | USER / ASSISTANT / SYSTEM / TOOL |
| content | 长期记忆原文内容 |
| summary | 长期记忆摘要 |
| tags_json | 标签 JSON |
| importance_score | 重要性评分 |
| archived | 是否归档 |
| metadata_json | 扩展元数据 JSON |
| occurred_at | 业务事件发生时间 |
| created_at | 创建时间 |

### 5. Summary Memory

保存会话摘要。

用途：

- 避免长对话每次全部塞回模型。
- 后续支持长期记忆压缩。

建议字段：

| 字段 | 说明 |
| --- | --- |
| summary_id | 摘要编号 |
| conversation_id | 会话编号 |
| agent_name | 智能体名称 |
| summary | 摘要内容 |
| source_message_start_id | 摘要覆盖起始消息 |
| source_message_end_id | 摘要覆盖结束消息 |
| created_at | 创建时间 |

## 已确认设计决策

| 问题 | 决策 |
| --- | --- |
| 本地数据库 | 使用本地 OceanBase/MySQL 协议连接 |
| 本地连接方式 | `obclient -h127.0.0.1 -P2881 -uroot -p` |
| 模型输入输出是否可明文存储 | 允许 |
| 身份体系 | 当前先使用 mock user/customer/operator |
| 数据保留周期 | 永久保留 |
| Workflow 范围 | 尽量完整实现，但拆成多个小步骤 |
| 向量召回方式 | Java 代码调用其他系统微应用接口 |

## 本地数据库方案

当前技术验证阶段使用本地 OceanBase，通过 MySQL 协议和 JDBC 连接。

命令行连接：

```bash
obclient -h127.0.0.1 -P2881 -uroot -p
```

应用配置使用 `local-db` profile：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:2881/insurance_agent
    username: root
    password: ${DB_PASSWORD}
```

需要提前创建数据库：

```sql
create database if not exists insurance_agent default character set utf8mb4;
```

当前不在代码中硬编码数据库密码。IDEA 启动时建议配置：

```text
SPRING_PROFILES_ACTIVE=local-db
DB_URL=jdbc:mysql://127.0.0.1:2881/insurance_agent?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=你的本地数据库密码
```

## Flyway 建表流程

使用 `local-db` profile 启动项目时，建表流程如下：

```text
Spring Boot 启动
↓
加载 application.yml
↓
加载 application-local-db.yml
↓
取消默认 profile 中的 DataSource/Flyway 排除
↓
创建 DataSource，连接 DB_URL 指向的本地 OceanBase 库
↓
FlywayAutoConfiguration 生效
↓
Flyway 检查 flyway_schema_history
↓
按版本顺序执行 classpath:db/migration 下尚未执行过的脚本
↓
执行 V1__create_memory_workflow_tables.sql
↓
创建 Memory / Invocation / Workflow / Retrieval 表
↓
启动 Spring 容器其他 Bean
```

因此：

- 数据库 schema `insurance_agent` 需要手动提前创建。
- 业务表不需要手动创建，由 Flyway 自动执行脚本。
- Flyway 会自动维护 `flyway_schema_history` 表，用于记录哪些版本已执行。
- 如果某个版本已经执行过，Flyway 不会重复执行同名版本脚本。

注意：

- 如果你已经用旧版 `V1__create_memory_workflow_tables.sql` 启动过 `local-db`，Flyway 已记录 V1。
- 这时修改 V1 内容后再次启动，Flyway 会校验失败或不会重新建表。
- 技术验证阶段最简单的处理方式是删除本地 `insurance_agent` 库后重建，再启动项目。
- 如果库中已有需要保留的数据，则应新增 `V2__xxx.sql` 做增量变更，而不是修改 V1。
- OceanBase 本地环境可能不识别 MySQL 的 `engine = innodb` 子句，因此建表脚本不显式指定存储引擎。

## Workflow 前置模型

Workflow 不等于马上实现 DAG。

当前先定义执行状态与节点边界：

```text
WorkflowDefinition
↓
WorkflowInstance
↓
WorkflowStep
↓
AgentInvocation
```

### WorkflowDefinition

表示一个工作流模板。

示例：

- `product-analysis-only`
- `product-analysis-with-knowledge`
- `customer-policy-analysis`

### WorkflowInstance

表示一次工作流执行。

建议字段：

| 字段 | 说明 |
| --- | --- |
| workflow_instance_id | 工作流实例编号 |
| workflow_code | 工作流编码 |
| conversation_id | 会话编号 |
| status | RUNNING / SUCCESS / FAILED / WAITING_CONFIRM |
| input | 输入 JSON |
| output | 输出 JSON |
| created_at | 创建时间 |
| updated_at | 更新时间 |

### WorkflowStep

表示工作流中的一个步骤。

建议字段：

| 字段 | 说明 |
| --- | --- |
| step_id | 步骤编号 |
| workflow_instance_id | 工作流实例编号 |
| step_code | 步骤编码 |
| step_type | AGENT / TOOL / RETRIEVAL / HUMAN_CONFIRM |
| target | 目标 Agent / Tool / Retrieval Port |
| status | PENDING / RUNNING / SUCCESS / FAILED / SKIPPED |
| input | 输入 JSON |
| output | 输出 JSON |
| error_message | 错误信息 |
| started_at | 开始时间 |
| ended_at | 结束时间 |

## 向量召回边界

向量能力由外部成熟系统提供，本项目只定义端口。

建议接口：

```java
public interface RetrievalService {

    RetrievalResult retrieve(RetrievalRequest request);
}
```

建议入参：

| 字段 | 说明 |
| --- | --- |
| query | 用户问题或 Agent 改写后的查询 |
| domain | product / policy / knowledge / asset |
| topK | 召回条数 |
| filters | 过滤条件，例如产品编码、客户等级、知识库分类 |
| traceId | 链路标识 |

建议出参：

| 字段 | 说明 |
| --- | --- |
| chunks | 召回文本片段 |
| source | 来源 |
| score | 相似度 |
| metadata | 元数据 |

## 包结构建议

当前仍保持单模块：

```text
com.xxx.insurance
├── ai
│   ├── memory
│   │   ├── config
│   │   ├── model
│   │   ├── repository
│   │   └── service
│   ├── workflow
│   │   ├── model
│   │   ├── repository
│   │   ├── service
│   │   └── config
│   └── retrieval
│       ├── model
│       └── service
```

未来拆分：

```text
ai-core
├── memory
├── workflow
└── retrieval
```

## 实施顺序建议

### Phase2-Task1：本地数据库与表结构

- 引入本地数据库依赖。
- 增加 datasource 配置。
- 建立 Spring AI ChatMemory / Invocation / Workflow 表结构。
- 实现 `JdbcChatMemoryRepository`。
- 暂不接 Agent。

### Phase2-Task2：Invocation 持久化

- 在 ProductAnalysisAgent 调用完成后保存 Agent Invocation。
- 保存用户问题、模型回答、耗时、格式检查、错误信息。
- 提供只读查询 API。

### Phase2-Task3：Conversation / Message Memory

- 将 `ChatMemory` 接入 ProductAnalysisAgent。
- 调用前读取 `conversationId` 对应历史消息。
- 调用后写入用户消息和 Assistant 消息。
- 使用 `ReactAgent.call(List<Message>)` 保留 Skill 和 Tool Calling 能力。
- 默认 profile 没有 `ChatMemory` Bean，仍保持单轮调用。
- `local-db` profile 下创建 `ChatMemory` Bean，并使用 `JdbcChatMemoryRepository` 持久化消息窗口。

### Phase2-Task4：Memory Context Provider

- 从本地数据库读取最近 N 条消息和摘要。
- 构造成 Agent 可用上下文。
- 再决定是否接入 Spring AI Alibaba Memory Hook。

### Phase2-Task5：Workflow 状态模型

- 建立 WorkflowInstance / WorkflowStep。
- 支持单节点 `product-analysis-only`。
- 只记录状态，不做复杂 DAG。

### Phase2-Task6：Retrieval Port

- 定义外部向量召回接口。
- 用 mock 实现返回召回数据。
- 后续替换为行内成熟召回服务。

## 需要确认的关键数据

1. 本地数据库优先用 H2 file mode，还是你更希望直接用 MySQL / PostgreSQL？
2. 是否已经有用户体系？如果有，`userId`、`customerId`、`operatorId` 是否需要分开？
3. 会话数据是否需要区分“客户会话”和“内部测试会话”？
4. 模型输入和模型输出是否允许明文落本地库？如果不允许，需要做脱敏或只存摘要。
5. 本地库数据保留周期是多少？例如 7 天、30 天、永久保留。
6. Workflow 第一版是否只做 `product-analysis-only` 单节点状态记录？
7. 外部向量召回服务最终会通过 HTTP、RPC，还是 Java SDK 调用？
