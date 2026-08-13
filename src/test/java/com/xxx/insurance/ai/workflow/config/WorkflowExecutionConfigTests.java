package com.xxx.insurance.ai.workflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowExecutionConfigTests {

    @Test
    void startsConcurrentSseRunsWithoutWaitingInApplicationQueue() throws InterruptedException {
        ThreadPoolTaskExecutor executor = new WorkflowExecutionConfig().workflowSseTaskExecutor();
        CountDownLatch started = new CountDownLatch(5);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int index = 0; index < 5; index++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
        }
        finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void rejectsSseRunImmediatelyWhenAllExecutionSlotsAreOccupied() throws InterruptedException {
        ThreadPoolTaskExecutor executor = new WorkflowExecutionConfig().workflowSseTaskExecutor();
        CountDownLatch started = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int index = 0; index < 8; index++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> executor.execute(() -> { }))
                    .isInstanceOf(RejectedExecutionException.class);
        }
        finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void isolatesMaintenanceTasksFromTokenFlushScheduler() throws InterruptedException {
        ThreadPoolTaskScheduler scheduler = new WorkflowExecutionConfig().workflowMaintenanceTaskScheduler();
        scheduler.initialize();
        try {
            CountDownLatch executed = new CountDownLatch(1);
            AtomicReference<String> threadName = new AtomicReference<>();

            scheduler.execute(() -> {
                threadName.set(Thread.currentThread().getName());
                executed.countDown();
            });

            assertThat(executed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).startsWith("workflow-maintenance-");
        }
        finally {
            scheduler.shutdown();
        }
    }
}
