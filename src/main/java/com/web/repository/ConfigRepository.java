package com.web.repository;

import com.web.entity.Config;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConfigRepository extends JpaRepository<Config, Long> {

    Optional<Config> findByConfigKey(String configKey);

    boolean existsByConfigKey(String configKey);
}
