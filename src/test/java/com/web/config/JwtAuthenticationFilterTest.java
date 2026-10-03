package com.web.config;

import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.util.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String TOKEN = "token.jwt.valido";
    private static final String EMAIL = "conductor@test.com";

    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private CustomUserDetailsService userDetailsService;
    @Mock
    private FilterChain filterChain;
    @Mock
    private DecodedJWT decodedJwt;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        request = new MockHttpServletRequest("GET", "/api/v1/trips");
        request.setRemoteAddr("10.0.0.1");
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // Token válido del usuario EMAIL emitido en "issuedAt"
    private void givenValidToken(Instant issuedAt) {
        request.addHeader("Authorization", "Bearer " + TOKEN);
        when(jwtTokenProvider.verify(TOKEN)).thenReturn(decodedJwt);
        when(decodedJwt.getSubject()).thenReturn(EMAIL);
        lenient().when(decodedJwt.getIssuedAt()).thenReturn(issuedAt == null ? null : Date.from(issuedAt));
    }

    private static SecurityUser securityUser(boolean enabled, LocalDateTime passwordChangedAt) {
        return new SecurityUser(EMAIL, "hash", enabled, List.of(new SimpleGrantedAuthority("ROLE_DRIVER")),
                passwordChangedAt);
    }

    private static Instant toInstant(LocalDateTime dateTime) {
        return dateTime.atZone(ZoneId.systemDefault()).toInstant();
    }

    @Test
    void shouldNotAuthenticate_WithoutAuthorizationHeader_AndContinueChain() throws Exception {
        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtTokenProvider, userDetailsService);
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldNotAuthenticate_WithHeaderWithoutBearerPrefix() throws Exception {
        // Given
        request.addHeader("Authorization", "Basic dXN1YXJpbzpjbGF2ZQ==");

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtTokenProvider, userDetailsService);
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldNotAuthenticate_WithLowercaseBearerPrefix() throws Exception {
        // Given: el prefijo es sensible a mayúsculas
        request.addHeader("Authorization", "bearer " + TOKEN);

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtTokenProvider, userDetailsService);
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldNotAuthenticate_WithInvalidToken() throws Exception {
        // Given
        request.addHeader("Authorization", "Bearer " + TOKEN);
        when(jwtTokenProvider.verify(TOKEN)).thenThrow(new JWTDecodeException("token mal formado"));

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then: sigue sin autenticación (los endpoints protegidos responderán 401)
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userDetailsService);
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldNotAuthenticate_WithExpiredToken() throws Exception {
        // Given
        request.addHeader("Authorization", "Bearer " + TOKEN);
        when(jwtTokenProvider.verify(TOKEN)).thenThrow(new TokenExpiredException("expirado", Instant.now()));

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userDetailsService);
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldAuthenticate_WithValidTokenOfActiveUser() throws Exception {
        // Given
        givenValidToken(Instant.now());
        SecurityUser userDetails = securityUser(true, null);
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(userDetails);

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isSameAs(userDetails);
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_DRIVER");
        assertThat(authentication.getDetails()).isInstanceOf(WebAuthenticationDetails.class);
        assertThat(((WebAuthenticationDetails) authentication.getDetails()).getRemoteAddress())
                .isEqualTo("10.0.0.1");
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldAuthenticate_WithPlainUserDetails() throws Exception {
        // Given: un UserDetails sin fecha de cambio de contraseña no se compara con "iat"
        givenValidToken(Instant.now());
        UserDetails userDetails = User.withUsername(EMAIL).password("hash").authorities("ROLE_DRIVER").build();
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(userDetails);

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldReturn401_WithValidTokenOfDisabledUser_AndStopChain() throws Exception {
        // Given: regresión de seguridad, un usuario desactivado con token vigente no debe autenticarse
        givenValidToken(Instant.now());
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(securityUser(false, null));

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then: 401 con el ErrorResponse uniforme y la cadena no continúa
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("error").asText()).isEqualTo("No autenticado");
        assertThat(body.get("message").asText()).isEqualTo("Usuario inactivo");
        assertThat(body.hasNonNull("timestamp")).isTrue();
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void shouldReturn401_WithTokenIssuedBeforePasswordChange() throws Exception {
        // Given: la contraseña se cambió 10 minutos después de emitir el token
        LocalDateTime changedAt = LocalDateTime.now().minusMinutes(5);
        givenValidToken(toInstant(changedAt.minusMinutes(10)));
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(securityUser(true, changedAt));

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(objectMapper.readTree(response.getContentAsString()).get("message").asText())
                .contains("cambio de contraseña");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void shouldAuthenticate_WithTokenIssuedAfterPasswordChange() throws Exception {
        // Given: nuevo login después del cambio
        LocalDateTime changedAt = LocalDateTime.now().minusMinutes(5);
        givenValidToken(toInstant(changedAt.plusMinutes(1)));
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(securityUser(true, changedAt));

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldAuthenticate_WithTokenIssuedInTheSameSecondAsPasswordChange() throws Exception {
        // Given: "iat" tiene precisión de segundos; un login en el mismo segundo del cambio sigue siendo válido
        LocalDateTime changedAt = LocalDateTime.of(2026, 5, 1, 10, 0, 0, 700_000_000);
        givenValidToken(toInstant(changedAt.withNano(0)));
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(securityUser(true, changedAt));

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldReturn401_WithTokenWithoutIssuedAtAfterPasswordChange() throws Exception {
        // Given: sin "iat" no se puede saber si es anterior al cambio, se rechaza
        givenValidToken(null);
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenReturn(securityUser(true, LocalDateTime.now().minusDays(1)));

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void shouldNotAuthenticate_WhenUserDoesNotExist_AndContinueChain() throws Exception {
        // Given
        givenValidToken(Instant.now());
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenThrow(new UsernameNotFoundException("Usuario no encontrado: " + EMAIL));

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldPropagate_UnexpectedExceptions_LikeDataAccessErrors() {
        // Given: un fallo de BD no es un token inválido, no se oculta como petición anónima
        givenValidToken(Instant.now());
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenThrow(new DataAccessResourceFailureException("BD caída"));

        // When/Then
        assertThatThrownBy(() -> jwtAuthenticationFilter.doFilter(request, response, filterChain))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(filterChain);
    }

    @Test
    void shouldPropagate_RuntimeExceptionFromTokenProvider() {
        // Given
        request.addHeader("Authorization", "Bearer " + TOKEN);
        when(jwtTokenProvider.verify(TOKEN)).thenThrow(new IllegalStateException("fallo inesperado"));

        // When/Then
        assertThatThrownBy(() -> jwtAuthenticationFilter.doFilter(request, response, filterChain))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(userDetailsService, filterChain);
    }

    @Test
    void shouldNotAuthenticate_WithEmptyBearerToken() throws Exception {
        // Given
        request.addHeader("Authorization", "Bearer ");
        when(jwtTokenProvider.verify("")).thenThrow(new JWTDecodeException("vacío"));

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userDetailsService, never()).loadUserByUsername(any());
        verify(filterChain, times(1)).doFilter(request, response);
    }
}
