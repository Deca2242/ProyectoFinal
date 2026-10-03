package com.web.exception;

import org.springframework.http.HttpStatus;

// Excepción lanzada cuando las credenciales de autenticación son incorrectas
public class InvalidCredentialsException extends BusinessException {
    public InvalidCredentialsException() {
        super("Credenciales inválidas", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
    }

    public InvalidCredentialsException(String message) {
        super(message, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
    }
}

