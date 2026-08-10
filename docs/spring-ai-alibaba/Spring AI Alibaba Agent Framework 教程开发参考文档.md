# Spring AI Alibaba Agent Framework 教程开发参考文档

> 适用对象：Codex、Java 开发人员、Agent 工作流设计人员
> 适用项目：基于 Spring AI Alibaba `ReactAgent`、Tool、Skill、Memory、Hook、Interceptor 构建的智能体系统
> 文档范围：Agent Framework 左侧“教程”栏目下的全部 8 个章节

---

## 使用说明

Codex 在依据本文修改项目代码前，必须先检查：

1. 项目现有的 `build.gradle`、`application.yml` 和模型配置类。
2. 实际使用的 Spring AI Alibaba 版本。
3. 对应版本的源码 JAR 和已有代码，不要机械复制官网示例。
4. 项目是否已经定义 `ChatModel`、`ReactAgent`、`SkillRegistry`、`Saver` 等 Bean。
5. 新增实现不能重复创建模型客户端、重复保存消息或破坏现有会话状态。

官网部分示例中的依赖版本与当前项目可能不同。当前项目以 `build.gradle` 中实际使用的 `1.1.2.3` 为准，遇到 API 差异时，应优先查看本地 Gradle 缓存中的 `1.1.2.3-sources.jar`。

---

# Agents

## 1. 章节定位

Agent 是将大语言模型、工具和执行状态组合起来的自动化系统。Spring AI Alibaba 提供的核心实现是 `ReactAgent`。

`ReactAgent` 基于 ReAct，即 Reasoning + Acting 模式运行：

1. 模型分析当前问题。
2. 模型决定是否调用工具。
3. 应用程序执行工具。
4. 工具结果作为 Observation 返回模型。
5. 模型继续推理，直到生成最终答案或达到停止条件。

底层执行并不是简单的 `while` 循环，而是建立在 Graph Runtime 上，主要包含模型节点、工具节点和 Hook 节点。

## 2. ReactAgent 核心配置

常用 Builder 参数包括：

```java
ReactAgent.builder()
    .name("product_analysis_agent")
    .description("保险产品分析智能体")
    .model(chatModel)
    .instruction(agentInstruction)
    .tools(toolCallbacks)
    .methodTools(toolObjects)
    .hooks(hooks)
    .interceptors(interceptors)
    .saver(checkpointSaver)
    .outputType(OutputDto.class)
    .build();
```

主要参数职责：

* `name`：Agent 唯一名称，建议使用稳定的英文标识。
* `description`：供多 Agent 编排器理解该 Agent 能做什么。
* `model`：负责推理和工具选择的 `ChatModel`。
* `systemPrompt`：较短的系统角色设置。
* `instruction`：较详细的任务规则、执行步骤和约束。
* `tools`：直接传入 `ToolCallback`。
* `methodTools`：扫描对象上的 `@Tool` 方法。
* `hooks`：在 Agent 或模型节点执行前后处理状态。
* `interceptors`：拦截模型请求、模型响应和工具调用。
* `saver`：保存 Graph 状态和会话短期记忆。
* `outputType` / `outputSchema`：限制最终输出结构。

## 3. Agent 调用方式

### call

适合只关心最终回答：

```java
AssistantMessage response = agent.call(userQuery, runnableConfig);
```

### invoke

适合获取完整 Graph 状态：

```java
Optional<OverAllState> result = agent.invoke(userQuery, runnableConfig);
```

可以从 `OverAllState` 中获取：

* `messages`
* 自定义状态字段
* `structured_output`
* 工作流中间结果

### stream

适合 SSE 流式输出：

```java
Flux<NodeOutput> stream = agent.stream(userQuery, runnableConfig);
```

不能把所有 `NodeOutput` 直接输出给前端，应根据 `StreamingOutput.getOutputType()` 过滤：

* `AGENT_MODEL_STREAMING`：模型增量文本。
* `AGENT_MODEL_FINISHED`：模型调用完成，也可能包含工具调用请求。
* `AGENT_TOOL_FINISHED`：工具执行完成。
* `AGENT_HOOK_FINISHED`：Hook 节点完成。

对于 Thinking 模型，还要检查 `AssistantMessage.metadata.reasoningContent`，避免把思考内容与最终回答混在一起。

## 4. RunnableConfig

`RunnableConfig` 是一次 Agent 执行的运行时配置：

```java
RunnableConfig config = RunnableConfig.builder()
    .threadId(conversationId)
    .addMetadata("customerId", customerId)
    .addMetadata("userId", userId)
    .build();
```

建议职责划分：

