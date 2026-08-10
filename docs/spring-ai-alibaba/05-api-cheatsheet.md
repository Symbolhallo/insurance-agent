# API 速查表

> 当前版本：Spring AI Alibaba `1.1.2.3`，Spring AI `1.1.2`。示例省略业务 Bean 定义，但类名和方法已按本地源码核对。

## Agent Framework

### ReactAgent Builder

| 项目 | 内容 |
|---|---|
| 完整类名 | `com.alibaba.cloud.ai.graph.agent.ReactAgent` / `com.alibaba.cloud.ai.graph.agent.Builder` |
| 模块 | `spring-ai-alibaba-agent-framework` |
| 作用 | 构造 ReAct Agent |
| 常用参数 | name、description、model/chatClient、instruction/systemPrompt、tools/methodTools、hooks、interceptors、saver、outputType |

```java
ReactAgent agent = ReactAgent.builder()
        .name("product-analysis-agent")
        .description("保险产品分析")
        .model(chatModel)
        .instruction("基于工具事实回答")
        .methodTools(productAnalysisTool)
        .hooks(skillsAgentHook)
        .parallelToolExecution(false)
        .build();
```

Builder 还支持 `toolExecutionTimeout(Duration)`、`maxParallelTools(int)`、`releaseThread(boolean)`、`stateSerializer(...)` 和 `executor(Executor)`。Agent Bean 可复用，请求数据不可写成员变量。

### call / invoke / stream / streamMessages

| API | 声明位置 | 返回值 | 注意事项 |
|---|---|---|---|
| `call` | `ReactAgent` | `AssistantMessage` | 最终消息便捷 API |
| `invoke` | `Agent` | `Optional<OverAllState>` | 读取完整状态 |
| `stream` | `Agent` | `Flux<NodeOutput>` | 包含节点和流式事件 |
| `streamMessages` | `Agent` | `Flux<Message>` | 只提取消息 |

```java
AssistantMessage answer = agent.call(input, config);
OverAllState state = agent.invoke(input, config).orElseThrow();
Flux<NodeOutput> events = agent.stream(input, config);
Flux<Message> messages = agent.streamMessages(input, config);
```

每个 API 支持 `String`、`UserMessage`、`List<Message>`、`Map<String,Object>` 重载。Map 的保留 key 为 `messages` 和 `input`。

### RunnableConfig / threadId

| 项目 | 内容 |
|---|---|
| 完整类名 | `com.alibaba.cloud.ai.graph.RunnableConfig` |
| 模块 | `spring-ai-alibaba-graph-core` |
| 作用 | 请求级执行、会话、Checkpoint、stream 和恢复配置 |

```java
RunnableConfig config = RunnableConfig.builder()
        .threadId(conversationId)
        .checkPointId(checkpointId)
        .mergeReasoningContent(true)
        .build();
```

可变执行状态不应跨并发请求共享同一个 Config。Checkpoint/HITL 必须提供 threadId。

### ToolCallback 与 @Tool

| 项目 | 内容 |
|---|---|
| 完整类名 | `org.springframework.ai.tool.ToolCallback` |
| 注解 | `org.springframework.ai.tool.annotation.Tool`、`ToolParam` |
| 模块 | Spring AI Core |
| 作用 | 向模型声明和执行工具 |

```java
ToolCallback[] callbacks = ToolCallbacks.from(productAnalysisTool);
ReactAgent agent = ReactAgent.builder().model(chatModel).tools(callbacks).build();
```

`ToolContext` 用于隐藏请求上下文；权限仍在 Service 校验。`returnDirect` 会绕过后续模型生成，金融答案慎用。

### Hooks

| 类型 | 完整类名 | 核心方法 |
|---|---|---|
| Agent Hook | `com.alibaba.cloud.ai.graph.agent.hook.AgentHook` | `beforeAgent`、`afterAgent` |
| Model Hook | `com.alibaba.cloud.ai.graph.agent.hook.ModelHook` | `beforeModel`、`afterModel` |
| 通用接口 | `com.alibaba.cloud.ai.graph.agent.hook.Hook` | tools、interceptors、KeyStrategy、jumpTo |

