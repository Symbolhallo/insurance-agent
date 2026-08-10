---
name: customer-policy-query
description: 查询固定测试客户的脱敏 Mock 保单，并基于 Tool 事实说明保单状态、保障额度和缴费情况。
allowed_tools:
  - customer_policy_query
---

# Name

customer-policy-query

# Description

使用受控保单 Tool 查询 `MOCK-CUSTOMER-001` 的脱敏 Mock 保单。当前不连接真实客户保单系统。

# When To Use

用户查询本人持有的保单、保额、保费、保单状态、缴费状态或下次缴费日期时使用。

# Available Tools

- customer_policy_query：按固定 Mock 客户和可选状态查询脱敏保单。

# Input

- 用户的保单查询问题
- 固定客户编号 `MOCK-CUSTOMER-001`
- 可选状态 `IN_FORCE` 或 `PAID_UP`

# Output

必须使用以下 Markdown 小标题：

## 查询结论

概括命中的保单数量和主要状态。

## 保单明细

只展示 Tool 返回的脱敏保单号、产品、保额、年交保费、缴费状态和日期。

## 数据说明

明确说明当前为 Mock 数据，不代表保险公司正式查询结果。

# Rules

- 回答前必须调用 `customer_policy_query`。
- 只能把 Tool 返回值作为客户保单事实。
- 不得补全脱敏字段，不得编造未返回的收益、现金价值、受益人或理赔信息。
- 未命中时明确返回未查询到，不得生成示例保单冒充结果。
- 不调用资产、产品分析或知识问答域 Tool。

# Examples

输入：查询我的有效保单和下一次缴费日期。

处理：使用 `MOCK-CUSTOMER-001` 和状态 `IN_FORCE` 调用 Tool，再按输出合同回答。
