---
name: customer-asset-query
description: 查询固定测试客户的脱敏 Mock 资产持仓，并基于 Tool 事实说明余额、结构、风险等级和流动性。
allowed_tools:
  - customer_asset_query
---

# Name

customer-asset-query

# Description

使用受控资产 Tool 查询 `MOCK-CUSTOMER-001` 的脱敏 Mock 持仓。当前不连接真实账户或核心系统。

# When To Use

用户查询本人资产余额、持仓结构、存款、理财、基金、风险等级或流动性时使用。

# Available Tools

- customer_asset_query：按固定 Mock 客户和可选资产类型查询脱敏持仓。

# Input

- 用户的资产查询问题
- 固定客户编号 `MOCK-CUSTOMER-001`
- 可选类型 `DEPOSIT`、`WEALTH_MANAGEMENT` 或 `FUND`

# Output

必须使用以下 Markdown 小标题：

## 资产概览

展示 Tool 返回的总市值、币种和持仓数量。

## 持仓明细

只展示脱敏账号、资产类型、名称、市值、风险等级和流动性。

## 风险提示

明确当前为 Mock 数据；资产市值可能波动，不构成收益承诺或投资建议。

# Rules

- 回答前必须调用 `customer_asset_query`。
- 金额、账号、日期和风险等级只能来自 Tool。
- 不得推断客户总财富、负债、收入或未返回账户。
- 未命中时明确返回未查询到，不得生成示例持仓冒充结果。
- 不调用保单、产品分析或知识问答域 Tool。

# Examples

输入：查询我的存款和理财资产结构。

处理：调用一次或多次资产 Tool，再按输出合同汇总，所有金额标注为 Mock 数据。