```java
AgentHook auditHook = new AgentHook() {
    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(
            OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(Map.of("startedAt", Instant.now().toString()));
    }
};
```

返回 Map 会合并进 State。Hook 节点参与 Graph 生命周期，不能执行无限阻塞操作。

### Interceptors

| 类型 | 完整类名 | 核心方法 |
|---|---|---|
| Model | `...agent.interceptor.ModelInterceptor` | `interceptModel(request, handler)` |
| Tool | `...agent.interceptor.ToolInterceptor` | `interceptToolCall(request, handler)` |
| Streaming | `...agent.interceptor.StreamingModelInterceptor` | before/chunk/complete/error |

```java
ToolRetryInterceptor retry = ToolRetryInterceptor.builder()
        .maxRetries(2)
        .toolName("product_analysis")
        .initialDelay(200)
        .build();
```

只重试幂等 Tool。内建能力还包括 ModelRetry/Fallback、ToolError/Selection、ContextEditing 等。

### SkillsAgentHook / SkillRegistry

| API | 完整类名 | 作用 |
|---|---|---|
| `SkillRegistry` | `com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry` | Skill 元数据与全文访问 |
| Classpath | `...registry.classpath.ClasspathSkillRegistry` | 从 resources/JAR 加载 |
| FileSystem | `...registry.filesystem.FileSystemSkillRegistry` | 从用户/项目目录加载 |
| Hook | `com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook` | 注入描述和 Skill Tools |

```java
SkillRegistry registry = ClasspathSkillRegistry.builder()
        .classpathPath("skills/product-analysis")
        .build();
SkillsAgentHook hook = SkillsAgentHook.builder()
        .skillRegistry(registry)
        .autoReload(false)
        .groupedTools(Map.of("limited-product-analysis", productTools))
        .build();
```

`1.1.2.3` Hook 提供 `read_skill`、`search_skills`、`disable_skill`。按 Agent 隔离 Registry 根路径。

## Graph Core

### StateGraph / addNode / addEdge

| 项目 | 内容 |
|---|---|
| 完整类名 | `com.alibaba.cloud.ai.graph.StateGraph` |
| 模块 | `spring-ai-alibaba-graph-core` |
| 作用 | 定义状态、节点和边 |

```java
StateGraph graph = new StateGraph("insurance-main", keyStrategyFactory)
        .addNode("load_context", node_async(loadContextNode))
        .addNode("summary", node_async(summaryNode))
        .addEdge(START, "load_context")
        .addEdge("load_context", "summary")
        .addEdge("summary", END);
```

同步 `NodeAction` 用 `AsyncNodeAction.node_async` 适配。还可直接 `addNode(id, StateGraph/CompiledGraph)`。

### addConditionalEdges / 并行边

```java
graph.addConditionalEdges("check",
        edge_async(state -> state.value("needConfirm", Boolean.class).orElse(false)
                ? "yes" : "no"),
        Map.of("yes", "confirm", "no", "route"));

graph.addEdge("route", List.of("product_agent", "knowledge_agent"))
     .addEdge(List.of("product_agent", "knowledge_agent"), "join");
```

动态并行使用 `addParallelConditionalEdges`。本地没有公开 `AllOf`/`AnyOf` 类；不要编造 `.allOf()` Builder。

### compile / invoke / stream

```java
CompiledGraph compiled = graph.compile(compileConfig);
Optional<OverAllState> result = compiled.invoke(input, config);
Flux<NodeOutput> output = compiled.stream(input, config);
Flux<GraphResponse<NodeOutput>> responses = compiled.graphResponseStream(input, config);
```

`compile()` 校验并冻结可执行拓扑。CompiledGraph 应作为 Bean 复用。

