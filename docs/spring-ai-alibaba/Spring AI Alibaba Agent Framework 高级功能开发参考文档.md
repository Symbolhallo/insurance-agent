# Spring AI Alibaba Agent Framework 高级功能开发参考文档

## 文档用途

本文用于指导 Codex 在现有项目中设计和实现：

* 上下文工程
* 人工审批和工作流中断
* 短期记忆与长期记忆
* 多智能体协作
* Agent Tool
* Graph 工作流
* RAG
* A2A 分布式智能体

Codex 在开发前必须先检查：

1. `build.gradle` 中的实际依赖版本。
2. 当前项目已有的 `ReactAgent`、Tool、Skill、Hook、Interceptor、Saver 和 Graph 实现。
3. 本地 Gradle 缓存中的 `spring-ai-alibaba-agent-framework-1.1.2.3-sources.jar`。
4. 官网示例 API 是否与当前版本一致。
5. 当前工作流是否已经有统一的 `conversationId`、`threadId` 和状态字段定义。

---

# 上下文工程（Context Engineering）

## 1. 核心定位

上下文工程不是单纯编写 Prompt，而是控制 Agent 每一次模型调用时：

* 模型能看到什么信息。
* 模型能够调用哪些工具。
* 工具能够读取和修改什么状态。
* 模型调用和工具调用之间执行什么处理。
* 哪些信息只影响当前调用，哪些信息需要长期保存。

Agent 失败通常有两个原因：

1. 模型本身能力不足。
2. 模型没有获得正确的上下文。

生产级 Agent 的可靠性，很大程度取决于第二点。

## 2. Agent 循环

典型 ReactAgent 循环：

```text
模型调用
    ↓
模型返回最终回答或 ToolCall
    ↓
执行工具
    ↓
将 ToolResponse 返回模型
    ↓
模型继续判断
    ↓
完成或继续调用工具
```

上下文工程需要控制循环中的每个阶段，而不是只控制第一次模型调用。

## 3. 三类上下文

### 3.1 模型上下文

模型在单次调用中看到的内容：

* System Prompt
* 消息历史
* 可用工具
* 使用的模型及模型参数
* 结构化响应格式

模型上下文通常属于**瞬态上下文**。通过 `ModelInterceptor` 修改请求，只影响当前模型调用，不会自动修改已经持久化的 Agent State。

### 3.2 工具上下文

工具执行时可以读取或写入：

* `OverAllState`
* `RunnableConfig`
* 用户身份
* 客户号
* 机构号
* 权限信息
* Store
* 临时状态
* 工具调用信息

工具上下文可以产生持久状态变化。

### 3.3 生命周期上下文

模型调用和工具调用之间的处理：

* 消息摘要
* 上下文裁剪
* 输入审核
* 输出审核
* PII 脱敏
* 日志记录
* 模型调用次数限制
* Tool 重试
* Human-in-the-Loop

主要通过 Hook 和 Interceptor 实现。

## 4. 三类数据源

### Runtime Context

会话运行期间基本不变的可信配置：

```text
userId
customerId
organizationId
conversationId
权限信息
数据库连接
环境配置
```

建议通过 `RunnableConfig.metadata` 或框架支持的运行时上下文传递。

### State

当前工作流实例中的短期状态：

```text
messages
rewriteQuery
intentions
confirmedProducts
agentResults
errors
summary
```

State 在节点和 Agent 之间传递。

### Store

跨会话保存的长期信息：

```text
用户偏好
用户画像
历史确认信息
长期业务事实
提取出的知识
```

Runtime Context、State 和 Store 的作用范围不同，不应混用。

## 5. 动态 System Prompt

动态 Prompt 适合根据以下信息调整模型指令：

* 用户角色。
* 当前工作流阶段。
* 已确认产品。
* 用户偏好。
* 对话长度。
* 业务权限。
* 当前失败情况。

推荐使用 `ModelInterceptor` 临时增强模型请求：

```java
public final class BusinessContextInterceptor extends ModelInterceptor {

    @Override
    public ModelResponse interceptModel(
            ModelRequest request,
            ModelCallHandler handler) {

        String businessContext = buildBusinessContext(request);

        SystemMessage systemMessage = mergeSystemMessage(
                request.getSystemMessage(),
                businessContext);

        ModelRequest enhancedRequest = ModelRequest.builder(request)
                .systemMessage(systemMessage)
                .build();

        return handler.call(enhancedRequest);
    }

    @Override
    public String getName() {
        return "business_context_interceptor";
    }
}
```

不要在每一次调用中无条件重复追加相同内容，否则 System Prompt 会持续膨胀。

## 6. 消息过滤

使用 `ModelInterceptor` 可以临时过滤本次发给模型的消息：

* 只保留最近 N 轮。
* 删除无关消息。
* 移除冗长 ToolResponse。
* 注入对话摘要。
* 只保留当前 Agent 所需上下文。

这种过滤默认不会修改持久 State；需要永久替换历史消息时，应使用 `MessagesModelHook` 或 `ModelHook`。

过滤时必须保证：

```text
AssistantMessage.ToolCall
        和
ToolResponseMessage.ToolResponse
```

成对保留，不能只删除其中一侧。

## 7. 动态工具控制

可以根据用户权限、当前意图和工作流阶段，只向模型暴露当前可使用的工具。

