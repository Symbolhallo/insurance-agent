package com.xxx.insurance.ai.workflow.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;

/**
 * 主工作流内部任务执行资源配置。
 */
@Configuration
public class WorkflowExecutionConfig {

    public static final String WORKFLOW_DAG_TASK_EXECUTOR = "workflowDagTaskExecutor";

    /**
     * 创建受 Spring 管理的有界线程池，供同一 DAG 波次中无依赖的子智能体并行执行。
     *
     * <p>线程数和队列均设置上限，避免模型接口变慢时无限创建线程或堆积任务；MDC
     * 会从 HTTP/Graph 线程复制到工作线程，使子智能体日志继续携带同一个 traceId。</p>
     */
    @Bean(name = WORKFLOW_DAG_TASK_EXECUTOR)
    public ThreadPoolTaskExecutor workflowDagTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("workflow-dag-");
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
