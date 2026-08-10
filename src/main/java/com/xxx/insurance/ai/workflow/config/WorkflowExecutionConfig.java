package com.xxx.insurance.ai.workflow.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;

/**
 * 主工作流内部任务执行资源配置。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({WorkflowSseProperties.class, WorkflowLifecycleProperties.class})
public class WorkflowExecutionConfig {

    public static final String WORKFLOW_DAG_TASK_EXECUTOR = "workflowDagTaskExecutor";

    public static final String WORKFLOW_SSE_TASK_EXECUTOR = "workflowSseTaskExecutor";

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
        return createExecutor("workflow-sse-", 2, 8, 64);
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