例如：

```text
普通用户：
- 查询产品
- 查询知识

客户经理：
- 查询产品
- 查询客户保单
- 查询客户资产

管理员：
- 查询
- 修改
- 删除
```

但不能仅依赖模型决定权限。正确设计应为：

```text
工具暴露过滤
    +
工具执行前权限校验
```

即使模型错误地产生了某个 ToolCall，工具执行层也必须再次验证权限。

## 8. 模型动态选择

根据任务类型选择模型：

```text
问题改写             → 低成本模型
简单意图识别         → 低成本、低温度模型
复杂产品分析         → 强推理模型
结构化结果生成       → JSON 能力较好的模型
总结                 → 长上下文、成本较低模型
输出审核             → 规则遵循能力强的模型
```

官网动态模型示例主要展示设计思想，并未真正完成模型替换；Codex 不应直接复制该概念代码。

当前项目如果需要动态模型，优先建立统一的模型路由组件，而不是在 Interceptor 中临时创建 `ChatModel`。

## 9. 工具读取和修改 State

工具可通过 `ToolContext` 读取：

```java
OverAllState state =
        (OverAllState) toolContext.getContext().get("state");

RunnableConfig config =
        (RunnableConfig) toolContext.getContext().get("config");
```

工具需要修改后续 Agent 可见的 State 时，可以通过框架提供的扩展状态机制写入。官网示例使用 `extraState` 表示工具执行后需要持久化到 State 的数据。

推荐统一定义状态 Key：

```java
public final class InsuranceStateKeys {

    public static final String REWRITE_QUERY = "rewriteQuery";
    public static final String INTENTIONS = "intentions";
    public static final String CONFIRMED_PRODUCTS = "confirmedProducts";
    public static final String PRODUCT_ANALYSIS_RESULT =
            "productAnalysisResult";

    private InsuranceStateKeys() {
    }
}
```

禁止在不同节点中随意使用字符串常量。

## 10. Codex 实现要求

上下文工程必须遵循：

```text
短期临时修改
    → ModelInterceptor

永久修改消息或状态
    → Hook

真实业务查询或操作
    → Tool

跨节点数据
    → State

跨会话长期数据
    → Store

可信用户和权限信息
    → RunnableConfig / ToolContext
```

---

# 人工介入（Human-in-the-Loop）

## 1. 核心定位

Human-in-the-Loop 用于在高风险 ToolCall 执行前暂停 Agent，等待人工决定。

典型场景：

* 执行 SQL。
* 修改数据库。
* 写入文件。
* 发送通知。
* 提交审批。
* 修改保单。
* 向客户经理发送提醒。
* 调用可能产生业务影响的外部接口。

HITL 依赖检查点机制保存中断状态，使 Agent 可以暂停并在之后恢复。

## 2. 三种决策

### approve

原样执行模型生成的 ToolCall。

### edit

人工修改 ToolCall 参数后执行。

适用于：

* 修改接收人。
* 修改发送内容。
* 修改 SQL 条件。
* 调整产品列表。

### reject

拒绝执行，并把拒绝原因返回 Agent，让模型重新规划或生成回复。

## 3. Hook 配置

基本结构：

```java
HumanInTheLoopHook hook = HumanInTheLoopHook.builder()
        .approvalOn(
                "send_customer_manager_notification",
                ToolConfig.builder()
                        .description("发送提醒前必须由人工确认")
                        .build())
        .approvalOn(
                "update_policy_status",
                ToolConfig.builder()
                        .description("修改保单状态前必须审批")
                        .build())
        .build();

ReactAgent agent = ReactAgent.builder()
        .name("policy_expiration_agent")
        .model(chatModel)
        .tools(notificationTool, updatePolicyTool, queryPolicyTool)
        .hooks(hook)
        .saver(checkpointSaver)
        .build();
```

只对需要审批的写操作配置 `approvalOn`。普通只读查询不应无差别增加审批，否则会严重降低工作流效率。

## 4. 中断处理流程

```text
第一次调用 Agent
    ↓
模型产生需要审批的 ToolCall
    ↓
HumanInTheLoopHook 产生 interrupt
    ↓
检查点保存状态
    ↓
前端展示待审批工具、参数和描述
    ↓
用户 approve / edit / reject
    ↓
构造 InterruptionMetadata
    ↓
使用相同 threadId 恢复
    ↓
继续执行工具和后续 Agent
```

恢复时需要把人工反馈放入 `RunnableConfig`。单独 Agent 一般通过 `agent.invokeAndGetOutput()` 恢复。

## 5. Workflow 中的人工中断

当 `ReactAgent` 作为 `StateGraph` 节点时：

* 中断发生在工作流中的 Agent 节点。
* 检查点需要在 Graph 编译配置中注册。
* 恢复执行时调用 `CompiledGraph`。
* 恢复时必须继续使用原 `threadId`。
* 通常传入空状态，因为原状态已由 Checkpointer 保存。

工作流与嵌套 Agent 应共享兼容的检查点保存器，避免状态不一致。

## 6. 前端数据结构建议

```java
public record HumanApprovalRequest(
        String conversationId,
        String workflowNode,
        List<PendingToolCall> toolCalls) {
}

public record PendingToolCall(
        String toolCallId,
        String toolName,
        String description,
        Map<String, Object> arguments,
        List<String> allowedActions) {
}
```

