package com.xxx.insurance.ai.workflow.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * 主工作流内部任务执行资源配置。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({WorkflowSseProperties.class, WorkflowLifecycleProperties.class})
public class WorkflowExecutionConfig {

    public static final String WORKFLOW_DAG_TASK_EXECUTOR = "workflowDagTaskExecutor";

    public static final String WORKFLOW_SSE_TASK_EXECUTOR = "workflowSseTaskExecutor";

    public static final String WORKFLOW_TOKEN_FLUSH_SCHEDULER = "workflowTokenFlushScheduler";

    public static final String WORKFLOW_MAINTENANCE_TASK_SCHEDULER = "taskScheduler";

    /**
     * 创建受 Spring 管理的有界线程池，供动态 DAG 中同时 READY 的任务子图并行执行。
     *
     * <p>线程数和队列均设置上限，避免模型接口变慢时无限创建线程或堆积任务；MDC
     * 会从 HTTP/Graph 线程复制到工作线程，使子智能体日志继续携带同一个 traceId。</p>
     */
    @Bean(name = WORKFLOW_DAG_TASK_EXECUTOR)
    public ThreadPoolTaskExecutor workflowDagTaskExecutor() {
        return createExecutor("workflow-dag-", 2, 4, 32);
    }

    /**
     * 创建主工作流 SSE 后台执行线程池，使模型和 Graph 执行不占用 Servlet 请求线程。
     *
     * @return 供 WorkflowSseService 启动作业使用的有界线程池
     */
    @Bean(name = WORKFLOW_SSE_TASK_EXECUTOR)
    public ThreadPoolTaskExecutor workflowSseTaskExecutor() {
        // SSE 连接建立后不能在应用队列中静默等待：空队列使线程池直接扩容到 maxPoolSize，
        // 容量耗尽时快速拒绝，由入口立即关闭订阅并返回错误。
        return createExecutor("workflow-sse-", 2, 8, 0);
    }

    /**
     * 创建独立的 Token 批次刷新调度器。
     *
     * <p>首个模型块仍由调用线程立即写入和发送；该调度器只负责把后续尚未达到字符阈值的
     * 小块在最大等待时间内刷新，不占用 Lease 心跳、SSE 数据库轮询或清理任务线程。</p>
     */
    @Bean(name = WORKFLOW_TOKEN_FLUSH_SCHEDULER, destroyMethod = "shutdown")
    public ScheduledExecutorService workflowTokenFlushScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                2, new CustomizableThreadFactory("workflow-token-flush-"));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    /**
     * 创建 Spring {@code @Scheduled} 专用调度器，承载 SSE 数据库轮询、清理和租约任务。
     *
     * <p>Bean 名必须为 {@code taskScheduler}，使 Spring 不会把仅供 Token 合并使用的
     * {@link ScheduledExecutorService} 自动选为全局调度器。两类任务隔离后，耗时的数据库
     * 维护操作不会占用模型流的低延迟刷新线程。</p>
     */
    @Bean(name = WORKFLOW_MAINTENANCE_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler workflowMaintenanceTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("workflow-maintenance-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    /** 创建带 MDC 传播、优雅关闭和固定容量约束的工作流线程池。 */
    private ThreadPoolTaskExecutor createExecutor(String threadNamePrefix,
                                                  int corePoolSize,
                                                  int maxPoolSize,
                                                  int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(task -> {
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            return () -> {
                try {
                    if (contextMap != null) {
                        MDC.setContextMap(contextMap);
                    }
                    task.run();
                }
                finally {
                    MDC.clear();
                }
            };
        });
        executor.initialize();
        return executor;
    }
}