* `threadId`：会话或工作流实例标识。
* `metadata`：调用期间不可直接由模型修改的业务上下文。
* `context()`：Hook 和 Interceptor 之间共享的临时执行数据。

## 5. 项目开发建议

保险产品管理智能体中：

* 主工作流使用 Graph 负责编排。
* 产品分析、知识问答、保单查询、资产查询分别使用独立 `ReactAgent`。
* Agent 内部负责推理和工具调用。
* Graph 负责节点依赖、并行执行、失败跳过、人工确认和结果汇总。
* 不要把所有工作流判断全部塞入一个超大 `ReactAgent`。
* 每个 Agent 都应配置最大模型调用次数，防止 ReAct 循环失控。
* 查询类 Agent 可以自动执行；数据库修改、消息发送等操作必须增加人工确认。

---

# Models 模型

## 1. 模型抽象层次

Spring AI 使用统一接口屏蔽不同模型厂商的差异。

主要接口关系：

```text
Model
├── ChatModel
│   └── call(Prompt) -> ChatResponse
└── StreamingModel
    └── stream(Prompt) -> Flux<ChatResponse>
```

主要对象：

* `ModelRequest`：模型输入抽象。
* `ModelOptions`：模型参数抽象。
* `ModelResponse`：模型响应抽象。
* `ModelResult`：单个模型生成结果。
* `Prompt`：消息列表和运行时参数。
* `ChatResponse`：模型完整响应。
* `Generation`：单个 `AssistantMessage` 及其元数据。

`ChatModel` 同时支持普通调用和流式调用，因此业务代码不应直接依赖具体模型厂商的 HTTP 客户端。

## 2. ChatOptions

常见参数包括：

* `model`
* `temperature`
* `maxTokens`
* `topP`
* `topK`
* `frequencyPenalty`
* `presencePenalty`
* `stopSequences`

参数分为两层：

### 启动默认参数

在创建 `ChatModel` 时设置，作为全局默认值。

### 请求级参数

通过 `Prompt` 传入，只覆盖当前请求：

```java
Prompt prompt = new Prompt(
    userMessage,
    runtimeChatOptions
);
```

运行时参数优先级高于默认参数。

## 3. 模型 Bean 管理

推荐在配置层统一定义模型 Bean：

```java
@Configuration
public class AiModelConfiguration {

    @Bean
    public ChatModel primaryChatModel(...) {
        // 根据项目现有配置创建模型
    }
}
```

业务 Agent 只注入 `ChatModel`，不要：

* 在每个 Agent 内重复创建模型客户端。
* 在工具方法中临时创建 `ChatModel`。
* 在请求级业务代码中修改全局模型默认参数。
* 把 API Key 写进 Java 源码。

## 4. 模型选择原则

不同节点可以使用不同模型，但应通过 Bean 名称或模型路由统一管理：

* 意图识别：低温度、支持结构化输出的模型。
* 产品分析：推理能力较强的模型。
* 对话总结：成本较低、上下文较长的模型。
* 输出审核：低温度、规则遵循能力强的模型。
* Tool Calling：必须确认所选模型正确支持工具调用。
* Structured Output：必须确认所选模型对 JSON 输出的支持程度。

## 5. Codex 实现要求

Codex 不得因为官网示例使用 DashScope，就擅自替换当前项目已有的 `OpenAiChatModel` 或行内模型配置。

新增 Agent 时应优先复用现有 `ChatModel` Bean：

```java
@Bean
public ReactAgent productAnalysisAgent(
        @Qualifier("primaryChatModel") ChatModel chatModel,
        ProductTools productTools) {
    // 构建 Agent
}
```

---

# Messages 消息

## 1. 消息结构

Message 是模型交互和 Agent 状态的基本单位，包含：

* Role：消息角色。
* Content：文本或多模态内容。
* Metadata：消息 ID、模型信息、Token 信息等。

主要消息类型：

* `SystemMessage`
* `UserMessage`
* `AssistantMessage`
* `ToolResponseMessage`

Spring AI 通过这些统一类型适配不同模型提供商。

## 2. SystemMessage

用于定义：

* Agent 身份。
* 业务职责。
* 回答范围。
* 输出规范。
* 禁止事项。
* 工具使用原则。

SystemMessage 应描述稳定规则，不应频繁拼入大量动态业务数据。

动态业务数据应放在：

* `UserMessage`
* Graph State
* `RunnableConfig.metadata`
* ToolContext
* Hook 动态注入内容

## 3. UserMessage

`UserMessage` 可以包含：

* 普通文本。
* Metadata。
* 图片。
* 音频。
* 视频。
* 文件资源。

