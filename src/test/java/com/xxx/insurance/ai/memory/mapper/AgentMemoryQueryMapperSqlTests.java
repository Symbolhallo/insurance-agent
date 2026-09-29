package com.xxx.insurance.ai.memory.mapper;

import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMemoryQueryMapperSqlTests {

    @Test
    void conversationQueryMapsEveryRecordConstructorArgumentInOrder() throws Exception {
        var method = AgentMemoryQueryMapper.class.getMethod("findConversation", String.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());
        var arguments = method.getAnnotation(ConstructorArgs.class).value();

        assertThat(sql).contains("conversation_id", "tenant_id", "user_id", "updated_at as occurred_at");
        assertThat(arguments)
                .extracting(argument -> argument.column())
                .containsExactly(
                        "conversation_id",
                        "tenant_id",
                        "user_id",
                        "customer_id",
                        "operator_id",
                        "session_type",
                        "agent_name",
                        "title",
                        "status",
                        "occurred_at");
    }
}
