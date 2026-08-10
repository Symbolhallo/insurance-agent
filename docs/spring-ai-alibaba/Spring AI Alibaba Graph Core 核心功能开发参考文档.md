# Spring AI Alibaba Graph Core 核心功能开发参考文档

## 1. 文档定位

本文用于指导 Codex 在现有保险产品管理智能体项目中使用 Spring AI Alibaba Graph Core 实现：

* 工作流状态定义
* 节点与路由
* 串行、条件和并行执行
* 会话级短期记忆
* 跨会话长期记忆
* Graph 状态持久化
* 工作流暂停、恢复和重放
* SSE 流式输出
* ReactAgent 与 Graph 的组合

Graph Core 左侧“核心功能”栏目包含以下章节：

1. 核心概念
2. 内存管理
3. 持久化
4. 流式输出

Spring AI Alibaba Graph 将工作流建模为由 State、Node 和 Edge 构成的有向图，并通过 `StateGraph` 定义、`CompiledGraph` 执行。

---

# 核心概念

## 1. Graph 的基本模型

Graph Core 中有三个核心元素。

### State

State 是整个工作流共享的数据快照，由 `OverAllState` 表示。

状态中可以保存：

* 用户原始问题
* 改写后的问题
* 意图识别结果
* 已确认的保险产品
* 子智能体执行结果
* 节点错误
* 重试次数
* 当前执行计划
* 最终回答
* 消息历史

### Node

Node 负责执行具体工作，例如：

* 普通 Java 业务判断
* 数据库查询
* 调用大模型
* 调用 ReactAgent
* 检索知识库
* 审核输入
* 汇总结果

主要节点接口包括：

```java
AsyncNodeAction
AsyncNodeActionWithConfig
```

`AsyncNodeAction` 只接收 `OverAllState`。

`AsyncNodeActionWithConfig` 除了接收 State，还可以接收 `RunnableConfig`，适合读取：

* `threadId`
* metadata
* Store
* 运行时上下文
* 用户和客户身份

### Edge

Edge 负责决定节点执行完成后前往哪里。

主要接口包括：

```java
AsyncEdgeAction
AsyncEdgeActionWithConfig
```

Graph 的基本职责可以概括为：

```text
Node：完成工作
Edge：决定下一步
State：在节点之间传递数据
```

官方文档将 State、Node 和 Edge 定义为 Graph 工作流的三个关键组成部分。

---

## 2. StateGraph

`StateGraph` 是图的定义对象，主要用于：

1. 定义状态更新策略。
2. 注册节点。
3. 注册普通边。
4. 注册条件边。
5. 配置入口和结束位置。
6. 最后编译为 `CompiledGraph`。

基本结构：

```java
StateGraph stateGraph = new StateGraph(keyStrategyFactory)
        .addNode("context_alignment", contextAlignmentNode)
        .addNode("intent_recognition", intentRecognitionNode)
        .addNode("summary", summaryNode)
        .addEdge(START, "context_alignment")
        .addEdge("context_alignment", "intent_recognition")
        .addEdge("summary", END);

CompiledGraph graph = stateGraph.compile();
```

Graph 在执行前必须调用 `compile()`。编译过程会检查图结构，并允许配置 Checkpointer、中断点等运行时能力。

### Codex 实现要求

Codex 不应在 Controller 中动态创建和编译 Graph。

推荐：

```java
@Configuration
public class InsuranceWorkflowConfiguration {

    @Bean
    public CompiledGraph insuranceWorkflow(...) {
        StateGraph stateGraph = buildWorkflow(...);
        return stateGraph.compile(...);
    }
}
```

原因：

* 图结构是稳定配置。
* 编译具有一定初始化成本。
* 动态创建容易产生多个 Saver、线程池和状态定义。
* 不利于测试和依赖注入。

---

## 3. OverAllState

`OverAllState` 本质上是以字符串 Key 为入口的状态容器。

读取状态：

```java
String userQuery = state.value("userQuery")
        .map(String::valueOf)
        .orElse("");
```

或者：

```java
String userQuery = state.value("userQuery", "");
```

节点不应直接修改整个 State，而应返回本节点产生的增量更新：

```java
return Map.of(
        "rewriteQuery", rewriteQuery,
        "needsProductRecall", needsProductRecall
);
```

Graph 引擎根据每个 Key 对应的 `KeyStrategy` 将更新合并到全局 State。

### 状态 Key 管理

由于 `OverAllState` 是弱类型 Map，禁止在节点中散落字符串。

推荐建立常量类：

```java
public final class InsuranceStateKeys {

    public static final String USER_QUERY = "userQuery";
    public static final String REWRITE_QUERY = "rewriteQuery";
    public static final String INTENT_RESULT = "intentResult";

    public static final String PRODUCT_CANDIDATES = "productCandidates";
    public static final String CONFIRMED_PRODUCTS = "confirmedProducts";

    public static final String EXECUTION_PLAN = "executionPlan";

    public static final String PRODUCT_ANALYSIS_RESULT =
            "productAnalysisResult";

    public static final String KNOWLEDGE_RESULT =
            "knowledgeResult";

    public static final String POLICY_RESULT =
            "policyResult";

    public static final String ASSET_RESULT =
            "assetResult";

    public static final String NODE_ERRORS = "nodeErrors";
    public static final String FINAL_ANSWER = "finalAnswer";

    private InsuranceStateKeys() {
    }
}
```