多模态内容通过 `Media` 定义，但是否真正可用取决于底层模型是否支持对应 MIME 类型和文件大小。

## 4. AssistantMessage

`AssistantMessage` 不只是最终文本，还可能包含：

* `text`
* `metadata`
* `toolCalls`
* `media`

判断模型是否请求调用工具：

```java
if (assistantMessage.hasToolCalls()) {
    assistantMessage.getToolCalls().forEach(toolCall -> {
        String id = toolCall.id();
        String name = toolCall.name();
        String arguments = toolCall.arguments();
    });
}
```

## 5. ToolResponseMessage

工具执行完成后，必须创建与原工具调用对应的响应。

关键约束：

```text
AssistantMessage.ToolCall.id
             =
ToolResponseMessage.ToolResponse.id
```

工具调用 ID 不匹配会破坏模型的工具调用上下文。

## 6. 消息历史

直接使用 `ChatModel` 时，模型通常是无状态的，应用程序需要维护完整消息列表。

使用 `ReactAgent + Saver + threadId` 时，Agent 已经通过 Graph State 管理消息历史，不要再在业务层重复拼接同一份历史消息。

当前项目应明确区分：

* `ai_chat_memory`：业务侧聊天记录或审计记录。
* `Saver/Checkpointer`：ReactAgent 工作流运行状态。
* 前端聊天记录：展示层消息。

三者可以关联，但不能都作为模型上下文的重复来源，否则会造成：

* 历史消息重复。
* Token 数量快速增长。
* ToolCall 与 ToolResponse 顺序错乱。
* 上下文出现多份相同 SystemMessage。

## 7. 消息修改

消息对象支持 `copy()` 和 `mutate()`。

Hook 中修改消息时，应尽量生成新消息列表，不要在多个线程间共享并修改可变集合。

---

# Tools 工具

## 1. Tool 的本质

Tool 是模型与外部系统之间的结构化接口，可以：

* 查询数据库。
* 调用外部 API。
* 检索文件。
* 查询实时信息。
* 执行业务操作。
* 触发工作流。

模型不会直接访问数据库或 API。模型只会生成工具名称和参数，真正的执行由 Java 应用程序完成。这是 Tool Calling 的重要安全边界。

## 2. 创建工具的主要方式

### 方式一：`@Tool` 方法

```java
@Component
public class ProductTools {

    @Tool(description = "根据产品代码查询保险产品详情")
    public ProductDetail queryProduct(
            @ToolParam(description = "保险产品代码")
            String productCode) {
        return productService.query(productCode);
    }
}
```

适合：

* Spring Service 调用。
* 多个相关工具放在同一个类中。
* 工具需要访问类成员和依赖 Bean。
* 当前保险项目中的大多数业务工具。

### 方式二：`FunctionToolCallback`

```java
ToolCallback tool = FunctionToolCallback
    .builder("query_product", function)
    .description("根据产品条件查询保险产品")
    .inputType(ProductQuery.class)
    .build();
```

适合：

* 函数式、无状态工具。
* 动态创建工具。
* 简单输入输出。

函数工具对部分原始类型、集合、异步类型和响应式类型存在限制；复杂业务工具更适合使用方法式工具。

### 方式三：直接实现 `ToolCallback`

适合需要完全控制以下内容的场景：

* 工具定义。
* JSON Schema。
* 工具元数据。
* 输入解析。
* 结果序列化。
* ToolContext 处理。

## 3. Tool Schema

`ToolDefinition` 主要包含：

* `name`
* `description`
* `inputSchema`

工具描述和参数描述直接影响模型是否会正确选择工具。

建议：

* 工具名称使用稳定的英文动词短语。
* 描述中明确“何时使用”和“何时不要使用”。
* 参数描述明确格式、单位、枚举范围。
* 所有真正必填参数必须声明为必填。
* 可选参数明确使用 `@ToolParam(required = false)` 或 `@Nullable`。

错误地把必填参数声明为可选，可能促使模型编造缺失参数。

## 4. 工具结果

工具结果最终会转换为字符串发送给模型。

默认使用 Jackson 序列化，复杂结果可以实现：

```java
ToolCallResultConverter
```

工具返回值应：

* 数据结构稳定。
* 字段命名清晰。
* 避免返回无关字段。
* 避免返回超长数据库对象。
* 不返回敏感字段。
* 不把异常堆栈直接交给模型。

## 5. 工具执行控制

### 框架控制

默认由 Spring AI 自动执行工具，适合普通查询类工具。

### 用户控制

设置：