人工反馈：

```java
public record HumanApprovalDecision(
        String toolCallId,
        DecisionType decision,
        Map<String, Object> editedArguments,
        String reason) {
}

public enum DecisionType {
    APPROVE,
    EDIT,
    REJECT
}
```

## 7. 当前项目建议

保险产品召回确认属于业务确认，可以使用 Graph 的人工确认节点。

真正存在副作用的操作，例如：

```text
写数据库
发送通知
修改客户经理任务
生成并提交审批单
```

应使用 `HumanInTheLoopHook`。

两种确认不要混成一个概念：

```text
业务候选确认
    → 确认模型识别结果是否正确

高风险 Tool 审批
    → 确认是否允许执行副作用操作
```

---

# 记忆管理（Memory）

## 1. 短期记忆与长期记忆

Spring AI Alibaba 将记忆划分为：

### 短期记忆

通常由 Saver 管理，并通过 `threadId` 隔离。

保存：

* 当前对话历史。
* 当前 Graph State。
* 工具调用结果。
* Agent 当前执行位置。
* 人工中断状态。

### 长期记忆

由 Store 管理，通过 `namespace + key` 组织，可以跨不同 `threadId` 使用。

保存：

* 用户偏好。
* 用户画像。
* 历史确认事实。
* 跨会话业务信息。
* 可复用的长期结论。

短期记忆服务于当前会话；长期记忆服务于同一用户的多个会话。

## 2. 长期记忆数据模型

Store 中的典型结构：

```text
namespace:
    ["insurance-agent", userId, "preferences"]

key:
    "communication-style"

value:
    {
        "language": "zh-CN",
        "answerStyle": "detailed",
        "updatedAt": "..."
    }
```

推荐 Namespace 包含：

```text
应用名
租户 ID
用户 ID
数据类型
```

例如：

```java
List<String> namespace = List.of(
        "insurance-agent",
        tenantId,
        userId,
        "confirmed-products");
```

不要把所有用户信息放在同一个扁平命名空间中。

## 3. 通过 RunnableConfig 注入 Store

```java
RunnableConfig config = RunnableConfig.builder()
        .threadId(conversationId)
        .addMetadata("userId", userId)
        .addMetadata("customerId", customerId)
        .store(memoryStore)
        .build();
```

Tool 或 Hook 可以从 `RunnableConfig` 获取 Store。

## 4. 工具显式读写长期记忆

适用于用户明确要求：

* “记住这个产品。”
* “记住我偏好看保证收益。”
* “以后回答得详细一点。”
* “删除之前保存的偏好。”

可以提供：

```text
save_user_preference
get_user_preference
delete_user_preference
save_confirmed_product
get_confirmed_products
```

Tool 参数中的 `userId` 不应由模型提供，应从 `RunnableConfig` 读取，避免越权访问其他用户记忆。

## 5. Hook 自动加载记忆

`BEFORE_MODEL`：

1. 从 metadata 获取当前用户。
2. 从 Store 加载相关记忆。
3. 选择真正与本轮任务相关的内容。
4. 注入模型上下文。

`AFTER_MODEL`：

1. 判断是否产生值得长期保存的新事实。
2. 提取结构化记忆。
3. 校验和去重。
4. 写入 Store。

官网示例展示了通过 `ModelHook` 在调用前加载用户画像，并把画像合并到模型消息中。

## 6. 不要自动保存全部对话

长期记忆不等于聊天记录归档。

禁止把以下内容无差别写入长期记忆：

* 每条用户消息。
* 每个模型回答。
* 临时问题。
* 未确认的推测。
* 模型生成的客户事实。
* 敏感凭证。
* 密码、Token 和 API Key。

官网跨会话示例中使用密码演示存储机制，该内容只应视为 API 示例，生产系统绝不能按照这种方式存储明文凭证。

## 7. 用户偏好学习

自动偏好学习应采用结构化提取，而不是简单匹配“喜欢”“偏好”等关键词。

推荐输出：

```java
public record PreferenceExtractionResult(
        boolean shouldSave,
        String preferenceType,
        String preferenceValue,
        double confidence,
        String evidence) {
}
```

只有满足以下条件才保存：

```text
用户明确表达
+
置信度达到阈值
+
不属于敏感信息
+
与已有偏好不冲突
```

## 8. 当前项目三层存储建议

```text
ai_chat_memory
    保存原始聊天记录、审计和前端展示

Graph Checkpointer / Saver
    保存工作流 State、执行位置和中断状态

Long-term Store
    保存跨会话用户偏好和已确认业务事实
```

不要将三种存储都作为模型历史重复加载。

---

# 多智能体（Multi-agent）

## 1. 使用条件

以下情况适合拆分 Multi-agent：

* 单个 Agent 挂载的工具过多。
* 单个 Prompt 包含多个不同职责。
* 不同任务需要不同模型。
* 不同任务需要不同权限。
* 上下文过长。
* 存在明确的专业化角色。
* 某些任务需要并行执行。

Spring AI Alibaba 将 Multi-agent 主要分为集中式 Tool Calling 和交接式 Handoffs，也支持顺序、并行、路由、监督者和自定义 FlowAgent。

## 2. Agent Tool 与 Handoffs

### Agent Tool

