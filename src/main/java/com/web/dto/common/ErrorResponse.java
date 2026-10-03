package com.web.dto.common;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

// Para manejo de errores consistente
public record ErrorResponse(
    Integer status,
    String error,
    String message,
    LocalDateTime timestamp,
    Map<String, String> validationErrors  // Campo->mensaje
) implements Serializable {}

