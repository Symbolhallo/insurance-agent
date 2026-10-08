package com.xxx.insurance.ai.agent;

import java.util.List;

/** 可选的领域回答合同检查结果；未配置检查器时使用 {@link #notInspected()}。 */
public record ConversationAgentOutputInspection(Boolean valid, List<String> missingSections) {

    public ConversationAgentOutputInspection {
        missingSections = missingSections == null ? List.of() : List.copyOf(missingSections);
    }

    /** 表示当前领域没有额外的回答格式合同。 */
    public static ConversationAgentOutputInspection notInspected() {
        return new ConversationAgentOutputInspection(null, List.of());
    }
}
