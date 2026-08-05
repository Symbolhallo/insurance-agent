# ProductAnalysisAgent DeepSeek Manual Test

本文档用于本地 IDEA + Swagger UI 真实联调 `ProductAnalysisAgent`。

## 前置条件

- IDEA 使用 Java 21 运行项目。
- `AI_API_KEY` 配置为你的 DeepSeek API Key。
- `AI_BASE_URL=https://api.deepseek.com`
- `AI_MODEL=deepseek-chat`
- 环境变量必须配置在 IDEA Run Configuration 的 `Environment variables`，不要配置到 `Active profiles`。

## 启动后检查

打开 Swagger UI：

```text
http://localhost:8080/swagger-ui.html
```

先调用：

```text
GET /api/v1/ai/model/status
```

期望：

- `success=true`
- `data.baseUrl=https://api.deepseek.com`
- `data.model=deepseek-chat`
- `data.apiKeyConfigured=true`
- `data.apiKeyMasked` 只显示脱敏值
- `data.skillCount=2`
- `data.tools` 包含 `product_analysis`

## 单产品分析

调用：

```text
POST /api/v1/product-analysis-agent/chat
```

请求体：

```json
{
  "message": "请分析 PA-001 是否适合35岁家庭经济支柱做长期保障规划，重点关注保障责任、适配性和风险提示。",
  "conversationId": "manual-single-001"
}
```

期望：

- HTTP 状态码为 `200`。
- 响应体外层为统一 `ApiResponse`。
- `data.modelInvoked=true`。
- 回答应包含以下 Markdown 小标题：
  - `## 分析结论`
  - `## 产品事实`
  - `## 适配分析`
  - `## 风险提示`
  - `## 后续建议`
- 回答中的产品事实应来自 Mock 产品库。
- 回答不得承诺收益。

## 多产品对比

调用：

```text
POST /api/v1/product-analysis-agent/chat
```

请求体：

```json
{
  "message": "请比较 PA-001、PA-002、PA-003 三款产品，客户是35岁家庭经济支柱，关注长期保障、健康风险和养老现金流。",
  "conversationId": "manual-batch-001"
}
```

期望：

- HTTP 状态码为 `200`。
- `data.modelInvoked=true`。
- 回答应包含以下 Markdown 小标题：
  - `## 对比结论`
  - `## 产品对比表`
  - `## 适配排序`
  - `## 关键风险`
  - `## 后续建议`
- 对比表中应包含工具返回的产品信息。
- 若排序依据不足，应明确说明，不得强行推荐。

## 异常场景

请求体：

```json
{
  "message": " ",
  "conversationId": "manual-invalid-001"
}
```

期望：

- HTTP 状态码为 `400`。
- `success=false`
- `code=COMMON-400`
- 响应头包含 `X-Trace-Id`

## 日志观察

日志中关注以下标识：

- `[Agent]`
- `[Tool]`
- `traceId`

若模型正确触发工具调用，应能看到 `ProductAnalysisTool` 输出的 `[Tool]` 日志。
