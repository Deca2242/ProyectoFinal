package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.parcel.ParcelCreateRequest;
import com.web.dto.parcel.ParcelResponse;
import com.web.dto.parcel.ParcelStatusUpdateRequest;
import com.web.entity.Parcel;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.service.parcel.ParcelService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
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

    // Verifica que un CLERK pueda consultar todas las encomiendas
    @Test
    @WithMockUser(roles = "CLERK")
    void getAllParcels_shouldReturn200() throws Exception {
        var resp = List.of(new ParcelResponse(
                1L, "PARCEL001", 1L, "Route Name", null,
                "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                null, null, Parcel.ParcelStatus.IN_TRANSIT,
                "123456", null, null, null
        ));

        when(parcelService.getAllParcels()).thenReturn(resp);

        mvc.perform(get("/api/v1/parcels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    // Verifica que un CLERK pueda crear una encomienda
    @Test
    @WithMockUser(roles = "CLERK")
    void createParcel_shouldReturn201() throws Exception {
        var req = new ParcelCreateRequest(
                1L, "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                BigDecimal.valueOf(10000), BigDecimal.valueOf(10.0), "Description"
        );
        var resp = new ParcelResponse(
                1L, "PARCEL001", 1L, "Route Name", null,
                "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                null, null, Parcel.ParcelStatus.IN_TRANSIT,
                "123456", null, null, null
        );

        when(parcelService.createParcel(any())).thenReturn(resp);

        mvc.perform(post("/api/v1/parcels").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    // Verifica que cualquier usuario pueda rastrear una encomienda por código (público)
    @Test
    void trackParcel_shouldReturn200() throws Exception {
        var resp = new ParcelResponse(
                1L, "PARCEL001", 1L, "Route Name", null,
                "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                null, null, Parcel.ParcelStatus.IN_TRANSIT,
                "123456", null, null, null
        );

        when(parcelService.trackParcel("PARCEL001")).thenReturn(resp);

        mvc.perform(get("/api/v1/parcels/PARCEL001/track")
                        .with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PARCEL001"));
    }

    // Verifica que retorne 404 cuando la encomienda no existe
    @Test
    void trackParcel_shouldReturn404WhenNotFound() throws Exception {
        when(parcelService.trackParcel("INVALID")).thenThrow(new ResourceNotFoundException("Encomienda con código: INVALID"));

        mvc.perform(get("/api/v1/parcels/INVALID/track")
                        .with(anonymous()))
                .andExpect(status().isNotFound());
    }

    // Verifica que un DRIVER pueda entregar una encomienda con OTP y foto
    @Test
    @WithMockUser(roles = "DRIVER")
    void deliverParcel_shouldReturn200() throws Exception {
        var trackResp = new ParcelResponse(
                1L, "PARCEL001", 1L, "Route Name", null,
                "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                null, null, Parcel.ParcelStatus.IN_TRANSIT,
                "123456", null, null, null
        );
        var deliverResp = new ParcelResponse(
                1L, "PARCEL001", 1L, "Route Name", null,
                "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                null, null, Parcel.ParcelStatus.DELIVERED,
                "123456", "photo.jpg", null, null
        );
        var req = new ParcelStatusUpdateRequest("PARCEL001", Parcel.ParcelStatus.DELIVERED, "123456", "photo.jpg");

        when(parcelService.trackParcel("PARCEL001")).thenReturn(trackResp);
        when(parcelService.deliverWithOtp(1L, "123456", "photo.jpg")).thenReturn(deliverResp);

        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    // Verifica validación cuando faltan OTP o foto en la entrega
    @Test
    @WithMockUser(roles = "DRIVER")
    void deliverParcel_shouldReturn400WhenMissingOtp() throws Exception {
        var req = new ParcelStatusUpdateRequest("PARCEL001", Parcel.ParcelStatus.DELIVERED, null, "photo.jpg");

        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // Verifica que un CLERK pueda actualizar el estado de una encomienda
    @Test
    @WithMockUser(roles = "CLERK")
    void updateParcelStatus_shouldReturn200() throws Exception {
        var trackResp = new ParcelResponse(
                1L, "PARCEL001", 1L, "Route Name", null,
                "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                null, null, Parcel.ParcelStatus.IN_TRANSIT,
                "123456", null, null, null
        );
        var updateResp = new ParcelResponse(
                1L, "PARCEL001", 1L, "Route Name", null,
                "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                null, null, Parcel.ParcelStatus.DELIVERED,
                "123456", null, null, null
        );
        var req = new ParcelStatusUpdateRequest("PARCEL001", Parcel.ParcelStatus.DELIVERED, null, null);

        when(parcelService.trackParcel("PARCEL001")).thenReturn(trackResp);
        when(parcelService.updateStatus(1L, Parcel.ParcelStatus.DELIVERED)).thenReturn(updateResp);

        mvc.perform(put("/api/v1/parcels/PARCEL001/status").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    // Encomienda de ejemplo en tránsito
    private ParcelResponse parcel(Parcel.ParcelStatus status) {
        return new ParcelResponse(
                1L, "PARCEL001", 1L, "Route Name", null,
                "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                null, null, status,
                "123456", null, null, null
        );
    }

    private ParcelCreateRequest validCreateRequest() {
        return new ParcelCreateRequest(
                1L, "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                BigDecimal.valueOf(10000), BigDecimal.valueOf(10.0), "Description"
        );
    }

    // GET /parcels

    // Verifica que un ADMIN también pueda listar encomiendas
    @Test
    @WithMockUser(roles = "ADMIN")
    void getAllParcels_shouldReturn200ForAdmin() throws Exception {
        when(parcelService.getAllParcels()).thenReturn(List.of(parcel(Parcel.ParcelStatus.CREATED)));

        mvc.perform(get("/api/v1/parcels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CREATED"));
    }

    // Verifica que un PASSENGER no pueda listar encomiendas
    @Test
    @WithMockUser(roles = "PASSENGER")
    void getAllParcels_shouldReturn403ForPassenger() throws Exception {
        mvc.perform(get("/api/v1/parcels"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(parcelService);
    }

    // Verifica que un DRIVER no pueda listar encomiendas
    @Test
    @WithMockUser(roles = "DRIVER")
    void getAllParcels_shouldReturn403ForDriver() throws Exception {
        mvc.perform(get("/api/v1/parcels"))
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

    // POST /parcels

    // Verifica que se rechace una encomienda sin remitente ni tripId
    @Test
    @WithMockUser(roles = "CLERK")
    void createParcel_shouldReturn400WhenInvalid() throws Exception {
        var req = new ParcelCreateRequest(
                null, "", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                BigDecimal.valueOf(10000), null, null
        );

        mvc.perform(post("/api/v1/parcels").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.tripId").exists())
                .andExpect(jsonPath("$.validationErrors.senderName").exists());

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

    // Verifica que un DRIVER no pueda crear encomiendas
    @Test
    @WithMockUser(roles = "DRIVER")
    void createParcel_shouldReturn403ForDriver() throws Exception {
        mvc.perform(post("/api/v1/parcels").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validCreateRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(parcelService);
    }

    // POST /parcels/{code}/deliver

    // Verifica que un CLERK también pueda entregar encomiendas
    @Test
    @WithMockUser(roles = "CLERK")
    void deliverParcel_shouldReturn200ForClerk() throws Exception {
        var req = new ParcelStatusUpdateRequest("PARCEL001", Parcel.ParcelStatus.DELIVERED, "123456", "photo.jpg");
        when(parcelService.trackParcel("PARCEL001")).thenReturn(parcel(Parcel.ParcelStatus.IN_TRANSIT));
        when(parcelService.deliverWithOtp(1L, "123456", "photo.jpg")).thenReturn(parcel(Parcel.ParcelStatus.DELIVERED));

        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    // Verifica validación cuando falta la foto de prueba
    @Test
    @WithMockUser(roles = "DRIVER")
    void deliverParcel_shouldReturn400WhenMissingPhoto() throws Exception {
        var req = new ParcelStatusUpdateRequest("PARCEL001", Parcel.ParcelStatus.DELIVERED, "123456", null);

        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La entrega requiere OTP y foto de prueba"));

        verifyNoInteractions(parcelService);
    }

    // Verifica que el body sin estado sea rechazado por @Valid
    @Test
    @WithMockUser(roles = "DRIVER")
    void deliverParcel_shouldReturn400WhenStatusMissing() throws Exception {
        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"PARCEL001\",\"otp\":\"123456\",\"proofPhotoUrl\":\"photo.jpg\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.status").exists());

        verifyNoInteractions(parcelService);
    }

    // Verifica que un estado inexistente en el body devuelva 400
    @Test
    @WithMockUser(roles = "DRIVER")
    void deliverParcel_shouldReturn400WhenStatusIsUnknown() throws Exception {
        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"PARCEL001\",\"status\":\"PERDIDA\",\"otp\":\"1\",\"proofPhotoUrl\":\"p\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(parcelService);
    }

    // Verifica que retorne 404 cuando la encomienda a entregar no existe
    @Test
    @WithMockUser(roles = "DRIVER")
    void deliverParcel_shouldReturn404WhenNotFound() throws Exception {
        var req = new ParcelStatusUpdateRequest("INVALID", Parcel.ParcelStatus.DELIVERED, "123456", "photo.jpg");
        when(parcelService.trackParcel("INVALID")).thenThrow(new ResourceNotFoundException("Encomienda", "INVALID"));

        mvc.perform(post("/api/v1/parcels/INVALID/deliver").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isNotFound());

        verify(parcelService, never()).deliverWithOtp(anyLong(), any(), any());
    }

    // Verifica que un PASSENGER no pueda entregar encomiendas
    @Test
    @WithMockUser(roles = "PASSENGER")
    void deliverParcel_shouldReturn403ForPassenger() throws Exception {
        var req = new ParcelStatusUpdateRequest("PARCEL001", Parcel.ParcelStatus.DELIVERED, "123456", "photo.jpg");

        mvc.perform(post("/api/v1/parcels/PARCEL001/deliver").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(parcelService);
    }

    // PUT /parcels/{code}/status

    // Verifica que un DRIVER pueda actualizar el estado de una encomienda
    @Test
    @WithMockUser(roles = "DRIVER")
    void updateParcelStatus_shouldReturn200ForDriver() throws Exception {
        var req = new ParcelStatusUpdateRequest("PARCEL001", Parcel.ParcelStatus.IN_TRANSIT, null, null);
        when(parcelService.trackParcel("PARCEL001")).thenReturn(parcel(Parcel.ParcelStatus.CREATED));
        when(parcelService.updateStatus(1L, Parcel.ParcelStatus.IN_TRANSIT)).thenReturn(parcel(Parcel.ParcelStatus.IN_TRANSIT));

        mvc.perform(put("/api/v1/parcels/PARCEL001/status").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
    }

    // Verifica que el body sin código sea rechazado por @Valid
    @Test
    @WithMockUser(roles = "CLERK")
    void updateParcelStatus_shouldReturn400WhenCodeBlank() throws Exception {
        var req = new ParcelStatusUpdateRequest("", Parcel.ParcelStatus.IN_TRANSIT, null, null);

        mvc.perform(put("/api/v1/parcels/PARCEL001/status").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.code").exists());

        verifyNoInteractions(parcelService);
    }

    // Verifica que retorne 404 cuando la encomienda no existe
    @Test
    @WithMockUser(roles = "CLERK")
    void updateParcelStatus_shouldReturn404WhenNotFound() throws Exception {
        var req = new ParcelStatusUpdateRequest("INVALID", Parcel.ParcelStatus.IN_TRANSIT, null, null);
        when(parcelService.trackParcel("INVALID")).thenThrow(new ResourceNotFoundException("Encomienda", "INVALID"));

        mvc.perform(put("/api/v1/parcels/INVALID/status").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isNotFound());

        verify(parcelService, never()).updateStatus(anyLong(), any());
    }

    // Verifica que una transición de estado no permitida devuelva 422
    @Test
    @WithMockUser(roles = "CLERK")
    void updateParcelStatus_shouldReturn422WhenInvalidTransition() throws Exception {
        var req = new ParcelStatusUpdateRequest("PARCEL001", Parcel.ParcelStatus.CREATED, null, null);
        when(parcelService.trackParcel("PARCEL001")).thenReturn(parcel(Parcel.ParcelStatus.DELIVERED));
        when(parcelService.updateStatus(1L, Parcel.ParcelStatus.CREATED))
                .thenThrow(new InvalidStateTransitionException("DELIVERED", "CREATED"));

        mvc.perform(put("/api/v1/parcels/PARCEL001/status").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity());
    }

    // Verifica que un ADMIN no pueda cambiar el estado de una encomienda
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateParcelStatus_shouldReturn403ForAdmin() throws Exception {
        var req = new ParcelStatusUpdateRequest("PARCEL001", Parcel.ParcelStatus.IN_TRANSIT, null, null);

        mvc.perform(put("/api/v1/parcels/PARCEL001/status").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(parcelService);
    }

    // Verifica que sin autenticación no se pueda cambiar el estado
    @Test
    void updateParcelStatus_shouldReturn401WhenAnonymous() throws Exception {
        var req = new ParcelStatusUpdateRequest("PARCEL001", Parcel.ParcelStatus.IN_TRANSIT, null, null);

        mvc.perform(put("/api/v1/parcels/PARCEL001/status").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(parcelService);
    }
}