```java
.internalToolExecutionEnabled(false)
```

然后由应用程序读取 `ChatResponse` 中的 ToolCall，使用 `ToolCallingManager` 执行。

适合：

* 人工审批。
* 工具调用审计。
* 自定义重试。
* 多工具事务。
* 需要向前端展示待确认操作。
* 高风险数据库写入。

## 6. 异常处理

工具异常可以：

1. 转换成消息返回模型，让模型调整策略。
2. 向调用方抛出，直接终止当前执行。

配置：

```yaml
spring:
  ai:
    tools:
      throw-exception-on-error: false
```

生产项目需要按异常类型区分：

* 可重试异常：网络超时、临时限流。
* 不可重试异常：参数错误、无权限、业务校验失败。
* 系统异常：数据库不可用、序列化失败。
* 安全异常：越权访问、敏感操作。

不能对所有异常无差别重试。

## 7. ToolContext

工具可以通过 `ToolContext` 读取模型不可见的运行时信息：

* Graph State。
* RunnableConfig。
* 用户 ID。
* 会话 ID。
* 客户号。
* 权限信息。
* 持久化 Store。
* 当前 ToolCall ID。

```java
public class PolicyQueryTool
        implements BiFunction<PolicyQuery, ToolContext, String> {

    @Override
    public String apply(
            PolicyQuery input,
            ToolContext toolContext) {

        RunnableConfig config =
            (RunnableConfig) toolContext
                .getContext()
                .get("config");

        String customerId = config
            .metadata("customerId")
            .map(String::valueOf)
            .orElseThrow();

        return policyService.query(customerId, input);
    }
}
```

客户号、用户身份、机构号等可信参数，不应让模型从自然语言中自行生成，应通过 `RunnableConfig` 和 `ToolContext` 注入。

## 8. ReactAgent 工具提供方式

| 方式                         | 适用场景              |
| -------------------------- | ----------------- |
| `tools()`                  | 少量固定 ToolCallback |
| `methodTools()`            | 工具按业务类组织，当前项目优先使用 |
| `toolCallbackProviders()`  | 工具来自外部平台或运行时动态加载  |
| `toolNames() + resolver()` | 工具定义和 Agent 配置解耦  |
| `resolver()`               | 自定义工具注册中心         |
| 多种方式组合                     | 大型复杂 Agent        |

`toolNames()` 必须与 `resolver()` 配合，否则无法将名称解析成实际工具。

## 9. MCP 工具

Spring AI 可以通过 MCP Client 自动发现远程 MCP Server 提供的工具，再通过 `ToolCallbackProvider` 注入 `ReactAgent`。

使用前必须考虑：

* 网络访问策略。
* MCP Server 身份认证。
* 工具权限。
* 超时。
* 返回数据脱敏。
* 外部工具稳定性。
* 是否允许在生产环境自动执行。

不能因为 MCP 工具已经存在，就默认它是安全可信的。

## 10. 当前项目建议

产品分析 Agent 的工具可以按领域拆分：

```text
ProductQueryTools
ProductBenefitCalculationTools
ProductComparisonTools
PolicyQueryTools
CustomerAssetQueryTools
FaqRetrievalTools
```

每个工具只负责一个明确业务动作，Agent 负责决定调用顺序，Service 负责真正业务逻辑。

---

# Memory 短期记忆

## 1. 短期记忆机制

ReactAgent 的短期记忆是 Graph State 的一部分，默认通过 `messages` 保存对话历史。

配置 Saver 后，状态可以根据 `threadId` 保存和恢复：

```java
ReactAgent agent = ReactAgent.builder()
    .model(chatModel)
    .saver(new MemorySaver())
    .build();

RunnableConfig config = RunnableConfig.builder()
    .threadId(conversationId)
    .build();
```

不同 `threadId` 之间的会话相互隔离。

## 2. Saver 选择

### MemorySaver

特点：

* 存储在当前进程内。
* 重启后丢失。
* 适合测试和本地开发。
* 不适合多实例部署。

### RedisSaver 等持久化 Saver

特点：

* 支持跨请求恢复。
* 支持服务重启后恢复。
* 更适合生产环境。
* Human-in-the-Loop 场景通常必须使用持久化 Saver。

架构上要注意：Saver 持久化的是某个 `threadId` 的 Agent 状态，语义上仍属于会话级短期记忆。跨会话的客户画像、偏好和业务资料，应存储在独立业务库或长期记忆 Store 中。

## 3. 上下文过长

持续保存全部历史会导致：

* 超过模型上下文窗口。
* Token 成本持续增长。
* 响应时间增加。
* 旧内容干扰当前判断。
* 工具调用历史占用大量上下文。