主 Agent 保持控制权，把子 Agent 当作工具调用。

适合：

* 主 Agent 统一回答用户。
* 子 Agent 只执行专业任务。
* 需要集中编排。
* 子 Agent 不需要直接与用户连续对话。

### Handoffs

当前 Agent 将控制权移交另一个 Agent，新的 Agent 成为当前活动 Agent。

适合：

* 不同专家分别与用户对话。
* 用户需要继续向某个专家追问。
* 对话焦点会在专家之间切换。

当前保险产品管理智能体以集中式业务编排为主，优先采用 Agent Tool、FlowAgent 或 Graph，不必首先使用 Handoffs。

## 3. 上下文传递

多 Agent 的核心不只是“拆成几个 Agent”，而是控制每个 Agent 能看到什么。

应明确：

* 是否传递原始问题。
* 是否传递改写问题。
* 是否传递完整聊天历史。
* 是否传递前序 Agent 输出。
* 是否传递工具调用历史。
* 是否传递中间推理。
* 子 Agent 最终返回什么结构。

官网指出，Agent 间上下文质量直接影响系统效果。

## 4. Instruction 占位符

常见占位符：

```text
{input}
{outputKey}
{stateKey}
```

示例：

```java
ReactAgent summaryAgent = ReactAgent.builder()
        .name("summary_agent")
        .model(chatModel)
        .instruction("""
            请基于以下信息生成最终回答：

            产品分析：
            {productAnalysisResult}

            知识问答：
            {knowledgeResult}

            保单信息：
            {policyResult}

            资产信息：
            {assetResult}
            """)
        .outputKey("finalAnswer")
        .build();
```

前序 Agent 必须配置稳定且唯一的 `outputKey`。

## 5. SequentialAgent

按 `subAgents` 顺序执行：

```text
Agent A
    ↓
Agent B
    ↓
Agent C
```

特点：

* 输出通过 State 传递。
* 后续 Agent 可通过占位符引用前序结果。
* 默认可能共享消息历史。
* 可以控制是否包含中间推理内容。

适合：

```text
查询改写
    → 检索
    → 分析
    → 审核
    → 总结
```

不适合存在复杂条件、人工中断和动态并行的流程，这类流程更适合 Graph。

## 6. ParallelAgent

多个子 Agent 并行处理同一输入，再合并结果：

```text
              ┌→ Agent A ─┐
输入 ─────────┼→ Agent B ─┼→ 合并
              └→ Agent C ─┘
```

需要配置：

* `subAgents`
* 每个 Agent 的 `outputKey`
* `mergeOutputKey`
* 默认或自定义 `MergeStrategy`

自定义合并策略应处理：

* 输出缺失。
* 某个 Agent 失败。
* 类型不一致。
* 并发写同一个 State Key。
* 输出顺序不稳定。

官网支持通过自定义 `MergeStrategy` 聚合并行结果。

## 7. LlmRoutingAgent

通过 LLM 在多个子 Agent 中选择一个执行。

特点：

* 一般只进行一次路由。
* 被选中的 Agent 处理请求。
* 子 Agent 结束后流程完成。
* 适合互斥意图。

路由质量依赖：

* 子 Agent `name`。
* 子 Agent `description`。
* Routing Agent 的 `systemPrompt`。
* Routing Agent 的 `instruction`。
* 是否提供清晰边界和反例。

`systemPrompt` 用于定义路由角色和决策规则；`instruction` 用于补充当前任务的路由上下文。

## 8. SupervisorAgent

SupervisorAgent 支持循环路由：

```text
Supervisor
    ↓
Agent A
    ↓
Supervisor
    ↓
Agent B
    ↓
Supervisor
    ↓
FINISH
```

与 `LlmRoutingAgent` 的区别：

| 对比项         | LlmRoutingAgent | SupervisorAgent |
| ----------- | --------------- | --------------- |
| 路由次数        | 单次              | 多步骤循环           |
| 子 Agent 完成后 | 直接结束            | 返回 Supervisor   |
| 复杂任务        | 较弱              | 较强              |
| 执行路径        | 相对简单            | 模型动态决定          |
| 可预测性        | 较高              | 较低              |

适用于任务步骤无法预先完全确定的场景。

当前保险项目已有较明确的业务流程和依赖关系，应优先采用 Graph 明确编排，而不是把全部路径交给 SupervisorAgent。

## 9. 自定义 FlowAgent

`FlowAgent` 是 `SequentialAgent`、`ParallelAgent`、`LlmRoutingAgent` 等流程型 Agent 的基础抽象。

需要自定义一种通用多 Agent 模式时，可以继承 FlowAgent 并实现图构建逻辑。

仅当该模式会被多个场景复用时才应自定义 FlowAgent。单个保险工作流直接使用 StateGraph 更清晰。

---

# 智能体作为工具（Agent Tool）

## 1. 核心模式

Agent Tool 将子 Agent 转换为主 Agent 可调用的 ToolCallback。

```java
ReactAgent coordinator = ReactAgent.builder()
        .name("insurance_coordinator")
        .model(chatModel)
        .tools(
            AgentTool.getFunctionToolCallback(productAnalysisAgent),
            AgentTool.getFunctionToolCallback(knowledgeAgent),
            AgentTool.getFunctionToolCallback(policyAgent),
            AgentTool.getFunctionToolCallback(assetAgent))
        .build();
```

