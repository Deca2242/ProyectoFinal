package com.web.repository;

import com.web.entity.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;


import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConfigRepository Integration Tests")
class ConfigRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ConfigRepository configRepository;

    @BeforeEach
    void setUp() {
        entityManager.clear();
    }

    @Test
    @DisplayName("Debe encontrar configuración por clave")
    void shouldFindConfigByKey() {
        // Given
        Config config = Config.builder()
                .configKey("test.key")
                .configValue("test.value")
                .dataType(Config.DataType.STRING)
                .build();
        entityManager.persist(config);
        entityManager.flush();

        // When
        Optional<Config> result = configRepository.findByConfigKey("test.key");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getConfigValue()).isEqualTo("test.value");
    }

    @Test
    @DisplayName("Debe verificar si existe configuración por clave")
    void shouldCheckIfConfigExists() {
        // Given
        Config config = Config.builder()
                .configKey("existing.key")
                .configValue("value")
                .dataType(Config.DataType.STRING)
                .build();
        entityManager.persist(config);
        entityManager.flush();

        // When & Then
        assertThat(configRepository.existsByConfigKey("existing.key")).isTrue();
        assertThat(configRepository.existsByConfigKey("non.existing.key")).isFalse();
    }

    @Test
    @DisplayName("Debe retornar vacío cuando la clave no existe")
    void shouldReturnEmptyWhenKeyNotFound() {
        // When
        Optional<Config> result = configRepository.findByConfigKey("nonexistent.key");

        // Then
        assertThat(result).isEmpty();
    }
}
