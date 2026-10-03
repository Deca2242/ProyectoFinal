package com.web.exception;

import org.springframework.http.HttpStatus;

// Excepción lanzada cuando no se encuentra un recurso solicitado (por ID o identificador)
public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    public ResourceNotFoundException(String resource, Long id) {
        super(String.format("%s con id %d no encontrado", resource, id), 
              HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    public ResourceNotFoundException(String resource, String identifier) {
        super(String.format("%s con identificador '%s' no encontrado", resource, identifier), 
              HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}