主 Agent 负责：

* 理解用户需求。
* 决定调用哪个子 Agent。
* 决定调用顺序。
* 汇总子 Agent 返回结果。

子 Agent 只负责自己的专业任务。

## 2. 子 Agent 描述

子 Agent 的 `description` 会成为主 Agent 选择工具的重要依据。

错误示例：

```text
处理保险问题
```

推荐：

```text
根据已确认的保险产品代码，查询产品责任、收益演示和条款信息，
完成单产品分析或多产品比较。
不负责查询客户名下保单和资产信息。
```

描述应包含：

* 能做什么。
* 需要什么输入。
* 不能做什么。
* 何时不应调用。
* 返回什么结果。

## 3. 控制输入

Agent Tool 支持通过 `inputSchema` 或 `inputType` 定义子 Agent 输入。

推荐使用类型：

```java
public record ProductAnalysisRequest(
        String rewrittenQuery,
        List<String> confirmedProductCodes,
        AnalysisMode mode,
        List<String> dimensions) {
}
```

```java
ReactAgent productAnalysisAgent = ReactAgent.builder()
        .name("product_analysis_agent")
        .model(chatModel)
        .inputType(ProductAnalysisRequest.class)
        .instruction("""
            根据输入的产品代码和分析维度完成产品分析。
            不得自行编造产品代码。
            """)
        .build();
```

结构化输入比把所有上下文拼成一个字符串更稳定。

## 4. 控制输出

主 Agent 只能稳定使用子 Agent 的最终输出，因此子 Agent 必须在最终消息中包含所有关键结果。

推荐使用：

```java
public record ProductAnalysisResult(
        boolean success,
        List<ProductConclusion> products,
        String comparison,
        List<String> missingInformation,
        List<String> warnings) {
}
```

```java
.outputType(ProductAnalysisResult.class)
```

官网支持通过 `outputSchema` 或 `outputType` 约束子 Agent 返回内容。

## 5. Agent Tool 的限制

Agent Tool 的路由和执行仍由 LLM 决定，因此：

* 执行路径不完全确定。
* 依赖关系不容易严格保证。
* 多个 Agent 并行不如 Graph 直观。
* 失败跳过策略不容易精确控制。
* 人工确认流程更复杂。
* 有可能重复调用同一个子 Agent。

适合：

* Agent 选择逻辑相对简单。
* 子 Agent 独立性强。
* 执行顺序允许由模型决定。

不适合：

* 有严格前置依赖。
* 必须确定性并行。
* 必须精确控制失败跳过。
* 需要多个 Human Confirm 节点。

## 6. 当前项目建议

产品分析、知识问答、保单查询、资产查询均可以暴露成 Agent Tool。

但当前保险产品管理智能体存在：

* 产品召回。
* 人工确认。
* 串并行混合。
* Agent 依赖。
* 自动重试。
* 失败跳过。
* 输出审核。
* 总结节点。

因此推荐：

```text
Graph 负责总体编排
    ↓
Graph Node 内调用 ReactAgent
    ↓
ReactAgent 内部根据需要调用普通 Tool
```

而不是完全由一个主 ReactAgent 自由调用四个 Agent Tool。

---

# 工作流（Workflow）

## 1. Graph 核心定位

Spring AI Alibaba Graph 是 Agent Framework 的底层运行时，同时也可以直接作为低级工作流 API 使用。

三个核心概念：

### State

节点间传递的数据，底层表现为 `Map<String, Object>`。

### Node

具体执行逻辑：

* 普通 Java 逻辑。
* 数据校验。
* 数据库查询。
* LLM 调用。
* ReactAgent。
* 结果聚合。
* 输出审核。

### Edge

控制节点之间的执行方向：

* 固定边。
* 条件边。
* 并行边。
* 循环边。

Node 完成工作，Edge 决定下一步执行什么。

## 2. Agentic API 与 Graph API

### 使用 Agentic API

适合：

* 常规 ReactAgent。
* 简单 Agent Tool。
* 顺序或并行 Agent。
* 不需要严格控制每个步骤。

### 使用 Graph API

适合：

* 复杂条件路由。
* 确定性流程。
* 人工确认。
* 多节点并行。
* 失败跳过。
* 循环和重试。
* 长时间运行。
* 需要观察每个节点状态。
* 普通 Java Node 和 Agent Node 混合。

当前保险产品管理智能体更适合 Graph API。

## 3. 自定义 Node

### NodeAction

只需要 State：

```java
public final class ContextAlignmentNode implements NodeAction {

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String userQuery = state.value("userQuery", "");

        return Map.of(
                "alignedContext",
                alignContext(userQuery, state));
    }
}
```

### NodeActionWithConfig

需要访问运行时配置：

```java
public final class PolicyQueryNode
        implements NodeActionWithConfig {

    @Override
    public Map<String, Object> apply(
            OverAllState state,
            RunnableConfig config) {

        String customerId = config.metadata("customerId")
                .map(String::valueOf)
                .orElseThrow();

        return Map.of(
                "policyResult",
                policyService.query(customerId));
    }
}
```

官网分别展示了普通 Node、带配置的 AI Node、条件 Node 和并行聚合 Node。

## 4. Node 返回规则