进一步建议定义统一读取工具，避免不安全强制转换：

```java
public final class GraphStateReader {

    public static String getString(
            OverAllState state,
            String key) {

        return state.value(key)
                .map(String::valueOf)
                .orElse("");
    }

    public static <T> Optional<T> get(
            OverAllState state,
            String key,
            Class<T> type) {

        return state.value(key)
                .filter(type::isInstance)
                .map(type::cast);
    }

    private GraphStateReader() {
    }
}
```

---

## 4. KeyStrategy

每个 State Key 都有独立的更新策略。

如果未明确指定，默认通常使用 `ReplaceStrategy`。

定义方式：

```java
KeyStrategyFactory keyStrategyFactory = () -> {
    Map<String, KeyStrategy> strategies = new HashMap<>();

    strategies.put(
            InsuranceStateKeys.USER_QUERY,
            new ReplaceStrategy());

    strategies.put(
            InsuranceStateKeys.REWRITE_QUERY,
            new ReplaceStrategy());

    strategies.put(
            InsuranceStateKeys.NODE_ERRORS,
            new AppendStrategy());

    return strategies;
};
```

官方文档重点介绍了 `ReplaceStrategy`、`AppendStrategy` 和自定义 KeyStrategy。

---

## 5. ReplaceStrategy

`ReplaceStrategy` 用新值完整替换旧值。

适合：

* 用户当前问题
* 改写问题
* 意图识别结果
* 是否需要产品召回
* 当前执行计划
* 最终回答
* 单个 Agent 的最终结果
* 当前节点状态

示例：

```java
strategies.put(
        InsuranceStateKeys.REWRITE_QUERY,
        new ReplaceStrategy());
```

执行过程：

```text
原值：
rewriteQuery = "旧问题"

节点更新：
rewriteQuery = "改写后的完整问题"

最终值：
rewriteQuery = "改写后的完整问题"
```

多个节点依次更新相同 Replace Key 时，后执行节点的值会覆盖前面的值。

### 并行节点注意事项

并行节点不应同时写入同一个 Replace Key。

错误设计：

```text
productAgent  → result
knowledgeAgent → result
policyAgent   → result
```

三个并行节点都写 `result`，会导致结果相互覆盖或出现执行顺序相关问题。

正确设计：

```text
productAgent   → productAnalysisResult
knowledgeAgent → knowledgeResult
policyAgent    → policyResult
```

最后由聚合节点写：

```text
aggregatedResult
```

---

## 6. AppendStrategy

`AppendStrategy` 将新值追加到旧值后面。

适合：

* 消息历史
* 错误列表
* 执行日志
* 已完成节点列表
* 警告列表
* 检索证据列表

示例：

```java
strategies.put(
        InsuranceStateKeys.NODE_ERRORS,
        new AppendStrategy());
```

节点返回：

```java
return Map.of(
        InsuranceStateKeys.NODE_ERRORS,
        List.of(nodeError)
);
```

注意节点应返回“本次新增值”，而不是返回 State 中已经存在的完整列表。

错误：

```java
List<NodeError> oldErrors = ...;
oldErrors.add(newError);

return Map.of("nodeErrors", oldErrors);
```

如果使用 AppendStrategy，旧数据可能被再次追加。

正确：

```java
return Map.of(
        "nodeErrors",
        List.of(newError)
);
```

官方示例表明，使用 AppendStrategy 时，各节点返回的值会依次累积，而不是相互覆盖。

### RemoveByHash

AppendStrategy 支持通过 `RemoveByHash` 删除已有元素：

```java
return Map.of(
        "messages",
        RemoveByHash.of(messageToRemove)
);
```

删除依据是对象的 `hashCode`，因此：

* 自定义对象必须正确实现 `equals()` 和 `hashCode()`。
* 不适合依赖可变对象。
* 消息内容变化后 Hash 可能不同。
* 金融业务状态不建议仅依靠 Hash 删除。

官方示例使用 `RemoveByHash` 从 Append 状态中移除指定消息。

---

## 7. 自定义 KeyStrategy

复杂状态可以自定义合并策略。

例如根据业务主键合并产品候选：

```java
public final class ProductCandidateMergeStrategy
        implements KeyStrategy {

    @Override
    public Object apply(
            Object oldValue,
            Object newValue) {

        Map<String, ProductCandidate> merged =
                new LinkedHashMap<>();

        if (oldValue instanceof Collection<?> oldItems) {
            oldItems.stream()
                    .filter(ProductCandidate.class::isInstance)
                    .map(ProductCandidate.class::cast)
                    .forEach(item ->
                            merged.put(
                                    item.productCode(),
                                    item));
        }

        if (newValue instanceof Collection<?> newItems) {
            newItems.stream()
                    .filter(ProductCandidate.class::isInstance)
                    .map(ProductCandidate.class::cast)
                    .forEach(item ->
                            merged.put(
                                    item.productCode(),
                                    item));
        }

        return List.copyOf(merged.values());
    }
}
```

适用场景：

* 根据产品代码去重。
* 根据文档 ID 合并证据。
* 根据节点名覆盖同一节点错误。
* 合并并行 Agent 的执行状态。
* 只保留最新 N 条消息。

自定义策略必须满足：

* 可预测。
* 尽量无副作用。
* 对空值有明确处理。
* 并行执行时结果不能依赖不稳定顺序。
* 支持序列化。

