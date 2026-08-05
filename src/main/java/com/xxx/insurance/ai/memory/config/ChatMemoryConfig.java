package com.xxx.insurance.ai.memory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.memory.mapper.ChatMemoryMapper;
import com.xxx.insurance.ai.memory.repository.MyBatisChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * ChatMemory 配置。
 *
 * <p>该配置只在 {@code local-db} profile 下启用。默认启动和单元测试不强依赖本地数据库，
 * 本地联调 Memory 时再通过 OceanBase/MySQL 协议启用 JDBC 存储。</p>
 */
@Configuration
@Profile("local-db")
public class ChatMemoryConfig {

    @Bean
    public ChatMemoryRepository chatMemoryRepository(ChatMemoryMapper chatMemoryMapper, ObjectMapper objectMapper) {
        return new MyBatisChatMemoryRepository(chatMemoryMapper, objectMapper);
    }

    @Bean
    public ChatMemory chatMemory(
            ChatMemoryRepository chatMemoryRepository,
            @Value("${insurance.ai.memory.max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
    }
}
