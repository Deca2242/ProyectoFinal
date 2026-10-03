package com.web.config;

import com.web.entity.User;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CustomUserDetailsService customUserDetailsService;

    private User buildUser(User.Role role, User.Status status) {
        return User.builder()
                .id(1L)
                .name("Usuario Prueba")
                .email("usuario@test.com")
                .phone("3001234567")
                .passwordHash("$2a$10$hashDePrueba")
                .role(role)
                .status(status)
                .build();
    }

    @Test
    void shouldLoadUserByUsername_WithActiveUser_ReturnEnabledUserDetails() {
        // Given
        User user = buildUser(User.Role.DISPATCHER, User.Status.ACTIVE);
        when(userRepository.findByEmail("usuario@test.com")).thenReturn(Optional.of(user));

        // When
        UserDetails details = customUserDetailsService.loadUserByUsername("usuario@test.com");

        // Then
        assertThat(details.getUsername()).isEqualTo("usuario@test.com");
        assertThat(details.getPassword()).isEqualTo("$2a$10$hashDePrueba");
        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_DISPATCHER");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
        verify(userRepository).findByEmail("usuario@test.com");
    }

    @Test
    void shouldLoadUserByUsername_MapEveryRoleWithRolePrefix() {
        // When / Then: cada rol del dominio se traduce a ROLE_<rol>
        for (User.Role role : User.Role.values()) {
            when(userRepository.findByEmail("usuario@test.com"))
                    .thenReturn(Optional.of(buildUser(role, User.Status.ACTIVE)));

            UserDetails details = customUserDetailsService.loadUserByUsername("usuario@test.com");

            assertThat(details.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_" + role.name());
        }
    }

    @Test
    void shouldLoadUserByUsername_WithInactiveUser_ReturnDisabledUserDetails() {
        // Given
        User user = buildUser(User.Role.PASSENGER, User.Status.INACTIVE);
        when(userRepository.findByEmail("usuario@test.com")).thenReturn(Optional.of(user));

        // When
        UserDetails details = customUserDetailsService.loadUserByUsername("usuario@test.com");

        // Then
        assertThat(details.isEnabled()).isFalse();
        assertThat(details.getUsername()).isEqualTo("usuario@test.com");
        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_PASSENGER");
    }

    @Test
    void shouldLoadUserByUsername_WithNonExistentEmail_ThrowUsernameNotFoundException() {
        // Given
        when(userRepository.findByEmail("noexiste@test.com")).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername("noexiste@test.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("noexiste@test.com");
    }
}
