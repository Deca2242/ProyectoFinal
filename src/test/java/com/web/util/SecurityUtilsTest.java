package com.web.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Acceso al usuario autenticado y a sus roles desde el contexto de seguridad
class SecurityUtilsTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String username, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    // ---------- currentUsername ----------

    @Test
    void shouldReturnEmptyUsername_WithoutAuthentication() {
        // Given: contexto vacío (p. ej. tarea programada)
        SecurityContextHolder.clearContext();

        // When/Then
        assertThat(SecurityUtils.currentUsername()).isEmpty();
    }

    @Test
    void shouldReturnEmptyUsername_WithUnauthenticatedToken() {
        // Given: token sin autenticar (constructor de dos argumentos)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user@test.com", "secreto"));

        // When/Then
        assertThat(SecurityUtils.currentUsername()).isEmpty();
    }

    @Test
    void shouldReturnUsername_WithAuthenticatedUser() {
        // Given
        authenticate("driver@test.com", "ROLE_DRIVER");

        // When/Then
        assertThat(SecurityUtils.currentUsername()).contains("driver@test.com");
    }

    @Test
    void shouldReturnAnonymousPrincipalName_WithAnonymousAuthentication() {
        // Given: Spring marca la autenticación anónima como autenticada
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        // When/Then
        assertThat(SecurityUtils.currentUsername()).contains("anonymousUser");
        assertThat(SecurityUtils.hasRole("DRIVER")).isFalse();
    }

    // ---------- hasRole ----------

    @Test
    void shouldNotHaveRole_WithoutAuthentication() {
        // When/Then
        assertThat(SecurityUtils.hasRole("ADMIN")).isFalse();
    }

    @Test
    void shouldNotHaveRole_WithUnauthenticatedTokenEvenIfItHasTheAuthority() {
        // Given
        TestingAuthenticationToken token = new TestingAuthenticationToken("driver@test.com", null, "ROLE_DRIVER");
        token.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(token);

        // When/Then
        assertThat(SecurityUtils.hasRole("DRIVER")).isFalse();
    }

    @Test
    void shouldHaveRole_WithMatchingRoleAuthority() {
        // Given
        authenticate("driver@test.com", "ROLE_PASSENGER", "ROLE_DRIVER");

        // When/Then
        assertThat(SecurityUtils.hasRole("DRIVER")).isTrue();
        assertThat(SecurityUtils.hasRole("PASSENGER")).isTrue();
        assertThat(SecurityUtils.hasRole("DISPATCHER")).isFalse();
    }

    @Test
    void shouldNotHaveRole_WithAuthorityWithoutRolePrefixOrDifferentCase() {
        // Given: la autoridad debe ser exactamente "ROLE_" + rol
        authenticate("driver@test.com", "DRIVER", "ROLE_clerk");

        // When/Then
        assertThat(SecurityUtils.hasRole("DRIVER")).isFalse();
        assertThat(SecurityUtils.hasRole("CLERK")).isFalse();
        assertThat(SecurityUtils.hasRole("clerk")).isTrue();
    }

    @Test
    void shouldBeNonInstantiableUtilityClass() throws Exception {
        // Given
        Constructor<SecurityUtils> constructor = SecurityUtils.class.getDeclaredConstructor();

        // Then
        assertThat(Modifier.isFinal(SecurityUtils.class.getModifiers())).isTrue();
        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
        constructor.setAccessible(true);
        assertThat(constructor.newInstance()).isNotNull();
    }
}