官方提供的主要处理模式包括：

* 修剪消息。
* 删除消息。
* 总结历史。
* 自定义过滤策略。

## 4. 修剪消息

使用 `MessagesModelHook` 在 `BEFORE_MODEL` 阶段处理。

基本原则：

* 保留 SystemMessage。
* 保留最近若干轮完整对话。
* ToolCall 和对应 ToolResponse 必须成组保留。
* 不要只按消息数量盲目截断。
* 更推荐按估算 Token 数触发。

## 5. 总结消息

当历史达到阈值时：

1. 提取较早消息。
2. 使用总结模型生成摘要。
3. 用摘要替换旧消息。
4. 保留最近若干条原始消息。

总结模型可以使用成本较低的模型，但摘要必须保留：

* 用户已确认事实。
* 产品名称和产品代码。
* 客户身份信息引用。
* 当前任务状态。
* 未完成事项。
* 工具查询结论。
* 人工确认结果。

不要让摘要模型随意删除已经确认的关键业务事实。

## 6. 访问记忆

可以从以下位置读取状态：

* ToolContext。
* MessagesModelHook。
* ModelHook。
* ModelInterceptor。
* `agent.invoke()` 返回的 `OverAllState`。

其中：

* 只修改消息：优先 `MessagesModelHook`。
* 需要修改业务状态：使用 `ModelHook`。
* 需要动态修改模型请求：使用 `ModelInterceptor`。

## 7. 当前项目建议

当前项目已有自定义 `ai_chat_memory` 表时，应明确：

```text
ai_chat_memory
    负责业务聊天记录、审计和历史展示

ReactAgent Saver
    负责 Agent 执行状态和恢复

conversationId / threadId
    建议保持一致或建立明确映射
```

避免在一次请求中同时：

1. 从数据库加载完整历史并手工拼入消息。
2. 又让 ReactAgent Saver 自动加载同一份历史。

否则消息会重复。

自定义消息持久化必须完整保留：

* `message_order`
* `message_type`
* `text_content`
* `metadata`
* ToolCall ID
* Tool 名称
* Tool 参数
* ToolResponse ID
* Thinking 信息是否需要保存

---

# Hooks 和 Interceptors

## 1. 定位

Hooks 和 Interceptors 用于在 Agent 执行过程中实现：

* 日志监控。
* 消息修改。
* 动态提示。
* 工具筛选。
* 失败重试。
* 人工确认。
* PII 检测。
* 调用次数控制。
* 性能统计。
* 内容审核。

它们通过 `ReactAgent.builder().hooks(...)` 和 `.interceptors(...)` 注册。

## 2. 内置能力

主要内置实现包括：

### SummarizationHook

当消息达到 Token 阈值时生成摘要。

### HumanInTheLoopHook

在指定工具执行前暂停工作流，等待人工批准、编辑或拒绝。

必须配置 Saver，以便跨中断恢复状态。

### ModelCallLimitHook

限制一次 Agent 运行中的模型调用次数，防止无限循环和成本失控。

### PIIDetectionHook

检测并遮盖邮箱、电话等个人信息，适合金融场景。

### ToolRetryInterceptor

对临时失败的工具调用进行重试。

### TodoListInterceptor

在执行复杂任务前生成规划步骤。

### ToolSelectionInterceptor

工具数量较多时，先使用模型选择候选工具。

### ToolEmulatorInterceptor

开发或测试时模拟工具结果，不执行真实外部操作。

### ContextEditingInterceptor

模型调用前清理或修改上下文。

## 3. 自定义 Hook 类型

### MessagesModelHook

直接处理 `List<Message>`。

适合：

* 修剪。
* 过滤。
* 摘要。
* 追加系统提示。
* 替换消息。

通过 `AgentCommand` 返回，并指定：

* `UpdatePolicy.REPLACE`
* `UpdatePolicy.APPEND`

也可以通过 `JumpTo.end` 提前终止。

### ModelHook

可以访问完整 `OverAllState`。

适合：

* 修改自定义状态。
* 维护计数器。
* 保存中间结果。
* 根据全局状态改变流程。
* 同时修改消息和业务状态。

### AgentHook

在一次 Agent 调用的开始和结束运行。

适合：

* 初始化资源。
* 记录总耗时。
* 写入执行日志。
* 清理临时数据。

### ModelInterceptor

包裹模型调用。

适合：

* 修改 ModelRequest。
* 修改 ModelResponse。
* 动态 System Prompt。
* 动态增加或过滤工具。
* 模型调用审计。
* 输入和输出审核。

