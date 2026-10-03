package com.web.exception;

import com.web.dto.common.ErrorResponse;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    // Comprueba los campos comunes de cualquier ErrorResponse
    private void assertError(ResponseEntity<ErrorResponse> response, HttpStatus expected, String message) {
        assertThat(response.getStatusCode()).isEqualTo(expected);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(expected.value());
        assertThat(body.error()).isEqualTo(expected.getReasonPhrase());
        assertThat(body.message()).isEqualTo(message);
        assertThat(body.timestamp()).isNotNull().isBeforeOrEqualTo(LocalDateTime.now());
    }

    // Excepciones de negocio

    @Test
    void shouldHandleResourceNotFound_Return404WithMessage() {
        // Given
        ResourceNotFoundException ex = new ResourceNotFoundException("Viaje", 7L);

        // When
        ResponseEntity<ErrorResponse> response = handler.handleResourceNotFound(ex);

        // Then
        assertError(response, HttpStatus.NOT_FOUND, "Viaje con id 7 no encontrado");
        assertThat(response.getBody().validationErrors()).isNull();
    }

    @Test
    void shouldHandleSeatNotAvailable_Return409() {
        // Given
        SeatNotAvailableException ex = new SeatNotAvailableException(12, 3L);

        // When
        ResponseEntity<ErrorResponse> response = handler.handleSeatNotAvailable(ex);

        // Then
        assertError(response, HttpStatus.CONFLICT, ex.getMessage());
    }

    @Test
    void shouldHandleInvalidSegment_Return400() {
        // Given
        InvalidSegmentException ex = new InvalidSegmentException("Tunja", "Bogotá");

        // When
        ResponseEntity<ErrorResponse> response = handler.handleInvalidSegment(ex);

        // Then
        assertError(response, HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @Test
    void shouldHandleOverbookingNotAllowed_UseStatusOfException() {
        // Given: no se fija el código HTTP concreto, solo que se respeta el de la excepción
        OverbookingNotAllowedException ex = new OverbookingNotAllowedException();

        // When
        ResponseEntity<ErrorResponse> response = handler.handleOverbookingNotAllowed(ex);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(ex.getStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(ex.getStatus().value());
        assertThat(response.getBody().message()).isEqualTo(ex.getMessage());
    }

    @Test
    void shouldHandleInvalidStateTransition_Return422() {
        // Given
        InvalidStateTransitionException ex = new InvalidStateTransitionException("DEPARTED", "BOARDING");

        // When
        ResponseEntity<ErrorResponse> response = handler.handleInvalidStateTransition(ex);

        // Then
        assertError(response, HttpStatus.UNPROCESSABLE_ENTITY,
                "No se puede cambiar del estado 'DEPARTED' al estado 'BOARDING'");
    }

    @Test
    void shouldHandleEmailAlreadyExists_Return409() {
        // Given
        EmailAlreadyExistsException ex = new EmailAlreadyExistsException("ana@test.com");

        // When
        ResponseEntity<ErrorResponse> response = handler.handleEmailAlreadyExists(ex);

        // Then
        assertError(response, HttpStatus.CONFLICT, "El email 'ana@test.com' ya está registrado");
    }

    @Test
    void shouldHandleInvalidCredentials_Return401() {
        // Given
        InvalidCredentialsException ex = new InvalidCredentialsException();

        // When
        ResponseEntity<ErrorResponse> response = handler.handleInvalidCredentials(ex);

        // Then
        assertError(response, HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
    }

    @Test
    void shouldHandleGenericBusinessException_UseItsOwnStatus() {
        // Given: una BusinessException genérica con un estado arbitrario
        BusinessException ex = new BusinessException("La ruta tiene viajes", HttpStatus.CONFLICT, "ROUTE_HAS_TRIPS");

        // When
        ResponseEntity<ErrorResponse> response = handler.handleBusinessException(ex);

        // Then
        assertError(response, HttpStatus.CONFLICT, "La ruta tiene viajes");
    }

    // Errores de validación y de petición

    @Test
    void shouldHandleValidationErrors_Return400WithFieldErrors() throws Exception {
        // Given: dos campos con error de validación
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new SampleRequest(null, ""), "request");
        bindingResult.addError(new FieldError("request", "tripId", "no debe ser nulo"));
        bindingResult.addError(new FieldError("request", "name", "no debe estar vacío"));
        MethodParameter parameter = new MethodParameter(
                SampleController.class.getDeclaredMethod("create", SampleRequest.class), 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        // When
        ResponseEntity<ErrorResponse> response = handler.handleValidationExceptions(ex);

        // Then
        assertError(response, HttpStatus.BAD_REQUEST, "Error de validación en los datos enviados");
        assertThat(response.getBody().validationErrors())
                .containsEntry("tripId", "no debe ser nulo")
                .containsEntry("name", "no debe estar vacío")
                .hasSize(2);
    }

    @Test
    void shouldHandleAccessDenied_Return403() {
        // Given
        AccessDeniedException ex = new AccessDeniedException("Access Denied");

        // When
        ResponseEntity<ErrorResponse> response = handler.handleAccessDenied(ex);

        // Then
        assertError(response, HttpStatus.FORBIDDEN, "No tienes los permisos suficientes para realizar esta acción.");
    }

    @Test
    void shouldHandleAuthorizationDenied_Return403() {
        // Given: excepción que lanza @PreAuthorize en Spring Security 6
        AuthorizationDeniedException ex = new AuthorizationDeniedException("Access Denied", new AuthorizationDecision(false));

        // When
        ResponseEntity<ErrorResponse> response = handler.handleAccessDenied(ex);

        // Then
        assertError(response, HttpStatus.FORBIDDEN, "No tienes los permisos suficientes para realizar esta acción.");
    }

    @Test
    void shouldHandleMessageNotReadable_Return400() {
        // Given
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error", new ServletServerHttpRequest(new MockHttpServletRequest()));

        // When
        ResponseEntity<ErrorResponse> response = handler.handleMessageNotReadable(ex);

        // Then
        assertError(response, HttpStatus.BAD_REQUEST, "El cuerpo de la petición no es válido o está mal formado");
    }

    @Test
    void shouldHandleTypeMismatch_Return400() throws Exception {
        // Given
        MethodParameter parameter = new MethodParameter(
                SampleController.class.getDeclaredMethod("byId", Long.class), 0);
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "id", parameter, new NumberFormatException("abc"));

        // When
        ResponseEntity<ErrorResponse> response = handler.handleBadParameter(ex);

        // Then
        assertError(response, HttpStatus.BAD_REQUEST, "Parámetro inválido o faltante en la petición");
    }

    @Test
    void shouldHandleMissingRequestParameter_Return400() {
        // Given
        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("fromStopId", "Long");

        // When
        ResponseEntity<ErrorResponse> response = handler.handleBadParameter(ex);

        // Then
        assertError(response, HttpStatus.BAD_REQUEST, "Parámetro inválido o faltante en la petición");
    }

    @Test
    void shouldHandleDataIntegrityViolation_Return409WithoutSqlDetails() {
        // Given: el mensaje original contiene detalles de la base de datos
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uk_tickets_trip_seat\"");

        // When
        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(ex);

        // Then
        assertError(response, HttpStatus.CONFLICT, "La operación viola una restricción de integridad de los datos");
        assertThat(response.getBody().message()).doesNotContain("uk_tickets_trip_seat");
    }

    @Test
    void shouldHandleGenericException_Return500WithoutInternalMessage() {
        // Given: el mensaje interno no debe llegar al cliente
        RuntimeException ex = new IllegalStateException("SELECT * FROM users WHERE password = 'secreto'");

        // When
        ResponseEntity<ErrorResponse> response = handler.handleGenericException(ex);

        // Then
        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor");
        assertThat(response.getBody().message()).doesNotContain("SELECT", "secreto");
        assertThat(response.getBody().validationErrors()).isNull();
    }

    // Ruta inexistente, método no soportado, tipo de contenido no soportado

    @Test
    void shouldHandleNoResourceFound_Return404WithPath() {
        // Given
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "api/v1/nope");

        // When
        ResponseEntity<ErrorResponse> response = handler.handleNoResource(ex);

        // Then
        assertError(response, HttpStatus.NOT_FOUND, "Recurso no encontrado: /api/v1/nope");
        assertThat(response.getBody().validationErrors()).isNull();
    }

    @Test
    void shouldHandleMethodNotSupported_Return405WithMethod() {
        // Given
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST"));

        // When
        ResponseEntity<ErrorResponse> response = handler.handleMethodNotSupported(ex);

        // Then
        assertError(response, HttpStatus.METHOD_NOT_ALLOWED, "Método HTTP no soportado: DELETE");
    }

    @Test
    void shouldHandleMediaTypeNotSupported_Return415WithContentType() {
        // Given
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        // When
        ResponseEntity<ErrorResponse> response = handler.handleMediaTypeNotSupported(ex);

        // Then
        assertError(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de contenido no soportado: text/plain");
    }

    @Test
    void shouldHandleValidationErrors_WithClassLevelObjectError_UseObjectNameWithoutClassCast() throws Exception {
        // Given: una restricción a nivel de clase produce un ObjectError (no FieldError)
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new SampleRequest(1L, "x"), "request");
        bindingResult.addError(new ObjectError("request", "la llegada debe ser posterior a la salida"));
        bindingResult.addError(new FieldError("request", "name", "no debe estar vacío"));
        MethodParameter parameter = new MethodParameter(
                SampleController.class.getDeclaredMethod("create", SampleRequest.class), 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        // When
        ResponseEntity<ErrorResponse> response = handler.handleValidationExceptions(ex);

        // Then
        assertError(response, HttpStatus.BAD_REQUEST, "Error de validación en los datos enviados");
        assertThat(response.getBody().validationErrors())
                .containsEntry("request", "la llegada debe ser posterior a la salida")
                .containsEntry("name", "no debe estar vacío")
                .hasSize(2);
    }

    // Integración con Spring MVC (MockMvc standalone): el advice resuelve las excepciones reales del framework

    @Nested
    class WithMockMvc {

        private MockMvc mvc;

        @BeforeEach
        void setUpMvc() {
            mvc = MockMvcBuilders.standaloneSetup(new SampleController())
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();
        }

        @Test
        void shouldReturn404_WhenResourceNotFoundIsThrown() throws Exception {
            mvc.perform(get("/test/not-found"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"))
                    .andExpect(jsonPath("$.message").value("Parada con id 5 no encontrado"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        void shouldReturnStatusOfBusinessException_WhenSubclassHasNoSpecificHandler() throws Exception {
            mvc.perform(get("/test/business"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Este viaje ya tiene una asignación"));
        }

        @Test
        void shouldReturn400WithFieldErrors_WhenBodyIsInvalid() throws Exception {
            mvc.perform(post("/test/create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tripId\": null, \"name\": \"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Error de validación en los datos enviados"))
                    .andExpect(jsonPath("$.validationErrors.tripId").exists())
                    .andExpect(jsonPath("$.validationErrors.name").exists());
        }

        @Test
        void shouldReturn400_WhenBodyIsMalformed() throws Exception {
            mvc.perform(post("/test/create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tripId\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("El cuerpo de la petición no es válido o está mal formado"));
        }

        @Test
        void shouldReturn400_WhenPathVariableHasWrongType() throws Exception {
            mvc.perform(get("/test/items/abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Parámetro inválido o faltante en la petición"));
        }

        @Test
        void shouldReturn400_WhenRequiredParameterIsMissing() throws Exception {
            mvc.perform(get("/test/search"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Parámetro inválido o faltante en la petición"));
        }

        @Test
        void shouldReturn403_WhenAuthorizationIsDenied() throws Exception {
            mvc.perform(get("/test/denied"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        void shouldReturn409_WhenDataIntegrityIsViolated() throws Exception {
            mvc.perform(get("/test/integrity"))
                    .andExpect(status().isConflict())
                    .andExpect(content().string(not(containsString("uk_secret_constraint"))));
        }

        @Test
        void shouldReturn500WithoutInternalMessage_WhenUnexpectedErrorOccurs() throws Exception {
            mvc.perform(get("/test/boom"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("Error interno del servidor"))
                    .andExpect(jsonPath("$.validationErrors").value(nullValue()))
                    .andExpect(content().string(not(containsString("detalle interno"))));
        }

        @Test
        void shouldReturn404_WhenNoResourceFoundIsThrown() throws Exception {
            mvc.perform(get("/test/missing-resource"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Recurso no encontrado: /static/missing.js"));
        }

        @Test
        void shouldReturn405_WhenHttpMethodIsNotSupported() throws Exception {
            mvc.perform(delete("/test/items/1"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.status").value(405))
                    .andExpect(jsonPath("$.message").value("Método HTTP no soportado: DELETE"));
        }

        @Test
        void shouldReturn415_WhenContentTypeIsNotSupported() throws Exception {
            mvc.perform(post("/test/create")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("tripId=1"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.status").value(415))
                    .andExpect(jsonPath("$.message").value(containsString("text/plain")));
        }

        @Test
        void shouldReturn400WithObjectName_WhenClassLevelConstraintFails() throws Exception {
            mvc.perform(post("/test/range")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"from\": 5, \"to\": 2}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Error de validación en los datos enviados"))
                    .andExpect(jsonPath("$.validationErrors.rangeRequest").value("rango inválido"));
        }
    }

    // Controlador mínimo solo para las pruebas del advice

    record SampleRequest(@NotNull Long tripId, @NotBlank String name) {
    }

    // Restricción a nivel de clase: produce un ObjectError sin campo
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = ValidRangeValidator.class)
    @interface ValidRange {
        String message() default "rango inválido";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class ValidRangeValidator implements ConstraintValidator<ValidRange, RangeRequest> {
        @Override
        public boolean isValid(RangeRequest value, ConstraintValidatorContext context) {
            return value == null || value.from() == null || value.to() == null || value.from() < value.to();
        }
    }

    @ValidRange
    record RangeRequest(Integer from, Integer to) {
    }

    @RestController
    static class SampleController {

        @PostMapping("/test/create")
        public String create(@Valid @RequestBody SampleRequest request) {
            return "ok";
        }

        @GetMapping("/test/items/{id}")
        public String byId(@PathVariable Long id) {
            return "ok";
        }

        @GetMapping("/test/search")
        public String search(@RequestParam Long fromStopId) {
            return "ok";
        }

        @GetMapping("/test/not-found")
        public String notFound() {
            throw new ResourceNotFoundException("Parada", 5L);
        }

        @GetMapping("/test/business")
        public String business() {
            throw new BusinessException("Este viaje ya tiene una asignación", HttpStatus.CONFLICT, "ASSIGNMENT_EXISTS");
        }

        @GetMapping("/test/denied")
        public String denied() {
            throw new AuthorizationDeniedException("Access Denied", new AuthorizationDecision(false));
        }

        @GetMapping("/test/integrity")
        public String integrity() {
            throw new DataIntegrityViolationException("violates uk_secret_constraint");
        }

        @GetMapping("/test/boom")
        public String boom() {
            throw new IllegalStateException("detalle interno de la base de datos");
        }

        @GetMapping("/test/missing-resource")
        public String missingResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "static/missing.js");
        }

        @PostMapping("/test/range")
        public String range(@Valid @RequestBody RangeRequest request) {
            return "ok";
        }
    }
}
