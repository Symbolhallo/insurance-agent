package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 产品候选召回判断类型。
 */
@Schema(description = "产品候选召回判断类型")
public enum ProductRecallTrigger {

    FIRST_EXPLICIT_PRODUCT,

    FUZZY_PRODUCT,

    UNCONFIRMED_PRODUCT_FOLLOW_UP,

    CONFIRMED_PRODUCT,

    CONDITION_FILTER,

    NO_PRODUCT_MENTION,

    NON_PRODUCT_TOPIC
}
