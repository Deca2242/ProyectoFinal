package com.web.config;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.common.ErrorResponse;
import com.web.util.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;



@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    static final String INACTIVE_USER_MESSAGE = "Usuario inactivo";
    static final String TOKEN_REVOKED_MESSAGE =
            "El token fue emitido antes del último cambio de contraseña. Inicia sesión de nuevo.";

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;

    // El ObjectMapper de Spring serializa el ErrorResponse igual que el resto de la API
    private final ObjectMapper objectMapper;

    // Extrae el token JWT del header, lo valida y establece la autenticación en el contexto
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractTokenFromHeader(request);
        if (token != null) {
            try {
                DecodedJWT jwt = jwtTokenProvider.verify(token);
                UserDetails userDetails = userDetailsService.loadUserByUsername(jwt.getSubject());

                // Un usuario desactivado no debe seguir operando aunque su token no haya expirado
                if (!userDetails.isEnabled()) {
                    writeUnauthorized(response, INACTIVE_USER_MESSAGE);
                    return;
                }

                // Cambiar la contraseña invalida los tokens emitidos antes
                if (issuedBeforePasswordChange(jwt, userDetails)) {
                    writeUnauthorized(response, TOKEN_REVOKED_MESSAGE);
                    return;
                }

                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                    );

                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JWTVerificationException | UsernameNotFoundException ex) {
                // Token inválido/expirado o usuario inexistente: la petición sigue sin autenticación
                // (los endpoints protegidos responden 401). Cualquier otro error (p. ej. de BD) se propaga
                log.debug("Token JWT rechazado: {}", ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    // El claim "iat" tiene precisión de segundos: se compara con el cambio de contraseña truncado al segundo
    private static boolean issuedBeforePasswordChange(DecodedJWT jwt, UserDetails userDetails) {
        if (!(userDetails instanceof SecurityUser securityUser) || securityUser.getPasswordChangedAt() == null) {
            return false;
        }
        Date issuedAt = jwt.getIssuedAt();
        if (issuedAt == null) {
            return true;
        }
        LocalDateTime changedAt = securityUser.getPasswordChangedAt();
        Instant changedAtInstant = changedAt.atZone(ZoneId.systemDefault()).toInstant().truncatedTo(ChronoUnit.SECONDS);
        return issuedAt.toInstant().isBefore(changedAtInstant);
    }

    // 401 con el mismo ErrorResponse que SecurityConfig; corta la cadena de filtros
    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ErrorResponse body = new ErrorResponse(HttpServletResponse.SC_UNAUTHORIZED, "No autenticado",
                message, LocalDateTime.now(), null);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    // Extrae el token JWT del header Authorization
    private String extractTokenFromHeader(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
