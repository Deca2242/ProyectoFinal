package com.web.exception;

import com.web.dto.common.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Maneja excepciones cuando no se encuentra un recurso (404)
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                ex.getStatus().value(),
                ex.getStatus().getReasonPhrase(),
                ex.getMessage(),
                LocalDateTime.now(),
                null);
        return ResponseEntity.status(ex.getStatus()).body(error);
    }

    // Maneja excepciones cuando un asiento no está disponible (409)
    @ExceptionHandler(SeatNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleSeatNotAvailable(SeatNotAvailableException ex) {
        ErrorResponse error = new ErrorResponse(
                ex.getStatus().value(),
                ex.getStatus().getReasonPhrase(),
                ex.getMessage(),
                LocalDateTime.now(),
                null);
        return ResponseEntity.status(ex.getStatus()).body(error);
    }

    // Maneja excepciones cuando un tramo de viaje no es válido (400)
    @ExceptionHandler(InvalidSegmentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSegment(InvalidSegmentException ex) {
        ErrorResponse error = new ErrorResponse(
                ex.getStatus().value(),
                ex.getStatus().getReasonPhrase(),
                ex.getMessage(),
                LocalDateTime.now(),
                null);
        return ResponseEntity.status(ex.getStatus()).body(error);
    }

    // Maneja excepciones cuando se excede el límite de overbooking (403)
    @ExceptionHandler(OverbookingNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleOverbookingNotAllowed(OverbookingNotAllowedException ex) {
        ErrorResponse error = new ErrorResponse(
                ex.getStatus().value(),
                ex.getStatus().getReasonPhrase(),
                ex.getMessage(),
                LocalDateTime.now(),
                null);
        return ResponseEntity.status(ex.getStatus()).body(error);
    }

    // Maneja excepciones cuando una transición de estado no es permitida (422)
    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidStateTransition(InvalidStateTransitionException ex) {
        ErrorResponse error = new ErrorResponse(
                ex.getStatus().value(),
                ex.getStatus().getReasonPhrase(),
                ex.getMessage(),
                LocalDateTime.now(),
                null);
        return ResponseEntity.status(ex.getStatus()).body(error);
    }

    // Maneja excepciones cuando se intenta registrar un email duplicado (409)
    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        ErrorResponse error = new ErrorResponse(
                ex.getStatus().value(),
                ex.getStatus().getReasonPhrase(),
                ex.getMessage(),
                LocalDateTime.now(),
                null);
        return ResponseEntity.status(ex.getStatus()).body(error);
    }

    // Maneja excepciones cuando las credenciales de autenticación son inválidas
    // (401)
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        ErrorResponse error = new ErrorResponse(
                ex.getStatus().value(),
                ex.getStatus().getReasonPhrase(),
                ex.getMessage(),
                LocalDateTime.now(),
                null);
        return ResponseEntity.status(ex.getStatus()).body(error);
    }

    // Maneja cualquier excepción de negocio genérica
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        ErrorResponse error = new ErrorResponse(
                ex.getStatus().value(),
                ex.getStatus().getReasonPhrase(),
                ex.getMessage(),
                LocalDateTime.now(),
                null);
        return ResponseEntity.status(ex.getStatus()).body(error);
    }

    // Maneja errores de validación de datos (cuando @Valid falla) (400)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Error de validación en los datos enviados",
                LocalDateTime.now(),
                errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // Maneja accesos denegados por @PreAuthorize (403)
    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ErrorResponse> handleAccessDenied(Exception ex) {
        return buildError(HttpStatus.FORBIDDEN, "No tienes los permisos suficientes para realizar esta acción.");
    }

    // Maneja cuerpos JSON mal formados o con valores inválidos (400)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        return buildError(HttpStatus.BAD_REQUEST, "El cuerpo de la petición no es válido o está mal formado");
    }

    // Maneja parámetros de URL o query con tipo incorrecto o faltantes (400)
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleBadParameter(Exception ex) {
        return buildError(HttpStatus.BAD_REQUEST, "Parámetro inválido o faltante en la petición");
    }

    // Maneja violaciones de restricciones de la base de datos (409)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return buildError(HttpStatus.CONFLICT, "La operación viola una restricción de integridad de los datos");
    }

    // Maneja cualquier excepción no capturada (error genérico del servidor) (500)
    // No se expone ex.getMessage() porque puede contener SQL o detalles internos
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Error no controlado", ex);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor");
    }

    private ResponseEntity<ErrorResponse> buildError(HttpStatus status, String message) {
        ErrorResponse error = new ErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                message,
                LocalDateTime.now(),
                null);
        return ResponseEntity.status(status).body(error);
    }
}
