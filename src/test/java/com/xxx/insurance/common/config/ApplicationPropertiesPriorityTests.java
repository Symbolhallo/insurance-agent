package com.xxx.insurance.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationPropertiesPriorityTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void baseConfigurationLoadsFromTheSinglePropertiesSource() {
        contextRunner.run(context -> {
            ConfigurableEnvironment environment = (ConfigurableEnvironment) context.getEnvironment();
            assertThat(environment.getPropertySources().stream()
                    .map(source -> source.getName())
                    .anyMatch(name -> name.contains("application.properties")))
                    .isTrue();
            assertThat(environment.getPropertySources().stream()
                    .map(source -> source.getName())
                    .noneMatch(name -> name.contains("application.yml")))
                    .isTrue();
            assertThat(environment.getProperty("spring.application.name")).isEqualTo("insurance-agent");
            assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8080);
        });
    }

    @Test
    void localDbPropertiesClearDefaultDatabaseExclusions() {
        contextRunner.withPropertyValues("spring.profiles.active=local-db").run(context -> {
            ConfigurableEnvironment environment = (ConfigurableEnvironment) context.getEnvironment();
            assertThat(environment.getPropertySources().stream()
                    .map(source -> source.getName())
                    .anyMatch(name -> name.contains("application-local-db.properties")))
                    .isTrue();
            assertThat(environment.getPropertySources().stream()
                    .map(source -> source.getName())
                    .noneMatch(name -> name.contains("application-local-db.yml")))
                    .isTrue();
            assertThat(environment.getProperty("spring.autoconfigure.exclude")).isEmpty();
            assertThat(environment.getProperty("spring.datasource.driver-class-name"))
                    .isEqualTo("com.mysql.cj.jdbc.Driver");
            assertThat(environment.getProperty("insurance.ai.workflow.sse.event-retention"))
                    .isEqualTo("10m");
        });
    }

}