Node 只返回需要更新的字段：

```java
return Map.of(
        "rewriteQuery", rewriteQuery,
        "needsProductRecall", needsProductRecall);
```

不要返回整份 State。

状态 Key 应配置明确的合并策略：

* `ReplaceStrategy`
* Append 或列表合并策略
* 自定义策略

并行节点不能无控制地写入相同 Key。

## 5. Node 错误处理

不要简单地在所有 Node 内吞掉异常并只返回字符串。

建议统一错误结构：

```java
public record NodeError(
        String nodeName,
        String errorCode,
        String message,
        boolean retryable,
        String dependency) {
}
```

State 中维护：

```text
errors
failedNodes
skippedNodes
retryCounts
```

异常策略：

```text
可重试错误
    → 自动重试

不可重试且无依赖影响
    → 记录失败，其他节点继续

前置依赖失败
    → 跳过依赖节点

关键节点失败
    → 进入降级或 END

最终 Summary
    → 说明结果缺失原因
```

## 6. ReactAgent 作为 Node

ReactAgent 可通过 `asNode()` 转换为 StateGraph 节点。

示例结构：

```java
workflow.addNode(
        "product_analysis",
        productAnalysisAgent.asNode(true, false));

workflow.addNode(
        "knowledge_qa",
        knowledgeAgent.asNode(true, false));
```

具体参数含义必须以当前 `1.1.2.3` 源码为准，Codex 不得仅根据布尔值位置猜测含义。

## 7. 普通 Node 与 Agent Node 混合

推荐工作流：

```text
START
  ↓
输入审核 Node
  ↓
上下文对齐与问题改写 Node
  ↓
意图识别 Agent/Node
  ↓
产品召回判断 Node
  ↓
产品候选检索 Node
  ↓
Human Confirm Node
  ↓
执行计划 Node
  ↓
并行或串行调用子 Agent
  ↓
结果聚合 Node
  ↓
输出审核 Agent/Node
  ↓
总结 Agent
  ↓
END
```

Graph 中普通 Node 负责确定性业务逻辑；ReactAgent 负责需要模型推理和 Tool Calling 的任务。官网也展示了普通预处理、校验 Node 与 Agent Node 混合的工作流。

## 8. 性能建议

* 无依赖的查询 Agent 并行执行。
* 不要在同一节点中重复调用模型。
* 数据清洗和校验优先用 Java。
* Agent 输出使用结构化 DTO，减少二次解析。
* 为每个模型调用和工具调用设置超时。
* 限制工作流总执行时间。
* 限制循环次数和重试次数。
* 大结果不要全部写入 `messages`。
* 节点 State 只保存后续真正需要的数据。
* 流式输出按节点类型和事件类型过滤。

官网工作流页面还包含特定环境下与 Dify 的压测数据，但这类结果不能直接用于当前项目容量规划；实际性能应以当前模型延迟、POD 规格、线程池和并发模型重新压测。

---

# 检索增强生成（RAG）

## 1. 核心定位

RAG 用于解决模型：

* 无法一次性读取全部知识。
* 训练知识存在时间截止。
* 不掌握企业内部信息。
* 容易生成无依据回答。

RAG 在运行时先获取与问题相关的外部知识，再让模型基于该知识回答。

## 2. 知识库组成

完整知识库一般包括：

```text
文档加载
    ↓
文档解析
    ↓
文本切分
    ↓
Embedding
    ↓
VectorStore
    ↓
检索
    ↓
重排序或过滤
    ↓
上下文注入
```

Spring AI 提供文档 Reader、Parser、Embedding Model、VectorStore 和模块化 Retriever 等组件。

## 3. 已有知识库不必重建

已有以下系统时：

* FAQ 平台。
* SQL 数据库。
* CRM。
* 产品管理系统。
* 文档平台。
* 行内知识库。
* 搜索服务。

可以直接：

1. 封装成 Agent Tool。
2. 在模型调用前查询，并将结果作为上下文。
3. 组合向量检索与关键词检索。

不必为了使用 RAG 把所有业务数据重新复制到向量库。

## 4. 两步 RAG

固定流程：

```text
用户问题
    ↓
检索
    ↓
上下文注入
    ↓
模型回答
```

特点：

* 路径固定。
* 延迟相对可预测。
* 检索一定执行。
* 容易测试和审计。
* 适合 FAQ、制度、条款和产品文档问答。

可以通过 `MessagesModelHook` 在 `BEFORE_MODEL` 阶段检索文档并注入消息。官网提供了 VectorStore 检索后替换消息列表的 Hook 示例。

注意：如果每次 ReAct 循环的 `BEFORE_MODEL` 都重新检索，可能产生重复查询。可以在 `BEFORE_AGENT` 检索一次，并通过 State 或 Context 供后续模型调用使用。官网也展示了此类 Hook 与 Interceptor 组合模式。

## 5. Agentic RAG

把搜索能力封装成 Tool，由 Agent 决定：

* 是否搜索。
* 搜索哪个数据源。
* 使用什么查询。
* 是否需要多次搜索。
* 是否组合多个来源。

例如：

```text
faq_search
product_document_search
policy_database_query
customer_asset_query
web_search
```

适合：

* 问题可能不需要知识检索。
* 存在多个异构数据源。
* 需要多轮调查。
* 查询策略取决于中间结果。