### ToolInterceptor

包裹工具执行。

适合：

* 工具权限校验。
* 重试。
* 熔断。
* 缓存。
* 耗时统计。
* 工具结果脱敏。

## 4. 选择原则

| 需求              | 推荐实现                |
| --------------- | ------------------- |
| 修改消息列表          | `MessagesModelHook` |
| 访问完整 Agent 状态   | `ModelHook`         |
| Agent 整体开始、结束处理 | `AgentHook`         |
| 修改模型请求或响应       | `ModelInterceptor`  |
| 修改、监控工具调用       | `ToolInterceptor`   |
| 暂停并等待人工操作       | Hook                |
| 普通日志、重试、缓存      | Interceptor         |

Hook 支持中断和恢复，Interceptor 主要负责包裹、修改和监控调用。

## 5. RunnableConfig.context

同一次 Agent 执行中的 Hook 可以通过 `RunnableConfig.context()` 共享临时数据，例如：

* 模型调用次数。
* 工具调用次数。
* 开始时间。
* 累计耗时。
* 重试次数。
* 临时错误信息。

建议内部 Key 使用双下划线：

```java
"__model_call_count__"
"__tool_retry_count__"
"__start_time__"
```

不要把需要跨请求持久化的数据仅放在 `context()` 中。

## 6. 执行顺序

多个 Hook 和 Interceptor 的执行顺序：

```text
Before Agent Hooks：正序

循环开始
    Before Model Hooks：正序
    Model Interceptors：嵌套执行
    模型调用
    After Model Hooks：逆序

    Tool Interceptors：嵌套执行
    工具调用

循环结束

After Agent Hooks：逆序
```

第一个注册的 Interceptor 位于调用链最外层。

## 7. 当前项目推荐链路

保险产品管理智能体可以采用：

```text
InputGuardrailInterceptor
        ↓
ContextAlignmentHook
        ↓
SummarizationHook
        ↓
ModelCallLimitHook
        ↓
ReactAgent 模型调用
        ↓
ToolPermissionInterceptor
        ↓
ToolRetryInterceptor
        ↓
ToolMonitoringInterceptor
        ↓
OutputReviewInterceptor
```

涉及数据库修改、消息推送、客户经理提醒等工具时，再增加：

```text
HumanInTheLoopHook
```

---

# Skills 技能

## 1. Skill 的定位

Skill 是可复用的任务说明和上下文包。

与 Tool 的区别：

* Tool：执行一个具体操作。
* Skill：告诉模型如何完成某类任务。
* Skill 可以说明需要按什么顺序调用哪些 Tool。
* Skill 本身不等于 Java Service，也不等于 Tool。

Spring AI Alibaba 使用渐进式披露机制：

1. 系统提示只注入技能名称、描述和路径。
2. 模型判断需要某个技能。
3. 模型调用 `read_skill(skill_name)`。
4. 加载完整 `SKILL.md`。
5. 按技能说明调用相关工具或读取资源。

## 2. Skill 目录

```text
skills/
└── product-analysis/
    ├── SKILL.md
    ├── references/
    ├── examples/
    └── scripts/
```

只有 `SKILL.md` 是必需的。

推荐：

* `references/`：业务规则、字段解释、长文档。
* `examples/`：输入输出样例。
* `scripts/`：辅助脚本。
* 不要把所有内容全部塞进 `SKILL.md`。

## 3. SKILL.md

```markdown
---
name: product-analysis
description: 用于对一个或多个保险产品进行收益、保障和适用人群分析。
---

# 产品分析技能

## 适用场景

## 输入要求

## 执行步骤

## 可用工具

## 输出要求

## 禁止事项
```

关键要求：

* `name` 使用小写字母、数字和连字符。
* `name` 必须与 `read_skill` 参数一致。
* `description` 要能帮助模型准确判断何时加载。
* 一个 SKILL.md 建议控制在约 1.5k～2k Tokens。
* 大段资料放入 `references/`。

## 4. SkillRegistry

### FileSystemSkillRegistry

适合：

* 技能外置部署。
* 不重新打包即可更新。
* 运营人员维护 Skill。
* 多环境挂载不同技能目录。

项目级技能会覆盖同名用户级技能。

### ClasspathSkillRegistry

适合：

* Skill 随项目 JAR 发布。
* 版本需要与代码保持一致。
* 行内系统统一发布。
* 不允许运行时随意修改。

当前保险项目更适合优先使用：

```java
ClasspathSkillRegistry.builder()
    .classpathPath("skills")
    .build();
```

开发阶段需要热更新时，可再考虑文件系统方式。