---

## 8. 节点 Nodes

节点主要有两类。

### AsyncNodeAction

只需要 State：

```java
var contextAlignmentNode = node_async(state -> {
    String userQuery =
            GraphStateReader.getString(
                    state,
                    InsuranceStateKeys.USER_QUERY);

    ContextAlignmentResult result =
            contextAlignmentService.align(
                    userQuery,
                    state);

    return Map.of(
            InsuranceStateKeys.REWRITE_QUERY,
            result.rewriteQuery()
    );
});
```

### AsyncNodeActionWithConfig

需要 Runtime Config：

```java
var policyQueryNode = node_async((state, config) -> {
    String customerId = config.metadata("customerId")
            .map(String::valueOf)
            .orElseThrow(() ->
                    new IllegalArgumentException(
                            "customerId is required"));

    PolicyQueryResult result =
            policyService.queryPolicies(customerId);

    return Map.of(
            InsuranceStateKeys.POLICY_RESULT,
            result
    );
});
```

官方文档说明，`AsyncNodeActionWithConfig` 可以同时访问 `OverAllState` 和 `RunnableConfig`。

### 节点职责原则

一个 Node 应只完成一项清晰职责。

推荐：

```text
context_alignment
query_rewrite
intent_recognition
product_recall_decision
product_retrieval
product_confirmation
execution_planning
product_analysis
knowledge_qa
policy_query
asset_query
result_aggregation
output_review
summary
```

不推荐：

```text
do_everything
main_agent_node
process_all
```

---

## 9. START 和 END

`START` 和 `END` 是特殊节点标识。

```java
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.StateGraph.END;
```

`START` 表示工作流入口：

```java
.addEdge(START, "input_review")
```

`END` 表示工作流结束：

```java
.addEdge("summary", END)
```

它们不承担实际业务逻辑。

---

## 10. 普通边

普通边表示确定性流转：

```java
stateGraph
        .addEdge(START, "input_review")
        .addEdge("input_review", "context_alignment")
        .addEdge("context_alignment", "intent_recognition");
```

适合：

* 必须执行的固定步骤。
* 清晰稳定的前后依赖。
* 审核后一定进入总结。

---

## 11. 条件边

条件边根据 State 决定下一个节点：

```java
stateGraph.addConditionalEdges(
        "product_recall_decision",
        edge_async(state -> {
            boolean needsRecall = state
                    .value("needsProductRecall")
                    .filter(Boolean.class::isInstance)
                    .map(Boolean.class::cast)
                    .orElse(false);

            return needsRecall
                    ? "recall_required"
                    : "recall_not_required";
        }),
        Map.of(
                "recall_required",
                "product_retrieval",
                "recall_not_required",
                "execution_planning"
        )
);
```

路由函数返回的是“路由标识”，随后通过 Map 映射到真实节点名称。

### Codex 实现要求

条件边只做路由，不要在 Edge 中执行：

* 数据库查询。
* 模型调用。
* 状态写入。
* 外部接口调用。
* 复杂业务运算。

复杂判断应先由 Node 产生结构化状态，Edge 只读取状态并路由。

推荐：

```text
Node：计算 needsProductRecall
Edge：读取 needsProductRecall 决定下一节点
```

---

## 12. 多个出边与并行执行

同一个节点配置多个普通出边时，目标节点可以形成并行执行关系。

例如：

```java
stateGraph
        .addEdge("execution_planning", "product_analysis")
        .addEdge("execution_planning", "knowledge_qa")
        .addEdge("execution_planning", "policy_query")
        .addEdge("execution_planning", "asset_query");
```

并行执行必须满足：

1. 节点之间没有前置依赖。
2. 节点写入不同的 State Key。
3. 汇总节点等待所有必要分支完成。
4. 每个节点的失败可以独立记录。
5. 不共享非线程安全的可变对象。

---

## 13. Serializer

Graph 在以下场景需要序列化 State：

* 状态克隆。
* Checkpoint 保存。
* 状态恢复。
* 子图传递。
* 跨实例持久化。

官方默认采用 Jackson 序列化，并提供 JDK 序列化等实现；对于自定义类型，可以增加 Jackson 注解、定制默认 `ObjectMapper`，或者向 `StateGraph` 提供自定义 Serializer。

### DTO 建议

状态 DTO 推荐：

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductCandidate(
        String productCode,
        String productName,
        double score) {
}
```

应避免写入 State：

* Spring Bean。
* `ChatClient`。
* `DataSource`。
* `HttpServletRequest`。
* 打开的数据库连接。
* 线程池对象。
* 不可序列化的流或资源句柄。
* 含敏感凭证的对象。

### 自定义序列化器

当 State 中包含框架不能正确序列化的第三方类型时，推荐扩展 `SpringAIJacksonStateSerializer` 并注册专用 Serializer/Deserializer。官方文档将向 Graph 提供自定义序列化器列为更可控的实现方式。

### Codex 检查项

Codex 增加新的 State DTO 后，必须至少测试：

```text
DTO → JSON → DTO
```

同时测试：

* null 字段。
* 空列表。
* 枚举。
* 嵌套对象。
* Spring AI Message。
* ToolCall。
* ToolResponse。
* Checkpoint 保存和恢复。

---

## 14. Threads

Thread 是 Checkpointer 保存一系列 Checkpoint 的会话标识。

```java
RunnableConfig config = RunnableConfig.builder()
        .threadId(conversationId)
        .build();
