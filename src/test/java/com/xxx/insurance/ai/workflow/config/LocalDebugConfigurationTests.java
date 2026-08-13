package com.xxx.insurance.ai.workflow.config;

import com.xxx.insurance.ai.workflow.sse.config.WorkflowSseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDebugConfigurationTests {

    @Test
    void bindsBreakpointFriendlyWorkflowDurations() throws Exception {
        Binder binder = binder("application-debug-timing.yml");

        WorkflowSseProperties sse = binder.bind(
                "insurance.ai.workflow.sse", Bindable.of(WorkflowSseProperties.class))
                .orElseThrow(() -> new IllegalStateException("Missing debug SSE properties"));
        WorkflowLifecycleProperties lifecycle = binder.bind(
                "insurance.ai.workflow.lifecycle", Bindable.of(WorkflowLifecycleProperties.class))
                .orElseThrow(() -> new IllegalStateException("Missing debug lifecycle properties"));

        assertThat(sse.connectionTimeout()).isEqualTo(Duration.ofHours(4));
        assertThat(sse.eventRetention()).isEqualTo(Duration.ofHours(4));
        assertThat(sse.databasePollInterval()).isEqualTo(Duration.ofMillis(500));
        assertThat(lifecycle.getExecutionLease()).isEqualTo(Duration.ofHours(4));
        assertThat(lifecycle.getClaimLease()).isEqualTo(Duration.ofHours(4));
        assertThat(lifecycle.getWaitingConfirmLease()).isEqualTo(Duration.ofDays(7));
        assertThat(lifecycle.getHeartbeatInterval()).isEqualTo(Duration.ofMinutes(5));
        lifecycle.validate();
    }

    @Test
    void localDebugProfileIncludesDatabaseBeforeTimingOverrides() throws Exception {
        PropertySource<?> source = load("application.yml").getFirst();

        assertThat(source.getProperty("spring.profiles.group.local-debug[0]")).isEqualTo("local-db");
        assertThat(source.getProperty("spring.profiles.group.local-debug[1]")).isEqualTo("debug-timing");
    }

    private Binder binder(String location) throws Exception {
        return new Binder(ConfigurationPropertySources.from(load(location)));
    }

    private List<PropertySource<?>> load(String location) throws Exception {
        return new YamlPropertySourceLoader().load(location, new ClassPathResource(location));
    }
}
