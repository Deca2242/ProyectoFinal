package com.web.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Tareas programadas (expiración de holds, no-show, aviso de llegada próxima...). Activas por defecto;
// app.scheduling.enabled=false las apaga, p. ej. en una réplica extra o en pruebas que las disparan a mano
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
