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

下一步：

- Phase1-Task5：ProductAnalysisTool 与 ReactAgent Tool Calling 集成

## 架构边界

当前保持单模块工程，但包结构按未来多模块演进设计：

```text
com.xxx.insurance
├── ai
│   ├── agent
│   ├── config
│   ├── memory
│   ├── skill
│   └── tool
├── product
│   ├── agent
│   ├── config
│   ├── formatter
│   ├── model
│   ├── service
│   ├── skill
│   └── tool
└── common
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

## 项目记忆

长期开发约束和阶段记录维护在：

```text
AGENTS.md
```

后续使用 Codex/Cursor/IDEA 开发时，请优先遵守该文件中的阶段边界、依赖规则、Skill 规范和禁止事项。