## 5. SkillsAgentHook

```java
SkillRegistry registry = ClasspathSkillRegistry.builder()
    .classpathPath("skills")
    .build();

SkillsAgentHook skillsHook = SkillsAgentHook.builder()
    .skillRegistry(registry)
    .build();

ReactAgent agent = ReactAgent.builder()
    .name("product-analysis-agent")
    .model(chatModel)
    .hooks(skillsHook)
    .build();
```

`SkillsAgentHook` 同时完成：

* 注入技能列表。
* 注册 `read_skill` 工具。
* 加载技能内容。
* 管理技能激活状态。

## 6. Skill 与 Tool 绑定

通过 `groupedTools` 可以让某些工具只在 Skill 激活后暴露：

```java
Map<String, List<ToolCallback>> groupedTools = Map.of(
    "product-analysis",
    List.of(
        productQueryTool,
        benefitCalculationTool,
        comparisonTool
    )
);

SkillsAgentHook hook = SkillsAgentHook.builder()
    .skillRegistry(registry)
    .groupedTools(groupedTools)
    .build();
```

适合工具数量很多的 Agent，可以降低：

* 系统提示长度。
* 模型选错工具的概率。
* 无关工具干扰。
* Tool Schema Token 消耗。

Skill 名称、`groupedTools` 的 Key 和 SKILL.md Front Matter 中的 `name` 必须一致。

## 7. 自动重载

```java
SkillsAgentHook.builder()
    .skillRegistry(registry)
    .autoReload(true)
    .build();
```

自动重载只应在 Registry 支持 `reload()` 时启用。

生产环境建议：

* 固定发布的 Skill 不开启自动重载。
* 外置运营 Skill 才开启。
* Skill 更新应有版本、审核和回滚机制。
* 金融业务规则不能未经审核直接生效。

## 8. Graph 中使用 Skill

`SkillPromptAugmentAdvisor` 只负责把技能列表和加载说明注入 `ChatClient` 的系统提示。

它不会自动注册 `read_skill` 工具。

因此：

* `ReactAgent`：优先使用 `SkillsAgentHook`。
* 普通 `ChatClient`：使用 `SkillPromptAugmentAdvisor` 时，还需要自行提供 `read_skill`。
* Graph 节点内部如果使用 ReactAgent，直接挂载 `SkillsAgentHook`。
* Graph 节点内部如果只使用 ChatClient，才考虑 Advisor 方式。

## 9. 当前项目 Skill 规划

建议至少建立：

```text
skills/
├── finite-product-analysis/
│   └── SKILL.md
├── batch-product-analysis/
│   └── SKILL.md
├── insurance-knowledge-qa/
│   └── SKILL.md
├── policy-query/
│   └── SKILL.md
└── customer-asset-query/
    └── SKILL.md
```

其中：

* 明确产品名称或代码：加载 `finite-product-analysis`。
* 只给产品属性或批量筛选条件：加载 `batch-product-analysis`。
* Skill 描述业务流程，Tool 执行真实查询和计算。
* 不要在 Skill 中复制大量 Java 代码实现细节。

---

# Structured Output 结构化输出

## 1. 定位

结构化输出用于让 Agent 返回可预测的数据结构，而不是只能返回自然语言。

ReactAgent 支持：

```java
.outputType(OutputClass.class)
```

或者：

```java
.outputSchema(jsonSchema)
```

最终结构化内容仍会以 JSON 文本形式出现在 `AssistantMessage` 中，同时可以从 `OverAllState` 的 `structured_output` 字段读取。

## 2. outputType

推荐方式：

```java
ReactAgent agent = ReactAgent.builder()
    .name("intent-agent")
    .model(chatModel)
    .outputType(IntentRecognitionResult.class)
    .build();
```

优点：

* Java 类型安全。
* 自动生成 Schema。
* DTO 和输出定义保持一致。
* 便于 Jackson 反序列化。
* 易于重构。

DTO 需要标准 Getter、Setter，或者使用当前版本确认支持的 Record 形式。

## 3. outputSchema

```java
BeanOutputConverter<IntentRecognitionResult> converter =
    new BeanOutputConverter<>(IntentRecognitionResult.class);

ReactAgent agent = ReactAgent.builder()
    .outputSchema(converter.getFormat())
    .build();
```

适合：

* 需要动态 Schema。
* Schema 由外部配置提供。
* 需要对 JSON Schema 进行精确控制。
* 输出结构无法直接映射固定 Java 类型。

固定结构优先使用 `outputType`。

## 4. 工作原理

框架会根据模型能力选择实现：