```

使用相同 `threadId`，Graph 可以继续使用之前保存的状态；使用不同 `threadId`，会创建彼此隔离的工作流状态。

当前项目建议：

```text
conversationId
    =
Graph threadId
```

如果无法保持一致，必须建立明确映射：

```text
conversationId → workflowThreadId
```

禁止：

* 多个用户共用固定 threadId。
* 使用客户号直接作为 threadId。
* 使用空 threadId 运行需要持久化的工作流。
* 前端直接决定任意 threadId 且后端不做归属校验。

---

# 内存管理

## 1. 短期内存与长期内存

Graph Core 将内存分为两类：

| 类型   | 作用域            | 主要组件               |
| ---- | -------------- | ------------------ |
| 短期内存 | 当前 Thread / 会话 | Checkpointer、Saver |
| 长期内存 | 跨 Thread / 跨会话 | Store              |

短期内存作为 Graph State 的一部分，用于维持多轮对话和当前工作流状态；长期内存通过 Store 保存用户级或应用级数据。

---

## 2. 短期内存

短期内存通过 Checkpointer 实现。

基本配置：

```java
MemorySaver memorySaver = new MemorySaver();

SaverConfig saverConfig = SaverConfig.builder()
        .register(memorySaver)
        .build();

CompiledGraph graph = stateGraph.compile(
        CompileConfig.builder()
                .saverConfig(saverConfig)
                .build()
);
```

运行：

```java
RunnableConfig config = RunnableConfig.builder()
        .threadId(conversationId)
        .build();

graph.invoke(input, config);
```

相同 `threadId` 的后续调用会继续访问已保存的会话状态。

### MemorySaver

`MemorySaver` 只在当前 JVM 内保存数据。

适合：

* 单元测试。
* 本地调试。
* Demo。
* 验证 Graph 状态流转。

不适合：

* 多实例部署。
* 应用重启后恢复。
* 长时间人工审批。
* 生产会话记忆。

官方建议生产环境使用 Redis 等数据库支持的 Checkpointer。

---

## 3. messages 状态

消息历史通常配置为：

```java
strategies.put(
        "messages",
        new AppendStrategy());
```

每轮请求只传入新增的用户消息：

```java
graph.invoke(
        Map.of(
                "messages",
                List.of(currentUserMessage)
        ),
        config
);
```

不要每轮都把数据库中的完整历史重新放入 Graph。

错误：

```text
数据库加载完整历史
+
Checkpointer 自动恢复历史
+
本轮消息
```

这会导致历史重复。

正确职责划分：

```text
Graph Checkpointer
    负责模型当前需要的短期状态

ai_chat_memory
    负责聊天审计、前端展示和业务归档
```

---

## 4. 子图中的短期内存

官方文档说明，只在父图编译时提供 Checkpointer 时，Graph 可以将其传播到子图；如果希望子图维护独立的内部历史，也可以为子图配置自己的 Checkpointer。

推荐原则：

### 共享父图状态

适合：

* 子图只是父流程的一部分。
* 子图无需独立会话。
* 子图只执行一次专业任务。
* 希望全流程统一恢复。

### 子图独立状态

适合：

* 子 Agent 有自己连续的多轮交互。
* 子图生命周期独立。
* 子图可能被多个父流程复用。
* 需要分别查看子图执行历史。

### 工程风险

父图和子图都使用 AppendStrategy 时，要特别注意不要把子图返回的“完整历史”再次追加到父图。

官方仓库中曾有关于嵌套子图与 Append 状态重复合并的缺陷报告。因此必须通过集成测试验证当前 `1.1.2.3` 的实际行为，不应仅凭示例推断。

---

## 5. 长期内存 Store

长期内存通过 `Store` 抽象实现。

官方示例包括：

```java
Store store = config.store();

Optional<StoreItem> item =
        store.getItem(namespace, key);

store.putItem(
        StoreItem.of(namespace, key, value)
);
```

`Store` 通过 `RunnableConfig` 注入：

```java
RunnableConfig config = RunnableConfig.builder()
        .threadId(conversationId)
        .store(memoryStore)
        .build();
```

需要访问 Store 的节点使用 `AsyncNodeActionWithConfig`。

---

## 6. Namespace 设计

长期记忆应使用层次化命名空间：

```java
List<String> namespace = List.of(
        "insurance-agent",
        tenantId,
        userId,
        "preferences"
);
```

或者：

```java
List<String> namespace = List.of(
        "insurance-agent",
        tenantId,
        customerId,
        "confirmed-products"
);
```

建议至少包含：

* 应用名。
* 租户或机构。
* 用户或客户。
* 数据类型。

禁止使用：

```java
List.of("memory")
```

将所有用户数据混在一个命名空间中。

---

## 7. 当前项目长期记忆建议

适合写入 Store：

* 用户偏好的回答风格。
* 用户偏好的分析维度。
* 用户已明确确认的产品简称映射。
* 跨会话有效的业务偏好。
* 客户经理经授权保存的配置。

不适合写入 Store：

* 每轮聊天消息。
* 模型猜测出的客户事实。
* 当前临时查询条件。
* 未经确认的产品候选。
* 密码、Token、API Key。
* 银行卡号等敏感信息。
* 本应来自业务数据库的权威数据。

客户保单和资产信息应实时从业务系统查询，不应将 Store 当作权威业务数据库。

---

## 8. 使用 Store 作为缓存

官方文档也展示了通过 Store 实现缓存：先查询 Store，未命中时执行耗时操作，再保存结果。

但是“长期记忆”和“缓存”应在逻辑上隔离：

```text
长期记忆 namespace：
insurance-agent/{tenant}/{user}/preferences