### interruptBefore / interruptAfter

```java
CompileConfig compileConfig = CompileConfig.builder()
        .interruptBefore("human_confirm_product")
        .interruptAfter("output_review")
        .saverConfig(saverConfig)
        .recursionLimit(30)
        .build();
```

中断必须配 Saver 和 threadId 才能可靠恢复。等待用户期间结束当前 HTTP/执行线程。

### SaverConfig / MemorySaver

| API | 完整类名 |
|---|---|
| Saver 抽象 | `com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver` |
| 配置 | `com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig` |
| 内存 | `com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver` |

```java
MemorySaver memorySaver = new MemorySaver();
SaverConfig saverConfig = SaverConfig.builder().register(memorySaver).build();
```

生产类包括 `RedisSaver`、`PostgresSaver`、`MongoSaver`。当前项目已确定实现 `BaseCheckpointSaver` 的 OceanBase 自定义实现，不使用这些 Saver 作为生产主方案。

### 自定义 BaseCheckpointSaver

| 项目 | 内容 |
|---|---|
| 完整类名 | `com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver` |
| 必须实现 | `list`、`get`、`put`、`release` |
| 项目实现 | `com.xxx.insurance.ai.workflow.checkpoint.OceanBaseCheckpointSaver` |

```text
public final class OceanBaseCheckpointSaver implements BaseCheckpointSaver {
    // list/get 从 OceanBase 加载，put 事务写入，release 逻辑释放。
    // 完整实现将在 Checkpoint 阶段提供，此处不展示未经测试的持久化代码。
}
```

注意：这只是接口轮廓而非 Java 示例；实现阶段需补齐四个接口方法、MyBatis Mapper、序列化器、事务与乐观锁测试。

### Checkpoint、状态历史与更新

| API | 作用 |
|---|---|
| `compiled.getState(config)` | 当前快照 |
| `compiled.getStateHistory(config)` | thread 历史 |
| `compiled.updateState(config, values)` | 更新状态并返回新 Config |
| `compiled.updateState(config, values, asNode)` | 以指定节点身份更新 |
| `RunnableConfig.withCheckPointId(id)` | 指向历史 Checkpoint |
| `RunnableConfig.withResume()` | 标记恢复 |

```java
StateSnapshot snapshot = compiled.getState(config);
RunnableConfig resumed = compiled.updateState(
        config, Map.of("confirmedProducts", selected), "human_confirm_product");
compiled.invoke(Map.of(), resumed.withResume());
```

Replay 要求副作用节点幂等；用 checkpointId + 业务版本校验用户确认。

### StreamingOutput / OutputType

| API | 完整类名 |
|---|---|
| 流式输出 | `com.alibaba.cloud.ai.graph.streaming.StreamingOutput<T>` |
| 类型枚举 | `com.alibaba.cloud.ai.graph.streaming.OutputType` |

```java
if (nodeOutput instanceof StreamingOutput<?> streaming
        && streaming.getOutputType() == OutputType.AGENT_MODEL_STREAMING) {
    Message message = streaming.message();
}
```

`StreamingOutput.chunk()` 已 deprecated；使用 `message()`。OutputType 有 Agent Model/Tool/Hook/Graph Node 的 streaming 与 finished 共八种。

## 常见包名纠错

| 易错写法 | `1.1.2.3` 正确写法 |
|---|---|
| `PostgreSqlSaver` | `PostgresSaver` |
| `graph.strategy.ReplaceStrategy` | `graph.state.strategy.ReplaceStrategy` |
| `ReactAgent.Builder` | Builder 返回类型为 `com.alibaba.cloud.ai.graph.agent.Builder` |
| `AllOf` / `AnyOf` Builder | 本地无该公开类，使用并行边与聚合策略 |
| 只读 `streaming.chunk()` | 使用 `streaming.message()` |
| `SupervisorAgent` | 本地未发现独立类；用 AgentTool/Graph 模式 |
