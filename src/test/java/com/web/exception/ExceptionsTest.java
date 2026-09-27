package com.web.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionsTest {

    @Test
    void shouldCreateBusinessException_WithMessageStatusAndCode() {
        // When
        BusinessException ex = new BusinessException("Error de negocio", HttpStatus.BAD_REQUEST, "BUSINESS_ERROR");

        // Then
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("Error de negocio");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getCode()).isEqualTo("BUSINESS_ERROR");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void shouldCreateBusinessException_WithCause() {
        // Given
        IOException cause = new IOException("disco lleno");

        // When
        BusinessException ex = new BusinessException("Error técnico", HttpStatus.INTERNAL_SERVER_ERROR,
                "TECHNICAL_ERROR", cause);

        // Then
        assertThat(ex.getMessage()).isEqualTo("Error técnico");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ex.getCode()).isEqualTo("TECHNICAL_ERROR");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void shouldCreateEmailAlreadyExistsException() {
        // When
        EmailAlreadyExistsException ex = new EmailAlreadyExistsException("ana@test.com");

        // Then
        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(ex.getMessage()).isEqualTo("El email 'ana@test.com' ya está registrado");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getCode()).isEqualTo("EMAIL_ALREADY_EXISTS");
    }

    @Test
    void shouldCreateInvalidCredentialsException_WithDefaultMessage() {
        // When
        InvalidCredentialsException ex = new InvalidCredentialsException();

        // Then
        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(ex.getMessage()).isEqualTo("Credenciales inválidas");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getCode()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void shouldCreateInvalidCredentialsException_WithCustomMessage() {
        // When
        InvalidCredentialsException ex = new InvalidCredentialsException("Usuario inactivo");

        // Then
        assertThat(ex.getMessage()).isEqualTo("Usuario inactivo");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getCode()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void shouldCreateInvalidSegmentException_WithMessage() {
        // When
        InvalidSegmentException ex = new InvalidSegmentException("Tramo inválido");

        // Then
        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(ex.getMessage()).isEqualTo("Tramo inválido");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getCode()).isEqualTo("INVALID_SEGMENT");
    }

    @Test
    void shouldCreateInvalidSegmentException_WithStops() {
        // When
        InvalidSegmentException ex = new InvalidSegmentException("Santa Marta", "Ciénaga");

        // Then
        assertThat(ex.getMessage()).isEqualTo("El tramo de 'Santa Marta' a 'Ciénaga' no es válido");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getCode()).isEqualTo("INVALID_SEGMENT");
    }

    @Test
    void shouldCreateInvalidStateTransitionException_WithMessage() {
        // When
        InvalidStateTransitionException ex = new InvalidStateTransitionException("Transición no permitida");

        // Then
        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(ex.getMessage()).isEqualTo("Transición no permitida");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getCode()).isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void shouldCreateInvalidStateTransitionException_WithStates() {
        // When
        InvalidStateTransitionException ex = new InvalidStateTransitionException("DELIVERED", "IN_TRANSIT");

        // Then
        assertThat(ex.getMessage()).isEqualTo("No se puede cambiar del estado 'DELIVERED' al estado 'IN_TRANSIT'");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getCode()).isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void shouldCreateOverbookingNotAllowedException_WithDefaultMessage() {
        // When
        OverbookingNotAllowedException ex = new OverbookingNotAllowedException();

        // Then
        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(ex.getMessage()).isEqualTo("El viaje ha alcanzado el límite de overbooking permitido");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getCode()).isEqualTo("OVERBOOKING_NOT_ALLOWED");
    }

    @Test
    void shouldCreateOverbookingNotAllowedException_WithCustomMessage() {
        // When
        OverbookingNotAllowedException ex = new OverbookingNotAllowedException("Supera el 5%");

        // Then
        assertThat(ex.getMessage()).isEqualTo("Supera el 5%");
        assertThat(ex.getCode()).isEqualTo("OVERBOOKING_NOT_ALLOWED");
    }

    @Test
    void shouldCreateResourceNotFoundException_WithMessage() {
        // When
        ResourceNotFoundException ex = new ResourceNotFoundException("No existe");

        // Then
        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(ex.getMessage()).isEqualTo("No existe");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getCode()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void shouldCreateResourceNotFoundException_WithResourceAndId() {
        // When
        ResourceNotFoundException ex = new ResourceNotFoundException("Viaje", 42L);

        // Then
        assertThat(ex.getMessage()).isEqualTo("Viaje con id 42 no encontrado");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getCode()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void shouldCreateResourceNotFoundException_WithResourceAndIdentifier() {
        // When
        ResourceNotFoundException ex = new ResourceNotFoundException("Encomienda", "PCL-123");

        // Then
        assertThat(ex.getMessage()).isEqualTo("Encomienda con identificador 'PCL-123' no encontrado");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getCode()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void shouldCreateSeatNotAvailableException_WithMessage() {
        // When
        SeatNotAvailableException ex = new SeatNotAvailableException("Asiento ocupado");

        // Then
        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(ex.getMessage()).isEqualTo("Asiento ocupado");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getCode()).isEqualTo("SEAT_NOT_AVAILABLE");
    }

    @Test
    void shouldCreateSeatNotAvailableException_WithSeatAndTrip() {
        // When
        SeatNotAvailableException ex = new SeatNotAvailableException(12, 7L);

        // Then
        assertThat(ex.getMessage()).isEqualTo("El asiento 12 no está disponible para el viaje 7");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getCode()).isEqualTo("SEAT_NOT_AVAILABLE");
    }
}
