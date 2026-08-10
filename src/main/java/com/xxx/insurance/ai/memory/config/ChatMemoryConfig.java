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

    /**
     * 创建基于 MyBatis 和 OceanBase 的 Spring AI ChatMemoryRepository。
     *
     * @param chatMemoryMapper 会话消息持久化 Mapper
     * @param objectMapper 消息元数据 JSON 编解码器
     * @return ChatMemory 使用的持久化仓库
     */
    @Bean
    public ChatMemoryRepository chatMemoryRepository(ChatMemoryMapper chatMemoryMapper, ObjectMapper objectMapper) {
        return new MyBatisChatMemoryRepository(chatMemoryMapper, objectMapper);
    }

    /**
     * 创建带窗口裁剪能力的全局 ChatMemory。
     *
     * @param chatMemoryRepository 本地数据库消息仓库
     * @param maxMessages 单个 conversationId 最多保留的上下文消息数
     * @return 子智能体和主工作流共享的窗口记忆组件
     */
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