官网展示了 Web、数据库和文档库作为多个搜索工具交给 Agent 自主选择的模式。

## 6. 混合 RAG

混合 RAG 结合固定检索和 Agentic RAG：

```text
问题改写
    ↓
首次检索
    ↓
相关性验证
    ↓
不充分 → 改写并再次检索
    ↓
生成回答
    ↓
答案一致性验证
    ↓
不合格 → 修订或重新生成
```

典型组成：

* Query Rewrite。
* 多查询扩展。
* Metadata Filter。
* 混合检索。
* Rerank。
* 检索充分性判断。
* 答案引用验证。
* 最终审核。

官网将查询增强、检索验证和答案验证列为混合 RAG 的关键步骤。

## 7. 三种架构选择

| 架构          | 适用场景     | 优点         | 风险       |
| ----------- | -------- | ---------- | -------- |
| 两步 RAG      | FAQ、制度问答 | 确定、可测、延迟稳定 | 每次都检索    |
| Agentic RAG | 研究、复杂查询  | 灵活、多数据源    | 路径和成本不稳定 |
| 混合 RAG      | 金融领域复杂问答 | 质量与控制较平衡   | 实现复杂     |

官网对三种架构的控制性、灵活性和延迟进行了区分。

## 8. 模块化 RAG

Spring AI 模块化 RAG 分为：

### Pre-Retrieval

* 查询重写。
* 查询压缩。
* 查询翻译。
* 多查询扩展。

### Retrieval

* 文档搜索。
* 多数据源检索。
* 文档连接。

### Post-Retrieval

* 重排序。
* 去重。
* 压缩。
* 过滤低相关文档。

### Generation

* Query Augmenter。
* 上下文注入。
* 最终回答生成。

模块化组件允许按业务场景组合，而不必把所有步骤写在一个 Service 中。

## 9. 当前保险项目建议

### 产品召回

产品召回不是普通知识问答，建议：

```text
产品名称、简称、代码、属性提取
    ↓
关键词检索 + 向量检索
    ↓
产品主数据校验
    ↓
候选列表
    ↓
人工确认
```

不能仅依赖向量相似度直接认定产品。

### 保险知识问答

适合混合 RAG：

```text
用户问题改写
    ↓
FAQ / 条款 / 业务制度检索
    ↓
重排序
    ↓
生成回答
    ↓
回答依据审核
```

### 保单和资产查询

属于结构化数据查询，应优先使用数据库 Tool，而不是把客户数据向量化。

## 10. RAG 输出规范

检索结果应包含：

```java
public record RetrievedEvidence(
        String documentId,
        String title,
        String section,
        String content,
        double score,
        Map<String, Object> metadata) {
}
```

最终回答建议包含：

```java
public record KnowledgeAnswer(
        String answer,
        List<String> evidenceIds,
        boolean sufficientEvidence,
        List<String> missingInformation,
        String warning) {
}
```

模型没有足够证据时必须明确说明，不允许用常识补全行内业务规则。

---

# 分布式智能体（A2A Agent）

## 1. 核心定位

A2A 用于解决不同应用、不同部署环境和不同技术框架的 Agent 之间的远程通信。

Spring AI Alibaba A2A 包含：

1. A2A Server：将本地 `ReactAgent` 暴露为远程服务。
2. A2A Registry：注册 Agent。
3. A2A Discovery：发现远程 Agent。

当前实现支持通过 Nacos 注册和发现。

## 2. AgentCard

AgentCard 描述远程 Agent 的：

* 名称。
* 描述。
* 版本。
* Provider。
* 服务地址。
* 能力信息。

A2A Server 启动后可暴露：

```text
/.well-known/agent.json
/a2a/message
```

第一个用于获取 AgentCard，第二个用于调用 Agent。

## 3. 名称一致性

配置中的：

```yaml
spring.ai.alibaba.a2a.server.card.name
```

必须与本地 `ReactAgent` 的 `name` 一致。

例如：

```java
ReactAgent.builder()
        .name("product_analysis_agent")
```

对应：

```yaml
spring:
  ai:
    alibaba:
      a2a:
        server:
          card:
            name: product_analysis_agent
```

否则注册、发现或调用可能失败。

## 4. 远程调用

远程 Agent 可以通过 `A2aRemoteAgent` 表示：

```java
A2aRemoteAgent remoteAgent = A2aRemoteAgent.builder()
        .name("product_analysis_agent")
        .agentCardProvider(agentCardProvider)
        .description("远程保险产品分析智能体")
        .build();
```

之后可以像本地 Agent 一样调用，但底层实际通过网络完成。

## 5. Registry 与 Discovery

### Registry

```yaml
registry:
  enabled: true
```

将当前应用中的 Agent 注册到 Nacos，当前应用是服务提供者。

### Discovery

```yaml
discovery:
  enabled: true
```

从 Nacos 发现其他 Agent，当前应用是服务消费者。

两者可以独立开启，也可以同时开启。

## 6. 依赖和部署限制

官网要求增加 A2A Nacos Starter，并保证 Nacos 可用。

当前文档还指出：

* 默认情况下只注册一个 Agent Bean。
* 多 Agent 注册可能需要运行多个应用实例。
* 每个实例配置不同的 Agent。
* 远程调用失败时需检查 AgentCard、网络、防火墙和 A2A 日志。

