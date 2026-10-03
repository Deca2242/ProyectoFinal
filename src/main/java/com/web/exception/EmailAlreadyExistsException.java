package com.web.exception;

import org.springframework.http.HttpStatus;

// Excepción lanzada cuando se intenta registrar un email que ya existe
public class EmailAlreadyExistsException extends BusinessException {
    public EmailAlreadyExistsException(String email) {
        super(String.format("El email '%s' ya está registrado", email),
              HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS");
    }
}