缓存 namespace：
insurance-agent/cache/product-detail
```

缓存还必须设计：

* TTL。
* 版本。
* 缓存键。
* 数据更新后失效。
* 数据权限。
* 是否允许跨用户复用。

如果 Store 实现不支持 TTL，不应把它直接当成熟缓存系统使用。

---

## 9. 短期和长期内存组合

推荐流程：

```text
START
  ↓
加载长期偏好
  ↓
恢复会话级短期状态
  ↓
执行当前工作流
  ↓
必要时保存明确的新偏好
  ↓
END
```

官方示例通过 Checkpointer 保存短期消息，同时通过 Store 加载跨会话用户偏好。

当前项目建议建立三层存储：

```text
1. ai_chat_memory
   原始聊天记录、审计、前端展示

2. Graph Saver / Checkpointer
   当前工作流状态、节点位置、中断信息

3. Store
   跨会话用户偏好和明确确认的长期事实
```

三层存储不能全部重复注入模型上下文。

---

# 持久化

## 1. Checkpointer

Graph 的持久化由 Checkpointer 实现。

配置 Checkpointer 后，Graph 会在每个 super-step 保存一个状态检查点。

检查点支持：

* 会话记忆。
* Human-in-the-Loop。
* 暂停与恢复。
* 状态查看。
* 状态历史。
* Replay。
* 状态修改。
* 容错处理。

检查点保存在 Thread 中，每个 Thread 包含同一工作流会话的一系列状态快照。

---

## 2. StateSnapshot

官方文档将检查点状态表示为 `StateSnapshot`，主要包含：

* `config`：检查点对应的运行配置。
* `metadata`：检查点元数据。
* `values/state`：当时的全局状态。
* `next`：下一步待执行的节点。
* `tasks`：下一步任务信息。
* 当前节点。
* Checkpoint ID。

不同文档示例中的方法名存在一定差异，例如：

```java
snapshot.state()
snapshot.node()
snapshot.config()
```

Codex 必须以项目实际依赖版本中的 `StateSnapshot` 源码为准。

---

## 3. Checkpoint 数量

一个简单工作流：

```text
START
  ↓
node_a
  ↓
node_b
  ↓
END
```

可能保存：

1. 空状态，下一节点是 START。
2. 已接收输入，下一节点是 node_a。
3. node_a 执行完成，下一节点是 node_b。
4. node_b 执行完成，没有下一节点。

因此，Checkpoint 并不只是“每个业务 Node 一个”，还可能包含输入和起始状态。官方持久化示例说明了这一检查点序列。

### 容量规划

生产环境要评估：

```text
单次工作流检查点数
×
单个状态大小
×
每日调用量
×
保留天数
```

不要在 State 中存入：

* 大型完整文档。
* 大量原始检索结果。
* 超长 ToolResponse。
* 重复消息历史。
* 图片或文件二进制。
* 无后续用途的中间数据。

---

## 4. 获取最新状态

```java
RunnableConfig config = RunnableConfig.builder()
        .threadId(conversationId)
        .build();

StateSnapshot snapshot = graph.getState(config);
```

适用场景：

* 查询当前工作流执行到哪里。
* 查看是否在等待人工确认。
* 查看最后执行节点。
* 展示流程状态。
* 故障排查。

也可以指定 Checkpoint ID 获取历史某个时间点的状态。

---

## 5. 获取状态历史

```java
Collection<StateSnapshot> history =
        graph.getStateHistory(config);
```

官方文档说明，历史检查点通常按最近时间优先返回。

用途：

* 工作流审计。
* 节点耗时分析。
* 错误定位。
* 人工审批记录。
* 查看状态如何变化。
* 构建调试界面。

不应直接把完整 State History 全量返回给前端，必须：

* 数据脱敏。
* 限制数量。
* 只展示必要字段。
* 避免暴露模型内部消息和工具参数。

---

## 6. Replay

Replay 通过：

```text
threadId
+
checkPointId
```

指定从历史检查点重新执行后续步骤。

概念上：

```text
检查点之前的步骤
    → 视为已经执行

检查点之后的步骤
    → 重新执行并形成新分支
