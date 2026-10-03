package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.dto.notification.PlatformUpdateRequest;
import com.web.dto.notification.PlatformUpdateResponse;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.service.dispatch.PlatformService;
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

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PlatformController.class)
@Import(SecurityConfig.class)
class PlatformControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private PlatformService platformService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    // Verifica que un DISPATCHER pueda cambiar el andén
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void updatePlatform_shouldReturn200() throws Exception {
        when(platformService.updatePlatform(1L, "A3"))
                .thenReturn(new PlatformUpdateResponse(1L, Trip.TripStatus.SCHEDULED, "A3", "B1", true));

        mvc.perform(put("/api/v1/trips/1/platform").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new PlatformUpdateRequest("A3"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(1))
                .andExpect(jsonPath("$.platform").value("A3"))
                .andExpect(jsonPath("$.previousPlatform").value("B1"))
                .andExpect(jsonPath("$.changed").value(true));

        verify(platformService).updatePlatform(1L, "A3");
    }

    // Verifica la validación del andén (obligatorio y de máximo 20 caracteres)
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"platform\":\"\"}", "{\"platform\":\"   \"}",
            "{\"platform\":\"ANDEN-MUY-LARGO-12345\"}"})
    @WithMockUser(roles = "DISPATCHER")
    void updatePlatform_shouldReturn400WhenInvalidBody(String body) throws Exception {
        mvc.perform(put("/api/v1/trips/1/platform").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(platformService);
    }

    // Verifica que un estado de viaje no válido responda 400
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void updatePlatform_shouldReturn400WhenTripAlreadyDeparted() throws Exception {
        when(platformService.updatePlatform(1L, "A3")).thenThrow(new BusinessException(
                "Solo se puede cambiar el andén de un viaje programado o en abordaje",
                HttpStatus.BAD_REQUEST, "INVALID_TRIP_STATUS"));

        mvc.perform(put("/api/v1/trips/1/platform").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new PlatformUpdateRequest("A3"))))
                .andExpect(status().isBadRequest());
    }

    // Verifica 404 cuando el viaje no existe
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void updatePlatform_shouldReturn404WhenTripNotFound() throws Exception {
        when(platformService.updatePlatform(99L, "A3")).thenThrow(new ResourceNotFoundException("Viaje", 99L));

        mvc.perform(put("/api/v1/trips/99/platform").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new PlatformUpdateRequest("A3"))))
                .andExpect(status().isNotFound());
    }

    // Verifica que otros roles no puedan cambiar el andén
    @ParameterizedTest
    @ValueSource(strings = {"PASSENGER", "DRIVER", "CLERK", "ADMIN"})
    void updatePlatform_shouldReturn403ForOtherRoles(String role) throws Exception {
        mvc.perform(put("/api/v1/trips/1/platform").with(csrf())
                        .with(user("user").roles(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new PlatformUpdateRequest("A3"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(platformService);
    }

    // Verifica que sin autenticación se responda 401
    @Test
    void updatePlatform_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(put("/api/v1/trips/1/platform").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new PlatformUpdateRequest("A3"))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(platformService);
    }
}
