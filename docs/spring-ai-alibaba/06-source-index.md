# 来源索引

访问日期均为 2026-08-06。官网页面会滚动更新；“本地源码”固定为项目解析出的 `1.1.2.0` sources JAR。

| 模块 | 页面标题/源码 | URL 或路径 | 主要内容 | 对应章节 |
|---|---|---|---|---|
| 总览 | Spring AI Alibaba Overview | https://java2ai.com/docs/overview/ | 模块定位和架构 | README、03 |
| 版本 | Version Management | https://java2ai.com/docs/versions/ | 版本线和依赖管理 | README、05 |
| Agent | Agents | https://java2ai.com/en/docs/frameworks/agent-framework/tutorials/agents/ | ReactAgent、调用、流式、RunnableConfig | 01、05 |
| Agent | Models | https://java2ai.com/docs/frameworks/agent-framework/tutorials/models/ | ChatModel 配置 | 01 |
| Agent | Messages | https://java2ai.com/docs/frameworks/agent-framework/tutorials/messages/ | Message 类型和上下文 | 01 |
| Agent | Tools | https://java2ai.com/en/docs/frameworks/agent-framework/tutorials/tools/ | ToolCallback、ToolContext、状态更新 | 01、05 |
| Agent | Memory | https://java2ai.com/docs/frameworks/agent-framework/tutorials/memory/ | 短期记忆、Saver、threadId | 01、04 |
| Agent | Long-term Memory | https://java2ai.com/docs/frameworks/agent-framework/advanced/memory/ | MemoryStore | 01、04 |
| Agent | Hooks | https://java2ai.com/docs/frameworks/agent-framework/tutorials/hooks/ | Hook 与 Interceptor | 01、05 |
| Agent | Skills | https://java2ai.com/docs/frameworks/agent-framework/tutorials/skills/ | Registry、渐进披露、groupedTools | 01、05 |
| Agent | Structured Output | https://java2ai.com/en/docs/frameworks/agent-framework/tutorials/structured-output/ | outputType、Schema | 01 |
| Agent | Multi-agent | https://java2ai.com/docs/frameworks/agent-framework/advanced/multi-agent/ | Sequential、Parallel、Routing、Loop 等 | 01、03 |
| Agent | Agent Tool（旧版本站点，辅助核对） | https://v1100.java2ai.com/docs/frameworks/agent-framework/advanced/agent-tool/ | Agent As Tool 模式 | 01、03 |
| Graph | Quick Start | https://java2ai.com/docs/frameworks/graph-core/quick-start/ | 最小 StateGraph | 02、05 |
| Graph | Core Library | https://java2ai.com/docs/frameworks/graph-core/core/core-library/ | State、Node、Edge、策略、编译 | 02 |
| Graph | Persistence | https://java2ai.com/en/docs/frameworks/graph-core/core/persistence/ | Saver、Checkpoint、Replay、历史 | 02、04 |
| Graph | Memory | https://java2ai.com/en/docs/frameworks/graph-core/core/memory/ | Store 与长期记忆 | 02、04 |
| Graph | Streaming | https://java2ai.com/docs/frameworks/graph-core/core/streaming/ | NodeOutput、StreamingOutput | 02、04 |
| Graph | LLM Streaming | https://java2ai.com/docs/frameworks/graph-core/examples/llm-streaming-springai/ | 模型 Token 流 | 02 |
| Graph | Human In The Loop | https://java2ai.com/docs/frameworks/graph-core/examples/human-in-the-loop/ | interruptBefore/After | 02、04 |
| Graph | Wait User Input | https://java2ai.com/en/docs/frameworks/graph-core/examples/wait-user-input/ | 暂停和恢复 | 02、04 |
| Graph | Parallel Branch | https://java2ai.com/docs/frameworks/graph-core/examples/parallel-branch/ | 并行分支与聚合 | 02 |
| Graph | Parallel Streaming | https://java2ai.com/docs/frameworks/graph-core/examples/parallel-streaming | 并行流式事件 | 02 |
| Graph | Subgraph | https://java2ai.com/docs/frameworks/graph-core/examples/subgraph/ | 子图总览 | 02 |
| Graph | StateGraph Subgraph | https://java2ai.com/docs/frameworks/graph-core/examples/subgraph-as-stategraph/ | StateGraph 作为子图 | 02 |
| Graph | CompiledGraph Subgraph | https://java2ai.com/docs/frameworks/graph-core/examples/subgraph-as-compiledgraph/ | 编译图作为节点 | 02 |
| Graph | NodeAction Subgraph | https://java2ai.com/docs/frameworks/graph-core/examples/subgraph-as-nodeaction/ | 包装子图动作 | 02 |
| Graph | Multi-agent Supervisor | https://java2ai.com/docs/frameworks/graph-core/examples/multi-agent-supervisor/ | Supervisor 模式示例 | 01、03 |
| Graph | Redis Checkpoint | https://java2ai.com/en/docs/frameworks/graph-core/examples/checkpoint-redis/ | RedisSaver 依赖与恢复示例 | 02、05 |
| 发布 | 1.1.2.0 中文发布说明 | https://java2ai.com/blog/saa-1120-release/ | AllOf/AnyOf、Skills、模式清单 | 01、02、05 |
| 发布 | v1.1.2.0 Release | https://github.com/alibaba/spring-ai-alibaba/releases/tag/v1.1.2.0 | Skills、streamMessages、并行、returnDirect | 全部 |
| GitHub | 官方仓库 README | https://github.com/alibaba/spring-ai-alibaba | 模块关系和当前能力 | README、03 |
| GitHub | 官方 Examples | https://github.com/spring-ai-alibaba/examples | 示例交叉验证 | 01、02 |
| 源码 | ReactAgent / Agent / Builder | Gradle Cache `spring-ai-alibaba-agent-framework-1.1.2.0-sources.jar`, `com/alibaba/cloud/ai/graph/agent/` | call/invoke/stream、Builder、内部图 | 01、05 |
| 源码 | Hooks / Interceptors / SkillsAgentHook | 同上，`agent/hook/`、`agent/interceptor/` | 生命周期、read/search/disable skill | 01、05 |
| 源码 | Flow Agents | 同上，`agent/flow/agent/` | Sequential、Parallel、Routing、Loop | 01、03 |
| 源码 | StateGraph / CompiledGraph | Gradle Cache `spring-ai-alibaba-graph-core-1.1.2.0-sources.jar`, `com/alibaba/cloud/ai/graph/` | 图 API、状态、流式、恢复 | 02、05 |
| 源码 | Saver 实现 | 同上，`graph/checkpoint/savers/` | Memory、Redis、Postgres、Mongo Saver | 02、05 |
| 源码 | Strategies | 同上，`graph/KeyStrategyFactory.java`、`graph/strategy/` | Replace、Append、Merge | 02、05 |
| 项目 | build.gradle | `build.gradle` | 当前实际版本与依赖 | README |
| 项目 | Agent/Skill/Memory/Workflow 源码 | `src/main/java/com/xxx/insurance/` | 当前能力基线与落地映射 | 04 |

## Git Tag 与版本说明

官方 GitHub 可核验的 `v1.1.2.0` Tag 对应 Commit `8177021`，本项目将其作为官方架构和功能文档基准；Release 页面当前还列出 `v1.1.2.2` Commit `7405a7d`。项目实际运行使用 Maven 构件 `1.1.2.0`，其 JAR Manifest 与 `pom.properties` 均确认版本。Java 编译与精确 API 仍以本地 `1.1.2.0` sources JAR 为准，并用公开 Tag 交叉核验。