```

官方文档将其用于工作流重放和时间旅行。

### 文档示例注意事项

官网 Replay 示例先构建了 `replayConfig`，但后面的示例调用展示成了：

```java
graph.invoke(Map.of(), config);
```

从上下文看，这里应重点核验是否应该传入：

```java
graph.invoke(Map.of(), replayConfig);
```

Codex 不得直接复制这一段，必须查看 `1.1.2.3` 的实际源码和测试。

### 副作用风险

Replay 可能再次执行 Checkpoint 之后的节点。

如果节点包含：

* 发送消息。
* 修改数据库。
* 创建任务。
* 提交审批。
* 扣费。
* 调用外部写接口。

必须实现幂等控制：

```text
workflowId
+
nodeId
+
businessOperationId
```

示例：

```java
if (operationRepository.hasExecuted(
        workflowId,
        nodeId,
        businessOperationId)) {

    return previousResult;
}
```

---

## 7. updateState

Graph 支持直接更新已保存的状态：

```java
graph.updateState(
        config,
        Map.of(
                "confirmedProducts",
                confirmedProducts
        ),
        "execution_planning"
);
```

第三个参数 `asNode` 或对应节点参数，可以影响更新被视为来自哪个节点，以及后续从哪里继续执行。具体语义必须以当前版本源码为准。

重要规则：

`updateState()` 仍然遵循该 Key 的 `KeyStrategy`。

例如：

```text
foo → ReplaceStrategy
bar → AppendStrategy
```

更新：

```java
Map.of(
    "foo", 2,
    "bar", List.of("b")
)
```

可能得到：

```json
{
  "foo": 2,
  "bar": ["a", "b"]
}
```

而不是把 `bar` 直接替换成 `["b"]`。

### 人工确认场景

产品人工确认后，可以更新：

```text
confirmedProducts
humanDecision
currentStatus
```

然后让工作流继续进入：

```text
execution_planning
```

但不要让前端直接传入任意 `asNode`，否则可能绕过：

* 输入审核。
* 产品确认。
* 权限校验。
* 输出审核。

后端必须维护允许恢复的节点白名单。

---

## 8. Checkpointer 实现

官方列出的实现包括：

* `MemorySaver`
* `RedisSaver`
* `PostgreSqlSaver`
* `MongodbSaver`

不同实现可能分布在不同模块或扩展依赖中。

### 选择建议

| 场景                    | 建议                   |
| --------------------- | -------------------- |
| 单元测试、本地调试             | MemorySaver          |
| 高频短期会话、快速恢复           | RedisSaver           |
| 强审计、SQL 查询和长期保存       | PostgreSQL/MySQL 类实现 |
| 文档型状态、已有 MongoDB 基础设施 | MongodbSaver         |

### 生产要求

必须评估：

* 是否支持多实例。
* 是否原子写入。
* 是否有事务。
* 是否有过期清理。
* 是否支持并发更新。
* 是否支持状态历史查询。
* Serializer 是否兼容。
* 大状态的性能。
* 敏感数据加密。
* 租户隔离。

---

## 9. Checkpoint 业务表与聊天表

不建议将 Checkpoint 直接塞入已有 `ai_chat_memory` 表。

推荐职责：

```text
ai_chat_memory
    一行一条用户或助手消息

graph_checkpoint
    一行一个工作流状态快照

graph_thread
    一行一个 Graph 会话

human_approval
    一行一条人工审批记录
```

这样可以分别满足：

* 聊天记录展示。
* 工作流恢复。
* Graph 调试。
* 审计和审批。

---

# 流式输出

## 1. Graph 流式执行

Graph 使用 Reactor `Flux` 提供流式能力。

```java
Flux<NodeOutput> outputs =
        graph.stream(input, config);
```

Flux 是惰性的。仅仅调用 `stream()` 不会真正启动执行，需要：

```java
subscribe()
```

或者：

```java
blockLast()
```

或者由 Spring WebFlux/SSE 框架订阅。

---

## 2. NodeOutput

Graph 流的基本输出类型是 `NodeOutput`。

它可以包含：

* 当前节点 ID。
* 当前全局 State。
* 节点消息。
* 节点执行结果。

普通 Node 执行完成时，会产生普通 `NodeOutput`。

---

## 3. StreamingOutput

流式 LLM 节点产生的增量内容通常包装为 `StreamingOutput`。

```java
if (output instanceof StreamingOutput<?> streamingOutput) {
    String chunk = streamingOutput.chunk();
}
```

Graph 执行流中可能交替出现：

```text
普通 NodeOutput
StreamingOutput Token 1
StreamingOutput Token 2
StreamingOutput Token 3
普通 NodeOutput
END NodeOutput
```

官方文档将 Graph 流分为图级执行流和节点级 Token 流，两者会统一出现在 Graph 返回的 Flux 中。

---

## 4. 节点返回 Flux

流式节点可以将模型 `Flux<ChatResponse>` 放入节点返回 Map：

```java
public final class StreamingSummaryNode
        implements NodeAction {

    private final ChatClient chatClient;

    @Override
    public Map<String, Object> apply(
            OverAllState state) {

        String prompt = buildPrompt(state);

        Flux<ChatResponse> responseFlux =
                chatClient.prompt()
                        .user(prompt)
                        .stream()
                        .chatResponse();

        return Map.of(
                "messages",
                responseFlux
        );
    }
}
```

Graph 引擎会订阅并消费该 Flux，并将 Token 作为 Graph 流的一部分向外发送。流完成后，框架还会把聚合后的最终内容更新到对应 State Key，使下一个节点读取到完整结果。

### 重要限制

一个节点返回 Flux 后，不要在节点内部再次手工调用：

```java
responseFlux.subscribe(...)
```

否则可能产生：

* 重复订阅。
* 模型被调用两次。
* 状态与前端输出不一致。
* 资源无法正确释放。

节点只返回 Flux，订阅交给 Graph 和 Web 层。

---

## 5. 下游节点读取流式结果

虽然上一个节点返回的是 Flux，但下一个节点开始执行时，Graph 已经消费完该流，并把最终结果更新到 State。

因此，下游节点读取的是完整结果：

```java
Object messages =
        state.value("messages")
                .orElse(List.of());
