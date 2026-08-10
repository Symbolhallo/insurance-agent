---
name: policy-query-scaffold
description: 客户保单信息查询智能体的工程扩展边界，当前不提供真实保单查询能力。
---

# Name

policy-query-scaffold

# Description

定义客户保单查询 Agent 的能力边界和未来 Tool 接入要求，当前不实现客户保单业务逻辑。

# When To Use

未来接入经过身份认证和数据授权的客户保单查询微应用后使用。

# Available Tools

当前没有可用业务 Tool。

# Input

- 会话编号
- 经过授权的客户身份上下文
- 保单查询条件

# Output

返回经过权限校验和脱敏处理的保单查询结果。

# Rules

- 未接入受控 Tool 前不得生成或猜测客户保单数据。
- 客户身份、权限和审计信息必须由调用上下文提供。
- Skill 只能加载保单域 Tool，不得调用资产或产品域内部服务。

# Examples

当前阶段无业务执行示例。
