package com.web.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

// Excepción base para errores de negocio con código HTTP y código de error personalizado
@Getter
public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    // Errores de negocio
    public BusinessException(String message, HttpStatus status, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    // Errores tecnicos
    public BusinessException(String message, HttpStatus status, String code, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }
}