```

不会一边接收 Token 一边执行后续节点。

概念上：

```text
流式节点产生 Token
    ↓
Token 实时输出给前端
    ↓
Flux 完成
    ↓
完整结果写入 State
    ↓
后续节点开始执行
```

官方流式处理节点示例明确说明，Graph 会先自动订阅并消费上一个节点的 Flux，再将聚合结果加入状态。

---

## 6. graph.stream 与 graphResponseStream

官方文档区分：

```java
graph.stream()
```

返回：

```java
Flux<NodeOutput>
```

适用于普通 Graph 执行。

```java
graph.graphResponseStream()
```

返回：

```java
Flux<GraphResponse<NodeOutput>>
```

更适合：

* 嵌套子图。
* 需要保留 GraphResponse 包装信息。
* 区分父图和子图。
* 处理多层流式输出。

具体方法签名必须以当前 `1.1.2.3` 源码为准。

---

## 7. SSE 输出设计

当前项目建议将 Graph 输出转换成统一 SSE 事件，而不是直接序列化 `NodeOutput`。

事件 DTO：

```java
public record WorkflowStreamEvent(
        String conversationId,
        String nodeId,
        WorkflowEventType type,
        Object data,
        long timestamp) {
}
```

事件类型：

```java
public enum WorkflowEventType {

    WORKFLOW_STARTED,
    NODE_STARTED,
    TOKEN,
    REASONING,
    TOOL_CALL,
    TOOL_RESULT,
    NODE_COMPLETED,
    HUMAN_CONFIRM_REQUIRED,
    WARNING,
    ERROR,
    FINAL_RESULT,
    WORKFLOW_COMPLETED
}
```

转换逻辑：

```java
public Flux<ServerSentEvent<WorkflowStreamEvent>> stream(
        WorkflowRequest request) {

    RunnableConfig config = buildConfig(request);

    return graph.stream(
                    buildInput(request),
                    config)
            .map(output -> convertOutput(
                    request.conversationId(),
                    output))
            .map(event ->
                    ServerSentEvent.builder(event)
                            .event(event.type().name())
                            .build())
            .doOnCancel(() ->
                    log.info(
                            "Workflow stream cancelled: {}",
                            request.conversationId()))
            .doOnError(error ->
                    log.error(
                            "Workflow stream failed: {}",
                            request.conversationId(),
                            error));
}
```

---

## 8. 不要把完整 State 每次推给前端

普通 `NodeOutput` 可能包含整个当前 State。

如果每个节点都把完整 State 推给浏览器，会造成：

* 数据量快速增长。
* 客户数据泄露。
* Tool 参数泄露。
* 内部 Prompt 泄露。
* 前端处理复杂。
* SSE 延迟增大。

应该根据节点和事件类型进行白名单转换。

例如：

```text
intent_recognition 完成
    → 只输出“已完成意图识别”

product_retrieval 完成
    → 输出候选产品摘要

policy_query 完成
    → 输出“保单查询完成”，不输出原始敏感数据

summary 流式
    → 输出 Token
```

---

## 9. Thinking 内容处理

Thinking 模型的推理内容和最终回答应分离。

```text
REASONING
    只用于允许展示思考过程的调试环境

TOKEN
    用户可见的最终回答 Token
```

行内生产环境建议默认不将完整思维链输出给前端。

可以仅展示状态提示：

```text
正在分析产品收益……
正在查询保单信息……
正在整理最终结果……
```

---

## 10. 错误处理

流式链路必须处理：

```java
.doOnError(...)
.onErrorResume(...)
.doFinally(...)
```

建议：

```java
return graph.stream(input, config)
        .map(this::convertOutput)
        .onErrorResume(error -> Flux.just(
                WorkflowStreamEvent.error(
                        classifyError(error))))
        .doFinally(signalType ->
                workflowResourceManager.cleanup(
                        config,
                        signalType));
```

错误类型至少区分：

* 模型超时。
* 工具调用失败。
* 数据库异常。
* 序列化异常。
* Graph 路由异常。
* 客户端主动取消。
* 人工确认中断。
* 工作流总超时。

---

## 11. 背压与客户端断开

官方最佳实践强调使用合适的订阅方式、错误处理、资源清理和背压机制。

客户端断开后，应停止或取消：

* 当前模型流。
* 无必要继续执行的 Graph。
* 下游工具请求。
* 定时心跳。
* 临时资源。

但对于已经执行的写操作，不能假设取消流就会自动回滚。

---

## 12. 并行节点流式输出

多个并行节点同时流式输出时，Token 可能交错：

```text
product_analysis: Token 1
knowledge_qa: Token 1
product_analysis: Token 2
policy_query: Node completed
knowledge_qa: Token 2
```

因此每个流事件必须包含：

```text
nodeId
agentName
eventType
sequence
```

前端不能仅按到达顺序把所有 Token 拼成一段文本。

推荐：

* 子 Agent 不直接向最终聊天区输出。
* 子 Agent Token 只用于调试或进度展示。
* 最终 Summary Agent 的 Token 才输出到最终回答区域。

---

# 当前保险产品管理智能体推荐 State

## 1. 状态字段

```text
userQuery                    Replace
rewriteQuery                 Replace
conversationContext          Replace

intentResult                 Replace
needsProductRecall           Replace

productCandidates            Replace
confirmedProducts            Replace
humanConfirmation            Replace

