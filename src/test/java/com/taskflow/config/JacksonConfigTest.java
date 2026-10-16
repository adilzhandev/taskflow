package com.taskflow.config;

import com.taskflow.task.CreateTaskRequest;
import com.taskflow.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JacksonConfigTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class);

    @Test
    void actualApplicationConfigurationAcceptsValidJson() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            CreateTaskRequest request = application.getBean(ObjectMapper.class).readValue(
                    "{\"title\":\"Study Java\",\"status\":\"TODO\"}", CreateTaskRequest.class);
            assertThat(request.title()).isEqualTo("Study Java");
            assertThat(request.status()).isEqualTo(TaskStatus.TODO);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"title\":123}", "{\"title\":1.5}", "{\"title\":true}",
            "{\"title\":\"Task\",\"ownerId\":2}", "{\"title\":\"Task\",\"status\":1}",
            "{\"title\":\"Task\",\"priority\":\"UNKNOWN\"}"
    })
    void actualApplicationConfigurationRejectsInvalidTypesAndUnknownFields(String json) {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            ObjectMapper mapper = application.getBean(ObjectMapper.class);
            assertThrows(JacksonException.class, () -> mapper.readValue(json, CreateTaskRequest.class));
        });
    }
}
