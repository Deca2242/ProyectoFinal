package com.web.config;

import com.web.util.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        when(jwtTokenProvider.validateToken(TOKEN)).thenReturn(false);

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtTokenProvider, never()).extractEmail(anyString());
        verifyNoInteractions(userDetailsService);
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldAuthenticate_WithValidTokenOfActiveUser() throws Exception {
        // Given
        request.addHeader("Authorization", "Bearer " + TOKEN);
        UserDetails userDetails = User.withUsername(EMAIL)
                .password("hash")
                .authorities("ROLE_DRIVER")
                .build();
        when(jwtTokenProvider.validateToken(TOKEN)).thenReturn(true);
        when(jwtTokenProvider.extractEmail(TOKEN)).thenReturn(EMAIL);
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
    void shouldNotAuthenticate_WithValidTokenOfDisabledUser() throws Exception {
        // Given: regresión de seguridad, un usuario desactivado con token vigente no debe autenticarse
        request.addHeader("Authorization", "Bearer " + TOKEN);
        UserDetails disabledUser = User.withUsername(EMAIL)
                .password("hash")
                .authorities("ROLE_DRIVER")
                .disabled(true)
                .build();
        when(jwtTokenProvider.validateToken(TOKEN)).thenReturn(true);
        when(jwtTokenProvider.extractEmail(TOKEN)).thenReturn(EMAIL);
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(disabledUser);

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldNotAuthenticate_WhenLoadUserThrows_AndContinueChain() throws Exception {
        // Given
        request.addHeader("Authorization", "Bearer " + TOKEN);
        when(jwtTokenProvider.validateToken(TOKEN)).thenReturn(true);
        when(jwtTokenProvider.extractEmail(TOKEN)).thenReturn(EMAIL);
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenThrow(new UsernameNotFoundException("Usuario no encontrado: " + EMAIL));

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldNotAuthenticate_WhenTokenProviderThrowsRuntimeException() throws Exception {
        // Given
        request.addHeader("Authorization", "Bearer " + TOKEN);
        when(jwtTokenProvider.validateToken(TOKEN)).thenThrow(new IllegalStateException("fallo inesperado"));

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userDetailsService);
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldNotAuthenticate_WithEmptyBearerToken() throws Exception {
        // Given
        request.addHeader("Authorization", "Bearer ");
        when(jwtTokenProvider.validateToken("")).thenReturn(false);

        // When
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userDetailsService, never()).loadUserByUsername(any());
        verify(filterChain, times(1)).doFilter(request, response);
    }
}
