package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.mapper.ConversationManagementMapper;
import com.xxx.insurance.common.exception.BusinessException;
import com.xxx.insurance.common.exception.ErrorCode;
import com.xxx.insurance.common.security.RequestIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisConversationManagementServiceTests {

    private static final RequestIdentity IDENTITY = RequestIdentity.localDefault();

    private ConversationManagementMapper mapper;

    private MyBatisConversationManagementService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ConversationManagementMapper.class);
        service = new MyBatisConversationManagementService(mapper);
    }

    @Test
    void capsConversationListAtOneHundredItems() {
        when(mapper.findActiveConversations(IDENTITY, 100)).thenReturn(List.of());

        assertThat(service.listConversations(IDENTITY, 500)).isEmpty();

        verify(mapper).findActiveConversations(IDENTITY, 100);
    }

    @Test
    void archivesIdleConversationWithoutSecondaryLookup() {
        when(mapper.archiveConversation(eq(IDENTITY), eq("conversation-1"), any(Instant.class))).thenReturn(1);

        assertThat(service.archiveConversation(IDENTITY, "conversation-1")).isTrue();

        verify(mapper, never()).countActiveUsage(eq(IDENTITY), eq("conversation-1"), any(Instant.class));
    }

    @Test
    void treatsMissingOrAlreadyArchivedConversationAsIdempotentDelete() {
        when(mapper.archiveConversation(eq(IDENTITY), eq("conversation-1"), any(Instant.class))).thenReturn(0);
        when(mapper.countActiveUsage(eq(IDENTITY), eq("conversation-1"), any(Instant.class))).thenReturn(0);

        assertThat(service.archiveConversation(IDENTITY, "conversation-1")).isFalse();
    }

    @Test
    void rejectsArchiveWhileWorkflowStillOwnsConversation() {
        when(mapper.archiveConversation(eq(IDENTITY), eq("conversation-1"), any(Instant.class))).thenReturn(0);
        when(mapper.countActiveUsage(eq(IDENTITY), eq("conversation-1"), any(Instant.class))).thenReturn(1);

        assertThatThrownBy(() -> service.archiveConversation(IDENTITY, "conversation-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.WORKFLOW_STATE_CONFLICT));
    }
}
