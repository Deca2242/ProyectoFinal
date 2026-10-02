package com.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
                        .requestMatchers(HttpMethod.GET, "/api/v1/trips", "/api/v1/trips/*", "/api/v1/trips/*/seats").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/parcels/*/track").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()

                        // Admin - Gestión de catálogos
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/routes").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/routes/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/routes/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/buses").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/buses/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/buses/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/buses/**").hasAnyRole("ADMIN", "DISPATCHER")

                        // Admin - Gestión de trips
                        .requestMatchers(HttpMethod.POST, "/api/v1/trips").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/trips/*/status").hasRole("ADMIN")
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

                        // Parcels
                        .requestMatchers(HttpMethod.GET, "/api/v1/parcels").hasAnyRole("CLERK", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/parcels").hasAnyRole("CLERK", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/parcels/*/status").hasAnyRole("DRIVER", "CLERK")
                        .requestMatchers(HttpMethod.POST, "/api/v1/parcels/*/deliver").hasAnyRole("DRIVER", "CLERK")

                        // Payments
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/confirm").hasRole("CLERK")
                        .requestMatchers(HttpMethod.POST, "/api/v1/cash/close").hasAnyRole("CLERK", "DRIVER")

                        // Sincronización offline
                        .requestMatchers(HttpMethod.POST, "/api/v1/sync/tickets").hasAnyRole("CLERK", "DRIVER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/sync/boardings").hasAnyRole("DRIVER", "DISPATCHER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/sync/conflicts").hasAnyRole("CLERK", "DRIVER", "DISPATCHER", "ADMIN")

                        // Tickets - requiere autenticación
                        .requestMatchers(HttpMethod.POST, "/api/v1/tickets/qr/*/board").hasAnyRole("DRIVER", "DISPATCHER")
                        .requestMatchers("/api/v1/trips/*/seats/*/hold").authenticated()
                        .requestMatchers("/api/v1/trips/*/tickets").authenticated()
                        .requestMatchers("/api/v1/tickets/**").authenticated()

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

    private void handleAuthenticationError(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("error", "No autenticado", "message", "Se requiere un token válido para acceder a este recurso.")));
    }


    private void handleAccessDeniedError(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("error", "Acceso denegado", "message", "No tienes los permisos suficientes para realizar esta acción.")));
    }
}

