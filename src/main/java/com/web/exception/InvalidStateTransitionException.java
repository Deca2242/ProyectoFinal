package com.web.exception;

import org.springframework.http.HttpStatus;

// Excepción lanzada cuando se intenta cambiar a un estado no permitido
public class InvalidStateTransitionException extends BusinessException {
    public InvalidStateTransitionException(String message) {
        super(message, HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATE_TRANSITION");
    }

    public InvalidStateTransitionException(String currentState, String targetState) {
        super(String.format("No se puede cambiar del estado '%s' al estado '%s'", currentState, targetState),
              HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATE_TRANSITION");
    }
}

