package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.parcel.ParcelCreateRequest;
import com.web.dto.parcel.ParcelReopenRequest;
import com.web.dto.parcel.ParcelResponse;
import com.web.dto.parcel.ParcelStatusUpdateRequest;
import com.web.entity.Parcel;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.service.parcel.ParcelService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(ParcelController.class)
@Import(SecurityConfig.class)
class ParcelControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private ParcelService parcelService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    // Encomienda de ejemplo (respuesta del personal: sin OTP)
    private ParcelResponse parcel(Parcel.ParcelStatus status) {
        return new ParcelResponse(
                1L, "PARCEL001", 1L, "Route Name", null,
                "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                null, null, status,
                null, null, null, null, "Caja", 0
        );
    }

    private ParcelCreateRequest validCreateRequest() {
        return new ParcelCreateRequest(
                1L, "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                BigDecimal.valueOf(10000), BigDecimal.valueOf(10.0), "Description"
        );
    }

    private String body(String code, Parcel.ParcelStatus status, String otp, String photo) throws Exception {
        return om.writeValueAsString(new ParcelStatusUpdateRequest(code, status, otp, photo));
    }

    // ---------- GET /parcels ----------

    // Verifica que el personal de taquilla, despacho y admin liste encomiendas con filtros
    @ParameterizedTest
    @ValueSource(strings = {"CLERK", "DISPATCHER", "ADMIN"})
    void getAllParcels_shouldReturn200WithFilters(String role) throws Exception {
        when(parcelService.searchParcels(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                Parcel.ParcelStatus.FAILED)).thenReturn(List.of(parcel(Parcel.ParcelStatus.FAILED)));

        mvc.perform(get("/api/v1/parcels").with(user("staff").roles(role))
                        .param("from", "2026-01-01").param("to", "2026-01-31").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("FAILED"));
    }

    // Verifica que sin filtros se listen todas
    @Test
    @WithMockUser(roles = "CLERK")
    void getAllParcels_withoutFilters_shouldReturn200() throws Exception {
        when(parcelService.searchParcels(null, null, null)).thenReturn(List.of(parcel(Parcel.ParcelStatus.CREATED)));

        mvc.perform(get("/api/v1/parcels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    // Verifica 400 con un estado o una fecha inválidos
    @Test
    @WithMockUser(roles = "ADMIN")
    void getAllParcels_shouldReturn400WhenInvalidFilters() throws Exception {
        mvc.perform(get("/api/v1/parcels").param("status", "PERDIDA"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/parcels").param("from", "ayer"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(parcelService);
    }

    // Verifica que PASSENGER y DRIVER no puedan listar encomiendas
    @ParameterizedTest
    @ValueSource(strings = {"PASSENGER", "DRIVER"})
    void getAllParcels_shouldReturn403ForOtherRoles(String role) throws Exception {
        mvc.perform(get("/api/v1/parcels").with(user("other").roles(role)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(parcelService);
    }

    // Verifica que sin autenticación se responda 401
    @Test
    void getAllParcels_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/parcels"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(parcelService);
    }

    // ---------- POST /parcels ----------

    // Verifica que un CLERK pueda crear una encomienda y reciba el OTP
    @Test
    @WithMockUser(roles = "CLERK")
    void createParcel_shouldReturn201WithOtp() throws Exception {
        var resp = new ParcelResponse(
                1L, "PARCEL001", 1L, "Route Name", null,
                "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                null, null, Parcel.ParcelStatus.CREATED,
                "123456", null, null, null, "Description", 0
        );
        when(parcelService.createParcel(any())).thenReturn(resp);

        mvc.perform(post("/api/v1/parcels").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validCreateRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.deliveryOtp").value("123456"))
                .andExpect(jsonPath("$.description").value("Description"));
    }

    // Verifica que se rechace una encomienda sin remitente ni tripId, o con descripción demasiado larga
    @Test
    @WithMockUser(roles = "CLERK")
    void createParcel_shouldReturn400WhenInvalid() throws Exception {
        var req = new ParcelCreateRequest(
                null, "", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                BigDecimal.valueOf(10000), null, "x".repeat(256)
        );

        mvc.perform(post("/api/v1/parcels").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.tripId").exists())
                .andExpect(jsonPath("$.validationErrors.senderName").exists())
                .andExpect(jsonPath("$.validationErrors.description").exists());

        verifyNoInteractions(parcelService);
    }

    // Verifica que retorne 404 cuando el viaje de la encomienda no existe
    @Test
    @WithMockUser(roles = "CLERK")
    void createParcel_shouldReturn404WhenTripNotFound() throws Exception {
        when(parcelService.createParcel(any())).thenThrow(new ResourceNotFoundException("Viaje", 1L));

        mvc.perform(post("/api/v1/parcels").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validCreateRequest())))
                .andExpect(status().isNotFound());
    }

    // Verifica que DRIVER y PASSENGER no puedan crear encomiendas
    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "PASSENGER"})
    void createParcel_shouldReturn403ForOtherRoles(String role) throws Exception {
        mvc.perform(post("/api/v1/parcels").with(user("other").roles(role)).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validCreateRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(parcelService);
    }

    // ---------- GET /parcels/{code}/track ----------

    // Verifica que cualquiera pueda rastrear y que la respuesta no exponga teléfonos ni OTP
    @Test
    void trackParcel_shouldReturn200WithoutPersonalData() throws Exception {
        var publicResp = new ParcelResponse(
                1L, "PARCEL001", 1L, "Route Name", null,
                null, null, null, null,
                1L, "Origin", 2L, "Destination",
                null, null, Parcel.ParcelStatus.IN_TRANSIT,
                null, null, null, null, null, 0
        );
        when(parcelService.trackParcel("PARCEL001")).thenReturn(publicResp);

        mvc.perform(get("/api/v1/parcels/PARCEL001/track").with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PARCEL001"))
                .andExpect(jsonPath("$.senderPhone").doesNotExist())
                .andExpect(jsonPath("$.receiverPhone").doesNotExist())
                .andExpect(jsonPath("$.senderName").doesNotExist())
                .andExpect(jsonPath("$.deliveryOtp").doesNotExist());
    }

    // Verifica que retorne 404 cuando la encomienda no existe
    @Test
    void trackParcel_shouldReturn404WhenNotFound() throws Exception {
        when(parcelService.trackParcel("INVALID")).thenThrow(new ResourceNotFoundException("Encomienda con código: INVALID"));

        mvc.perform(get("/api/v1/parcels/INVALID/track").with(anonymous()))
                .andExpect(status().isNotFound());
    }

    // ---------- POST /parcels/{code}/deliver ----------

    // Verifica que DRIVER y CLERK entreguen con OTP y foto
    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "CLERK"})
    void deliverParcel_shouldReturn200(String role) throws Exception {
        when(parcelService.deliverWithOtp("PARCEL001", "123456", "photo.jpg"))
                .thenReturn(parcel(Parcel.ParcelStatus.DELIVERED));

        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").with(user("staff").roles(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL001", Parcel.ParcelStatus.DELIVERED, "123456", "photo.jpg")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    // Verifica validación cuando faltan OTP o foto en la entrega
    @Test
    @WithMockUser(roles = "DRIVER")
    void deliverParcel_shouldReturn400WhenMissingOtpOrPhoto() throws Exception {
        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL001", Parcel.ParcelStatus.DELIVERED, null, "photo.jpg")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La entrega requiere OTP y foto de prueba"));
        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL001", Parcel.ParcelStatus.DELIVERED, "123456", " ")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(parcelService);
    }

    // Verifica que el OTP incorrecto devuelva 400 con los intentos restantes
    @Test
    @WithMockUser(roles = "DRIVER")
    void deliverParcel_shouldReturn400WithRemainingAttemptsWhenOtpInvalid() throws Exception {
        when(parcelService.deliverWithOtp("PARCEL001", "000000", "photo.jpg"))
                .thenThrow(new BusinessException("OTP inválido. Intentos restantes: 2", HttpStatus.BAD_REQUEST,
                        "INVALID_OTP"));

        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, Parcel.ParcelStatus.DELIVERED, "000000", "photo.jpg")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("OTP inválido. Intentos restantes: 2"));
    }

    // Verifica 422 al entregar una encomienda que no está en tránsito
    @Test
    @WithMockUser(roles = "DRIVER")
    void deliverParcel_shouldReturn422WhenNotInTransit() throws Exception {
        when(parcelService.deliverWithOtp("PARCEL001", "123456", "photo.jpg"))
                .thenThrow(new InvalidStateTransitionException("CREATED", "DELIVERED"));

        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL001", Parcel.ParcelStatus.DELIVERED, "123456", "photo.jpg")))
                .andExpect(status().isUnprocessableEntity());
    }

    // Verifica que el body sin estado o con un estado inexistente sea rechazado
    @Test
    @WithMockUser(roles = "DRIVER")
    void deliverParcel_shouldReturn400WhenStatusMissingOrUnknown() throws Exception {
        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"PARCEL001\",\"otp\":\"123456\",\"proofPhotoUrl\":\"photo.jpg\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.status").exists());
        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"PARCEL001\",\"status\":\"PERDIDA\",\"otp\":\"1\",\"proofPhotoUrl\":\"p\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(parcelService);
    }

    // Verifica que retorne 404 cuando la encomienda a entregar no existe
    @Test
    @WithMockUser(roles = "DRIVER")
    void deliverParcel_shouldReturn404WhenNotFound() throws Exception {
        when(parcelService.deliverWithOtp("INVALID", "123456", "photo.jpg"))
                .thenThrow(new ResourceNotFoundException("Encomienda con código: INVALID"));

        mvc.perform(post("/api/v1/parcels/INVALID/deliver").contentType(MediaType.APPLICATION_JSON)
                        .content(body("INVALID", Parcel.ParcelStatus.DELIVERED, "123456", "photo.jpg")))
                .andExpect(status().isNotFound());
    }

    // Verifica que PASSENGER, DISPATCHER y ADMIN no puedan entregar encomiendas
    @ParameterizedTest
    @ValueSource(strings = {"PASSENGER", "DISPATCHER", "ADMIN"})
    void deliverParcel_shouldReturn403ForOtherRoles(String role) throws Exception {
        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").with(user("other").roles(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL001", Parcel.ParcelStatus.DELIVERED, "123456", "photo.jpg")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(parcelService);
    }

    // ---------- PUT/POST /parcels/{code}/status ----------

    // Verifica que DRIVER y CLERK pongan la encomienda en tránsito
    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "CLERK"})
    void updateParcelStatus_shouldReturn200ForInTransit(String role) throws Exception {
        when(parcelService.updateStatus("PARCEL001", Parcel.ParcelStatus.IN_TRANSIT, null, null))
                .thenReturn(parcel(Parcel.ParcelStatus.IN_TRANSIT));

        mvc.perform(put("/api/v1/parcels/PARCEL001/status").with(user("staff").roles(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL001", Parcel.ParcelStatus.IN_TRANSIT, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
    }

    // Verifica que POST sea alias de PUT (POST /api/parcels/{code}/status del documento)
    @Test
    @WithMockUser(roles = "DRIVER")
    void updateParcelStatus_postAlias_shouldReturn200() throws Exception {
        when(parcelService.updateStatus("PARCEL001", Parcel.ParcelStatus.FAILED, null, null))
                .thenReturn(parcel(Parcel.ParcelStatus.FAILED));

        mvc.perform(post("/api/v1/parcels/PARCEL001/status").contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, Parcel.ParcelStatus.FAILED, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    // Verifica que DELIVERED sin OTP responda 400 DELIVERY_REQUIRES_OTP
    @Test
    @WithMockUser(roles = "CLERK")
    void updateParcelStatus_toDeliveredWithoutOtp_shouldReturn400() throws Exception {
        when(parcelService.updateStatus("PARCEL001", Parcel.ParcelStatus.DELIVERED, null, null))
                .thenThrow(new BusinessException("La entrega debe registrarse con OTP y foto de prueba",
                        HttpStatus.BAD_REQUEST, "DELIVERY_REQUIRES_OTP"));

        mvc.perform(put("/api/v1/parcels/PARCEL001/status").contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL001", Parcel.ParcelStatus.DELIVERED, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("OTP")));
    }

    // Verifica que DELIVERED con OTP y foto se entregue (delegando en la entrega con OTP)
    @Test
    @WithMockUser(roles = "CLERK")
    void updateParcelStatus_toDeliveredWithOtpAndPhoto_shouldReturn200() throws Exception {
        when(parcelService.updateStatus("PARCEL001", Parcel.ParcelStatus.DELIVERED, "123456", "photo.jpg"))
                .thenReturn(parcel(Parcel.ParcelStatus.DELIVERED));

        mvc.perform(put("/api/v1/parcels/PARCEL001/status").contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL001", Parcel.ParcelStatus.DELIVERED, "123456", "photo.jpg")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    // Verifica que el código del body sea opcional y que, si viene distinto al de la URL, se rechace
    @Test
    @WithMockUser(roles = "CLERK")
    void updateParcelStatus_shouldReturn400WhenBodyCodeDiffersFromPath() throws Exception {
        mvc.perform(put("/api/v1/parcels/PARCEL001/status").contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL999", Parcel.ParcelStatus.IN_TRANSIT, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PARCEL999")));
        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL999", Parcel.ParcelStatus.DELIVERED, "123456", "photo.jpg")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(parcelService);
    }

    // Verifica que el body sin estado se rechace por @Valid
    @Test
    @WithMockUser(roles = "CLERK")
    void updateParcelStatus_shouldReturn400WhenStatusMissing() throws Exception {
        mvc.perform(put("/api/v1/parcels/PARCEL001/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.status").exists());

        verifyNoInteractions(parcelService);
    }

    // Verifica que retorne 404 cuando la encomienda no existe
    @Test
    @WithMockUser(roles = "CLERK")
    void updateParcelStatus_shouldReturn404WhenNotFound() throws Exception {
        when(parcelService.updateStatus("INVALID", Parcel.ParcelStatus.IN_TRANSIT, null, null))
                .thenThrow(new ResourceNotFoundException("Encomienda con código: INVALID"));

        mvc.perform(put("/api/v1/parcels/INVALID/status").contentType(MediaType.APPLICATION_JSON)
                        .content(body("INVALID", Parcel.ParcelStatus.IN_TRANSIT, null, null)))
                .andExpect(status().isNotFound());
    }

    // Verifica que una transición de estado no permitida devuelva 422
    @Test
    @WithMockUser(roles = "CLERK")
    void updateParcelStatus_shouldReturn422WhenInvalidTransition() throws Exception {
        when(parcelService.updateStatus("PARCEL001", Parcel.ParcelStatus.CREATED, null, null))
                .thenThrow(new InvalidStateTransitionException("DELIVERED", "CREATED"));

        mvc.perform(put("/api/v1/parcels/PARCEL001/status").contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL001", Parcel.ParcelStatus.CREATED, null, null)))
                .andExpect(status().isUnprocessableEntity());
    }

    // Verifica que un conductor no asignado reciba 403 del servicio
    @Test
    @WithMockUser(roles = "DRIVER")
    void updateParcelStatus_shouldReturn403WhenDriverNotAssigned() throws Exception {
        when(parcelService.updateStatus("PARCEL001", Parcel.ParcelStatus.IN_TRANSIT, null, null))
                .thenThrow(new BusinessException("El conductor no está asignado a este viaje",
                        HttpStatus.FORBIDDEN, "DRIVER_NOT_ASSIGNED"));

        mvc.perform(put("/api/v1/parcels/PARCEL001/status").contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL001", Parcel.ParcelStatus.IN_TRANSIT, null, null)))
                .andExpect(status().isForbidden());
    }

    // Verifica que ADMIN, DISPATCHER y PASSENGER no cambien el estado (PUT ni POST)
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "DISPATCHER", "PASSENGER"})
    void updateParcelStatus_shouldReturn403ForOtherRoles(String role) throws Exception {
        mvc.perform(put("/api/v1/parcels/PARCEL001/status").with(user("other").roles(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL001", Parcel.ParcelStatus.IN_TRANSIT, null, null)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/parcels/PARCEL001/status").with(user("other").roles(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL001", Parcel.ParcelStatus.IN_TRANSIT, null, null)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(parcelService);
    }

    // Verifica que sin autenticación no se pueda cambiar el estado
    @Test
    void updateParcelStatus_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(put("/api/v1/parcels/PARCEL001/status").contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL001", Parcel.ParcelStatus.IN_TRANSIT, null, null)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(parcelService);
    }

    // ---------- POST /parcels/{code}/reopen ----------

    // Verifica que DISPATCHER y ADMIN reabran una encomienda FAILED, con y sin reasignación
    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "ADMIN"})
    void reopenParcel_shouldReturn200(String role) throws Exception {
        when(parcelService.reopenParcel("PARCEL001", null)).thenReturn(parcel(Parcel.ParcelStatus.IN_TRANSIT));
        when(parcelService.reopenParcel("PARCEL001", 7L)).thenReturn(parcel(Parcel.ParcelStatus.CREATED));

        mvc.perform(post("/api/v1/parcels/PARCEL001/reopen").with(user("boss").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
        mvc.perform(post("/api/v1/parcels/PARCEL001/reopen").with(user("boss").roles(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ParcelReopenRequest(7L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    // Verifica que DRIVER y CLERK no puedan reabrir encomiendas
    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "CLERK", "PASSENGER"})
    void reopenParcel_shouldReturn403ForOtherRoles(String role) throws Exception {
        mvc.perform(post("/api/v1/parcels/PARCEL001/reopen").with(user("staff").roles(role)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(parcelService);
    }

    // Verifica 422 al reabrir una encomienda que no está FAILED
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void reopenParcel_shouldReturn422WhenNotFailed() throws Exception {
        when(parcelService.reopenParcel("PARCEL001", null))
                .thenThrow(new InvalidStateTransitionException("DELIVERED", "IN_TRANSIT"));

        mvc.perform(post("/api/v1/parcels/PARCEL001/reopen"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- GET /trips/{id}/parcels ----------

    // Verifica que el personal liste las encomiendas de un viaje con filtro de estado
    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "CLERK", "DISPATCHER", "ADMIN"})
    void getTripParcels_shouldReturn200(String role) throws Exception {
        when(parcelService.getTripParcels(1L, Parcel.ParcelStatus.IN_TRANSIT))
                .thenReturn(List.of(parcel(Parcel.ParcelStatus.IN_TRANSIT)));

        mvc.perform(get("/api/v1/trips/1/parcels").with(user("staff").roles(role)).param("status", "IN_TRANSIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("PARCEL001"))
                .andExpect(jsonPath("$[0].deliveryOtp").doesNotExist());
    }

    // Verifica 403 para un conductor de otro viaje y para PASSENGER
    @Test
    void getTripParcels_shouldReturn403ForUnassignedDriverAndPassenger() throws Exception {
        when(parcelService.getTripParcels(1L, null))
                .thenThrow(new BusinessException("El conductor no está asignado a este viaje",
                        HttpStatus.FORBIDDEN, "DRIVER_NOT_ASSIGNED"));

        mvc.perform(get("/api/v1/trips/1/parcels").with(user("driver").roles("DRIVER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/trips/1/parcels").with(user("pax").roles("PASSENGER")))
                .andExpect(status().isForbidden());

        verify(parcelService, never()).getTripParcels(1L, Parcel.ParcelStatus.CREATED);
    }

    // Verifica 401 sin autenticación (no es parte del catálogo público de viajes)
    @Test
    void getTripParcels_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/trips/1/parcels"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(parcelService);
    }

    // Garantiza que LocalDateTime se serializa (la respuesta de entrega trae deliveredAt)
    @Test
    @WithMockUser(roles = "CLERK")
    void deliverParcel_shouldSerializeDeliveredAt() throws Exception {
        var delivered = new ParcelResponse(
                1L, "PARCEL001", 1L, "Route Name", null,
                "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                null, null, Parcel.ParcelStatus.DELIVERED,
                null, "photo.jpg", null, LocalDateTime.of(2026, 1, 1, 10, 0), null, 0
        );
        when(parcelService.deliverWithOtp("PARCEL001", "123456", "photo.jpg")).thenReturn(delivered);

        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").contentType(MediaType.APPLICATION_JSON)
                        .content(body("PARCEL001", Parcel.ParcelStatus.DELIVERED, "123456", "photo.jpg")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proofPhotoUrl").value("photo.jpg"))
                .andExpect(jsonPath("$.deliveredAt").isNotEmpty());
    }
}