1. 模型支持原生结构化输出时，使用模型原生能力。
2. 模型不支持时，通过动态 ToolCall 约束输出。
3. 同时在 Prompt 中增加格式说明。

即使配置了 `outputType`，也不能认为输出一定永远合法。

不同模型的保证程度不同：

* 某些模型在 API 层面严格约束 JSON。
* 某些模型主要通过 Prompt 尽力生成 JSON。
* 不支持原生结构化输出的模型可能依赖 ToolCall 模拟。

## 5. 错误处理

生产代码必须包含：

### JSON 解析异常处理

```java
try {
    OutputDto output =
        objectMapper.readValue(
            assistantMessage.getText(),
            OutputDto.class);
} catch (JsonProcessingException exception) {
    // 记录原始响应并进入降级逻辑
}
```

### 业务字段校验

例如：

* 意图列表不能为空。
* 产品代码必须符合格式。
* `needsProductCall` 不能为空。
* 置信度范围为 0～1。
* 产品数量不能为负。
* 枚举值必须在允许范围内。

### 有限重试

格式错误时可以重新调用，但必须限制重试次数，例如最多 1～2 次。

不要无限重试结构化输出。

## 6. 当前项目适用位置

适合配置结构化输出的节点：

* 用户问题改写。
* 意图识别。
* 产品召回判断。
* 产品候选列表生成。
* Agent 执行计划。
* 输出审核。
* 子 Agent 运行结果。
* Summary 所需中间数据。

例如意图识别结果：

```java
public class IntentRecognitionResult {

    private String userQuery;
    private String rewriteQuery;
    private List<String> intention;
    private List<String> dependOn;
    private Map<String, String> intentionQueries;
    private String keyStatement;
    private Boolean needsProductCall;
    private String reason;

    // Getter / Setter
}
```

建议不要让同一次模型响应同时返回：

```text
一大段自然语言说明
+
一个 JSON 代码块
```

应直接返回纯结构化数据，解释文本放入 DTO 的 `reason`、`summary` 等字段。

---

# 面向当前项目的统一落地建议

## 1. 推荐职责边界

```text
Graph
    负责工作流、节点路由、依赖、并行、失败处理和人工中断

ReactAgent
    负责节点内部的 ReAct 推理和工具选择

Skill
    负责说明某类任务应该如何完成

Tool
    负责真实查询、计算和业务操作

Hook
    负责状态、消息、生命周期和中断控制

Interceptor
    负责模型调用与工具调用的拦截、监控、重试和审核

Saver
    负责按 threadId 保存 Agent 状态

Structured Output
    负责节点间稳定的数据契约
```

## 2. 建议包结构

```text
com.xxx.insurance.ai
├── config
│   ├── AiModelConfiguration
│   ├── AgentConfiguration
│   ├── SkillConfiguration
│   └── MemoryConfiguration
├── agent
│   ├── ProductAnalysisAgent
│   ├── InsuranceKnowledgeAgent
│   ├── PolicyQueryAgent
│   ├── CustomerAssetAgent
│   └── SummaryAgent
├── workflow
│   ├── state
│   ├── node
│   ├── router
│   └── graph
├── tool
│   ├── product
│   ├── policy
│   ├── asset
│   └── knowledge
├── hook
├── interceptor
├── memory
├── skill
├── dto
└── service
```

## 3. Codex 开发检查清单

Codex 每次实现新 Agent 或节点时，应检查：

* [ ] 是否复用了现有 ChatModel Bean。
* [ ] Agent 名称和职责是否唯一清晰。
* [ ] Tool 是否只做一个明确业务动作。
* [ ] Tool 参数是否有准确 Schema 和描述。
* [ ] 可信业务参数是否通过 RunnableConfig/ToolContext 传递。
* [ ] 是否限制模型调用次数。
* [ ] 是否配置工具异常处理。
* [ ] 高风险工具是否需要 Human-in-the-Loop。
* [ ] threadId 是否正确使用 conversationId。
* [ ] 是否重复加载了聊天历史。
* [ ] 长对话是否配置修剪或总结。
* [ ] Skill 名称是否与 SKILL.md、groupedTools 一致。
* [ ] 是否使用 outputType 定义节点间数据契约。
* [ ] 是否对结构化输出进行解析、校验和有限重试。
* [ ] 流式输出是否按 OutputType 过滤。
* [ ] Thinking、ToolCall、ToolResult 和最终文本是否分别处理。
* [ ] 是否记录模型耗时、工具耗时和失败原因。
* [ ] 实际代码是否与项目使用的 `1.1.2.3` 源码 API 一致。
