package com.web.exception;

import org.springframework.http.HttpStatus;

// Excepción lanzada cuando se intenta vender más tickets que el límite de overbooking permite
public class OverbookingNotAllowedException extends BusinessException {
    public OverbookingNotAllowedException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "OVERBOOKING_NOT_ALLOWED");
    }

    public OverbookingNotAllowedException() {
        super("El viaje ha alcanzado el límite de overbooking permitido",
              HttpStatus.BAD_REQUEST, "OVERBOOKING_NOT_ALLOWED");
    }
}

