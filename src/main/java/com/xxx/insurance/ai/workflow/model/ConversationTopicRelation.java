package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 当前问题与上一轮话题的关系。
 */
@Schema(description = "当前问题与上一轮话题的关系")
public enum ConversationTopicRelation {

    NO_HISTORY,

    CONTINUE,

    SWITCH
}
