# insurance-agent

基于 Spring AI Alibaba 的保险产品管理智能体技术验证项目。

当前目标是以单模块 Gradle 工程验证金融保险智能体平台的基础架构，后续可演进为多智能体、多模型、多 Workflow 的银行金融智能体平台。

## 技术栈

- Java 21
- Spring Boot 3.5.8
- Gradle
- Spring AI 1.1.2
- Spring AI Alibaba 1.1.2.3
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
│   └── tool
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
JdbcChatMemoryRepository
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

启用 `local-db` profile 后，每次成功请求会更新 `ai_chat_memory`，并向 `ai_long_term_memory` 追加 USER 和 ASSISTANT 两条长期记忆。
两张表由 `AgentMemoryService` 在同一个事务内写入，任一写入失败都会整体回滚。

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
```

你只需要手动创建 `insurance_agent` 数据库，业务表由 Flyway 自动创建。