这些行为可能随版本变化，Codex 必须核对当前版本源码和自动配置类。

## 7. 生产安全要求

A2A 服务不能直接无保护暴露：

* 增加身份认证。
* 增加调用方授权。
* 增加租户隔离。
* 限制可调用 Agent。
* 限制输入大小。
* 设置连接和读取超时。
* 增加重试与熔断。
* 防止重复请求产生副作用。
* 对敏感字段脱敏。
* 记录 Trace ID。
* 不信任远程 Agent 返回内容。
* 跨系统写操作仍需业务审批。

远程 Agent 返回的数据应作为外部输入再次校验。

## 8. 当前项目是否需要 A2A

当前四个保险子 Agent 若处于：

```text
同一代码仓库
同一 Spring Boot 应用
同一部署单元
```

则不需要 A2A，直接使用 Graph、Agent Node 或 Agent Tool。

只有在以下情况才考虑 A2A：

* 不同团队分别维护 Agent。
* Agent 独立发布。
* Agent 使用不同技术栈。
* Agent 需要跨系统复用。
* Agent 需要独立扩缩容。
* 网络和权限边界要求物理隔离。

不要为了“多 Agent”而使用 A2A。A2A 解决的是分布式通信问题，不是普通的进程内编排问题。

---

# 高级功能统一选型表

| 需求                  | 推荐技术                              |
| ------------------- | --------------------------------- |
| 临时修改本次模型消息          | `ModelInterceptor`                |
| 永久修改消息或 State       | `ModelHook` / `MessagesModelHook` |
| 限制高风险 Tool          | `HumanInTheLoopHook`              |
| 保存当前会话工作流状态         | Saver / Checkpointer              |
| 保存跨会话用户偏好           | Store                             |
| 简单顺序执行多个 Agent      | `SequentialAgent`                 |
| 简单并行执行多个 Agent      | `ParallelAgent`                   |
| 单次智能路由              | `LlmRoutingAgent`                 |
| 多步骤动态路由             | `SupervisorAgent`                 |
| 主 Agent 调用专业子 Agent | `AgentTool`                       |
| 严格业务流程编排            | `StateGraph`                      |
| 固定检索后回答             | 两步 RAG                            |
| Agent 自主选择检索        | Agentic RAG                       |
| 检索、验证、重试            | 混合 RAG                            |
| 跨应用调用 Agent         | A2A                               |

---

# 当前保险产品管理智能体推荐架构

```text
用户输入
    ↓
输入审核 Node
    ↓
上下文对齐与问题改写 Node
    ↓
意图识别 Node
    ↓
产品召回判断 Node
    ↓
需要产品召回？
    ├── 否 ─────────────────────────────┐
    └── 是                              │
         ↓                              │
    混合检索召回产品                     │
         ↓                              │
    候选产品校验                         │
         ↓                              │
    Human Confirm                       │
         ↓                              │
    保存已确认产品到会话 State            │
         └───────────────────────────────┘
                         ↓
                    执行计划 Node
                         ↓
        ┌────────────────┼────────────────┐
        ↓                ↓                ↓
  产品分析 Agent    知识问答 Agent    保单/资产查询 Agent
        └────────────────┼────────────────┘
                         ↓
                    结果聚合 Node
                         ↓
                    输出审核 Node
                         ↓
                    Summary Agent
                         ↓
                        END
```

## 技术职责

```text
StateGraph
    控制业务流程和节点依赖

ReactAgent
    完成节点内部推理和工具选择

Tool
    完成真实数据查询和计算

Skill
    描述任务方法、规则和执行步骤

ModelInterceptor
    临时调整 Prompt、消息和可用工具

Hook
    修改持久 State、摘要、审批和生命周期控制

Saver
    保存当前会话和中断状态

Store
    保存跨会话长期信息

Structured Output
    作为节点间数据契约
```

---

# Codex 开发强制检查清单

Codex 每次修改项目时必须检查：

* [ ] 当前 API 是否存在于 `1.1.2.3-sources.jar`。
* [ ] 是否复用了已有 `ChatModel` Bean。
* [ ] `conversationId` 和 `threadId` 是否统一。
* [ ] Runtime Context、State、Store 是否职责清晰。
* [ ] 是否重复加载同一份聊天历史。
* [ ] ToolCall 和 ToolResponse 是否成对保存。
* [ ] 是否将可信客户号交给模型生成。
* [ ] 高风险 Tool 是否配置人工审批。
* [ ] Human Feedback 恢复时是否使用相同 `threadId`。
* [ ] Workflow 与嵌套 Agent 是否正确共享 Checkpointer。
* [ ] 每个 Agent 是否有唯一 `name` 和 `outputKey`。
* [ ] 子 Agent 的输入和输出是否结构化。
* [ ] 并行节点是否写入不同 State Key。
* [ ] 是否限制模型循环、重试和总执行时间。
* [ ] 是否区分可重试异常与业务异常。
* [ ] RAG 是否保留文档 ID、来源和相关度。
* [ ] 结构化数据查询是否错误地使用了向量检索。
* [ ] 是否对长期记忆进行敏感信息过滤。
* [ ] A2A 是否确实存在跨应用部署需求。
* [ ] 官网概念代码是否被误当成可直接编译代码。
