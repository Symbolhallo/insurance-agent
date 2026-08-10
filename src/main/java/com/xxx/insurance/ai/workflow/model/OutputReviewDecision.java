package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 行内输出审核节点返回的发布决策。
 */
@Schema(description = "输出审核决策")
public enum OutputReviewDecision {

    /** 原候选答案可以直接发布。 */
    PASS,

    /** 行内节点已改写风险内容，应发布 publishableAnswer。 */
    REWRITE,

    /** 原答案被阻断，只允许发布行内节点返回的安全降级文案。 */
    BLOCK
}
