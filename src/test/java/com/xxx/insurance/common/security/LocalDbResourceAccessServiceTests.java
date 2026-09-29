package com.xxx.insurance.common.security;

import com.xxx.insurance.common.exception.BusinessException;
import com.xxx.insurance.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalDbResourceAccessServiceTests {

    private static final RequestIdentity IDENTITY =
            new RequestIdentity("tenant-a", "user-a", "customer-a", "operator-a");

    private ResourceAccessMapper mapper;

    private LocalDbResourceAccessService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ResourceAccessMapper.class);
        service = new LocalDbResourceAccessService(mapper);
    }

    @Test
    void claimsConversationWithoutOverwritingTheExistingOwner() {
        when(mapper.countOwnedConversation(IDENTITY, "conversation-1")).thenReturn(1);

        service.claimConversation(IDENTITY, "conversation-1");

        verify(mapper).claimConversation(eq(IDENTITY), eq("conversation-1"), any(Instant.class));
        verify(mapper).countOwnedConversation(IDENTITY, "conversation-1");
    }

    @Test
    void hidesConversationOwnedByAnotherIdentity() {
        when(mapper.countOwnedConversation(IDENTITY, "conversation-1")).thenReturn(0);

        assertNotFound(() -> service.requireConversationAccess(IDENTITY, "conversation-1"));
    }

    @Test
    void hidesWorkflowOwnedByAnotherIdentity() {
        when(mapper.countOwnedWorkflow(IDENTITY, "workflow-1")).thenReturn(0);

        assertNotFound(() -> service.requireWorkflowAccess(IDENTITY, "workflow-1"));
    }

    private void assertNotFound(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
                    assertThat(exception.getMessage()).doesNotContain("tenant-a", "user-a");
                });
    }
}
