package com.xxx.insurance.ai.memory.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.memory.mapper.ChatMemoryMapper;
import com.xxx.insurance.ai.memory.model.ChatMemoryMessageRecord;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MyBatisChatMemoryRepositoryTests {

    @Test
    void saveAllReplacesConversationWindowAndPreservesMessageOrder() {
        InMemoryChatMemoryMapper mapper = new InMemoryChatMemoryMapper();
        MyBatisChatMemoryRepository repository = new MyBatisChatMemoryRepository(mapper, new ObjectMapper());

        repository.saveAll("conversation-001", List.of(
                UserMessage.builder()
                        .text("first question")
                        .metadata(Map.of("source", "test"))
                        .build(),
                AssistantMessage.builder()
                        .content("first answer")
                        .build()));
        repository.saveAll("conversation-001", List.of(
                UserMessage.builder()
                        .text("second question")
                        .build()));

        List<Message> messages = repository.findByConversationId("conversation-001");

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst()).isInstanceOf(UserMessage.class);
        assertThat(messages.getFirst().getText()).isEqualTo("second question");
        assertThat(mapper.records)
                .extracting(ChatMemoryMessageRecord::messageOrder)
                .containsExactly(0);
    }

    private static class InMemoryChatMemoryMapper implements ChatMemoryMapper {

        private final List<ChatMemoryMessageRecord> records = new ArrayList<>();

        @Override
        public List<String> findConversationIds() {
            return records.stream()
                    .map(ChatMemoryMessageRecord::conversationId)
                    .distinct()
                    .sorted()
                    .toList();
        }

        @Override
        public List<ChatMemoryMessageRecord> findByConversationId(String conversationId) {
            return records.stream()
                    .filter(record -> record.conversationId().equals(conversationId))
                    .sorted((left, right) -> Integer.compare(left.messageOrder(), right.messageOrder()))
                    .toList();
        }

        @Override
        public void insert(ChatMemoryMessageRecord record) {
            records.add(record);
        }

        @Override
        public void deleteByConversationId(String conversationId) {
            records.removeIf(record -> record.conversationId().equals(conversationId));
        }
    }
}
