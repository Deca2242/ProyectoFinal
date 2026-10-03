package com.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.common.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.access.AccessDeniedException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    // El ObjectMapper de Spring serializa LocalDateTime igual que el resto de la API
    private final ObjectMapper objectMapper;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Endpoints públicos
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/routes", "/api/v1/routes/*", "/api/v1/routes/*/stops").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/routes/*/fares").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/trips", "/api/v1/trips/*", "/api/v1/trips/*/seats").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/parcels/*/track").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        // Observabilidad: el healthcheck es público, el resto de Actuator solo ADMIN
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // Admin - Gestión de catálogos
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/routes").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/routes/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/routes/*/stops/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/routes/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/buses").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/buses/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/buses/*/seats/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/buses/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/buses/**").hasAnyRole("ADMIN", "DISPATCHER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/routes/*/fares").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/fares/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/fares/*").hasRole("ADMIN")

                        // Admin - Gestión de trips
                        .requestMatchers(HttpMethod.POST, "/api/v1/trips").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/trips/*/status").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/trips/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/trips/*").hasRole("ADMIN")

                        // Dispatch
                        .requestMatchers(HttpMethod.POST, "/api/v1/trips/*/assign").hasRole("DISPATCHER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/trips/*/boarding/**").hasRole("DISPATCHER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/trips/*/assignment").hasAnyRole("DISPATCHER", "DRIVER")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/trips/*/assignment").hasRole("DISPATCHER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/trips/*/depart").hasRole("DRIVER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/trips/*/arrive").hasRole("DRIVER")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/trips/*/platform").hasRole("DISPATCHER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/trips/*/overbooking/approve").hasRole("DISPATCHER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/trips/*/baggage").hasAnyRole("DISPATCHER", "DRIVER", "CLERK")
                        .requestMatchers(HttpMethod.GET, "/api/v1/trips/*/occupancy").hasAnyRole("DISPATCHER", "ADMIN")

                        // Asignaciones del usuario autenticado, overbooking por ruta y franja, incidentes
                        .requestMatchers(HttpMethod.GET, "/api/v1/assignments/me").hasRole("DRIVER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/assignments").hasRole("DISPATCHER")
                        .requestMatchers("/api/v1/routes/*/overbooking-policies", "/api/v1/overbooking-policies/*").hasAnyRole("DISPATCHER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/incidents").hasAnyRole("DRIVER", "DISPATCHER", "CLERK")
                        .requestMatchers(HttpMethod.GET, "/api/v1/incidents", "/api/v1/incidents/*").hasAnyRole("ADMIN", "DISPATCHER", "CLERK", "DRIVER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/incidents/*/resolve").hasAnyRole("ADMIN", "DISPATCHER")

                        // Parcels
                        .requestMatchers(HttpMethod.GET, "/api/v1/parcels").hasAnyRole("CLERK", "DISPATCHER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/parcels").hasAnyRole("CLERK", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/parcels/*/status").hasAnyRole("DRIVER", "CLERK")
                        .requestMatchers(HttpMethod.POST, "/api/v1/parcels/*/status").hasAnyRole("DRIVER", "CLERK")
                        .requestMatchers(HttpMethod.POST, "/api/v1/parcels/*/deliver").hasAnyRole("DRIVER", "CLERK")
                        .requestMatchers(HttpMethod.POST, "/api/v1/parcels/*/reopen").hasAnyRole("DISPATCHER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/trips/*/parcels").hasAnyRole("DRIVER", "CLERK", "DISPATCHER", "ADMIN")

                        // Payments
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/confirm").hasAnyRole("CLERK", "DRIVER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/tickets/*/receipt").hasAnyRole("PASSENGER", "CLERK", "ADMIN", "DISPATCHER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/cash/close").hasAnyRole("CLERK", "DRIVER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/cash/closes").hasAnyRole("CLERK", "DRIVER", "ADMIN")

                        // Sincronización offline
                        .requestMatchers(HttpMethod.POST, "/api/v1/sync/tickets").hasAnyRole("CLERK", "DRIVER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/sync/boardings").hasAnyRole("DRIVER", "DISPATCHER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/sync/conflicts").hasAnyRole("CLERK", "DRIVER", "DISPATCHER", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/sync/conflicts/*/resolve").hasAnyRole("CLERK", "DISPATCHER", "ADMIN")

                        // Tickets - requiere autenticación
                        .requestMatchers(HttpMethod.POST, "/api/v1/tickets/qr/*/board").hasAnyRole("DRIVER", "DISPATCHER")
                        // Equipaje de un ticket: registro en taquilla; la consulta queda para cualquier autenticado
                        .requestMatchers(HttpMethod.POST, "/api/v1/tickets/*/baggage").hasAnyRole("CLERK", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/tickets/*/baggage").hasRole("CLERK")
                        .requestMatchers("/api/v1/trips/*/seats/*/hold").authenticated()
                        .requestMatchers("/api/v1/trips/*/tickets").authenticated()
                        .requestMatchers("/api/v1/tickets/**").authenticated()

                        // Notificaciones propias (consultar y marcar como leídas)
                        .requestMatchers("/api/v1/notifications/me", "/api/v1/notifications/*/read").authenticated()

                        // Perfil del usuario autenticado (la gestión de usuarios va bajo /api/v1/admin/**)
                        .requestMatchers("/api/v1/users/me", "/api/v1/users/me/**").authenticated()

                        // Resto requiere autenticación
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(this::handleAuthenticationError)
                        .accessDeniedHandler(this::handleAccessDeniedError)
                );

        return http.build();
    }

    // 401 y 403 de la cadena de seguridad usan el mismo ErrorResponse que GlobalExceptionHandler
    private void handleAuthenticationError(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "No autenticado",
                "Se requiere un token válido para acceder a este recurso.");
    }

    private void handleAccessDeniedError(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException {
        writeError(response, HttpServletResponse.SC_FORBIDDEN, "Acceso denegado",
                "No tienes los permisos suficientes para realizar esta acción.");
    }

    private void writeError(HttpServletResponse response, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ErrorResponse body = new ErrorResponse(status, error, message, LocalDateTime.now(), null);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
