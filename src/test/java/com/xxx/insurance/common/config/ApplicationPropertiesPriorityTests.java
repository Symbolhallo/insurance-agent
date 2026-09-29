package com.xxx.insurance.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationPropertiesPriorityTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void propertiesResourceHasHigherPriorityThanRetainedYamlResource() {
        contextRunner.run(context -> {
            ConfigurableEnvironment environment = (ConfigurableEnvironment) context.getEnvironment();
            List<String> sourceNames = new ArrayList<>();
            environment.getPropertySources().forEach(source -> sourceNames.add(source.getName()));

            int propertiesIndex = indexContaining(sourceNames, "application.properties");
            int yamlIndex = indexContaining(sourceNames, "application.yml");

            assertThat(propertiesIndex).isGreaterThanOrEqualTo(0);
            assertThat(yamlIndex).isGreaterThanOrEqualTo(0);
            assertThat(propertiesIndex).isLessThan(yamlIndex);
            assertThat(environment.getProperty("spring.application.name")).isEqualTo("insurance-agent");
            assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8080);
        });
    }

    @Test
    void localDbPropertiesClearDefaultDatabaseExclusions() {
        contextRunner.withPropertyValues("spring.profiles.active=local-db").run(context -> {
            ConfigurableEnvironment environment = (ConfigurableEnvironment) context.getEnvironment();
            List<String> sourceNames = new ArrayList<>();
            environment.getPropertySources().forEach(source -> sourceNames.add(source.getName()));

            int propertiesIndex = indexContaining(sourceNames, "application-local-db.properties");
            int yamlIndex = indexContaining(sourceNames, "application-local-db.yml");

            assertThat(propertiesIndex).isGreaterThanOrEqualTo(0);
            assertThat(yamlIndex).isGreaterThanOrEqualTo(0);
            assertThat(propertiesIndex).isLessThan(yamlIndex);
            assertThat(environment.getProperty("spring.autoconfigure.exclude")).isEmpty();
            assertThat(environment.getProperty("spring.datasource.driver-class-name"))
                    .isEqualTo("com.mysql.cj.jdbc.Driver");
            assertThat(environment.getProperty("insurance.ai.workflow.sse.event-retention"))
                    .isEqualTo("10m");
        });
    }

    private int indexContaining(List<String> sourceNames, String resourceName) {
        for (int index = 0; index < sourceNames.size(); index++) {
            if (sourceNames.get(index).contains(resourceName)) {
                return index;
            }
        }
        return -1;
    }
}