executionPlan                Replace

productAnalysisResult        Replace
knowledgeResult              Replace
policyResult                 Replace
assetResult                  Replace

nodeErrors                   Append
warnings                     Append
completedNodes               Append
skippedNodes                 Append

aggregatedResult             Replace
reviewResult                 Replace
finalAnswer                  Replace

messages                     Append
```

---

## 2. KeyStrategyFactory

```java
@Bean
public KeyStrategyFactory insuranceKeyStrategyFactory() {

    return () -> {
        Map<String, KeyStrategy> strategies =
                new HashMap<>();

        strategies.put(
                InsuranceStateKeys.USER_QUERY,
                new ReplaceStrategy());

        strategies.put(
                InsuranceStateKeys.REWRITE_QUERY,
                new ReplaceStrategy());

        strategies.put(
                InsuranceStateKeys.INTENT_RESULT,
                new ReplaceStrategy());

        strategies.put(
                InsuranceStateKeys.PRODUCT_CANDIDATES,
                new ReplaceStrategy());

        strategies.put(
                InsuranceStateKeys.CONFIRMED_PRODUCTS,
                new ReplaceStrategy());

        strategies.put(
                InsuranceStateKeys.PRODUCT_ANALYSIS_RESULT,
                new ReplaceStrategy());

        strategies.put(
                InsuranceStateKeys.KNOWLEDGE_RESULT,
                new ReplaceStrategy());

        strategies.put(
                InsuranceStateKeys.POLICY_RESULT,
                new ReplaceStrategy());

        strategies.put(
                InsuranceStateKeys.ASSET_RESULT,
                new ReplaceStrategy());

        strategies.put(
                InsuranceStateKeys.NODE_ERRORS,
                new AppendStrategy());

        strategies.put(
                InsuranceStateKeys.FINAL_ANSWER,
                new ReplaceStrategy());

        strategies.put(
                "messages",
                new AppendStrategy());

        return strategies;
    };
}
```

---

## 3. 推荐 Graph

```text
START
  ↓
input_review
  ↓
context_alignment
  ↓
intent_recognition
  ↓
product_recall_decision
  ↓
需要召回产品？
  ├── 是
  │    ↓
  │ product_retrieval
  │    ↓
  │ product_confirmation
  │    ↓
  │ execution_planning
  │
  └── 否
       ↓
     execution_planning
       ↓
 ┌─────┼────────┬────────┐
 ↓     ↓        ↓        ↓
产品   知识     保单     资产
分析   问答     查询     查询
 └─────┼────────┴────────┘
       ↓
result_aggregation
       ↓
output_review
       ↓
summary
       ↓
END
```

---

# Codex 开发强制规则

Codex 修改 Graph Core 代码时必须遵守：

* [ ] 先核对本地 `1.1.2.3-sources.jar`。
* [ ] 不直接复制官网中存在拼写或变量使用问题的示例。
* [ ] 所有 State Key 统一定义为常量。
* [ ] 每个 Key 明确配置 Replace、Append 或自定义策略。
* [ ] Node 只返回本节点产生的增量状态。
* [ ] 使用 AppendStrategy 时不返回旧值加新值的完整列表。
* [ ] 并行节点不得写入同一个 Replace Key。
* [ ] Edge 只做路由，不做模型调用和外部查询。
* [ ] 可信身份信息通过 RunnableConfig 传递。
* [ ] `conversationId` 与 `threadId` 保持一致或明确映射。
* [ ] 生产环境不使用 MemorySaver。
* [ ] 聊天记录、Checkpoint 和长期 Store 分开存储。
* [ ] State 中只放可序列化、后续确实需要的数据。
* [ ] 自定义 DTO 必须测试序列化和反序列化。
* [ ] Replay 后可能再次执行的写操作必须幂等。
* [ ] `updateState` 必须考虑对应 KeyStrategy。
* [ ] 前端不能自行指定任意恢复节点。
* [ ] Flux 由 Graph 或 WebFlux 统一订阅。
* [ ] 节点内部不得对返回给 Graph 的 Flux 再次订阅。
* [ ] SSE 只输出白名单字段。
* [ ] 并行 Agent Token 必须携带 nodeId。
* [ ] 最终用户回答只流式输出 Summary Agent 的内容。
* [ ] Thinking 内容默认不向生产前端展示。
* [ ] 客户端取消时必须清理模型流和临时资源。
* [ ] 对嵌套子图、AppendStrategy 和流式并行编写集成测试。

---

# 建议测试用例

## State 策略测试

```text
ReplaceStrategy 是否覆盖旧值
AppendStrategy 是否只追加增量
RemoveByHash 是否正确删除
自定义合并策略是否去重
```

## Graph 路由测试

```text
需要产品召回
不需要产品召回
只有一个意图
多个并行意图
关键节点失败
依赖节点失败
```

## Checkpoint 测试

```text
相同 threadId 恢复状态
不同 threadId 状态隔离
获取最新状态
获取状态历史
人工中断后恢复
updateState 后继续执行
Replay 后形成新分支
```

## Serializer 测试

```text
自定义 DTO
Spring AI Message
ToolCall
ToolResponse
枚举
null
嵌套集合
```

## Streaming 测试

```text
普通节点输出
LLM Token 输出
流式完成后下游读取完整结果
并行流节点
客户端主动断开
模型中途报错
SSE 最终完成事件
```
