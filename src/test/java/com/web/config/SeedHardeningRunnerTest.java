package com.web.config;

import com.web.entity.Config;
import com.web.entity.User;
import com.web.repository.ConfigRepository;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// Endurecimiento de los datos semilla en producción
@ExtendWith(MockitoExtension.class)
class SeedHardeningRunnerTest {

    private static final String PASSWORD = "Adm1n-Segura-2026";

    @Mock
    private UserRepository userRepository;
    @Mock
    private ConfigRepository configRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private SeedHardeningRunner runner(String password) {
        return new SeedHardeningRunner(userRepository, configRepository, passwordEncoder, password);
    }

    private static User user(String email, User.Role role, User.Status status) {
        return User.builder().email(email).role(role).status(status).passwordHash("$hash-semilla").build();
    }

    private static void run(SeedHardeningRunner runner) throws Exception {
        runner.run(new DefaultApplicationArguments());
    }

    @Test
    void shouldHarden_DeactivateSeedUsersSetAdminPasswordAndMarkConfig() throws Exception {
        // Given: semilla completa (un usuario ya estaba inactivo) más un usuario real que no se toca
        Map<String, User> users = new HashMap<>();
        for (String email : SeedHardeningRunner.SEED_USER_EMAILS) {
            users.put(email, user(email, User.Role.PASSENGER, User.Status.ACTIVE));
        }
        users.get("driver3@transport.com").setStatus(User.Status.INACTIVE);
        User admin = user(SeedHardeningRunner.SEED_ADMIN_EMAIL, User.Role.ADMIN, User.Status.ACTIVE);
        users.put(admin.getEmail(), admin);
        when(configRepository.existsByConfigKey("seed.hardened.at")).thenReturn(false);
        when(userRepository.findByEmail(anyString())).thenAnswer(inv -> Optional.ofNullable(users.get(inv.<String>getArgument(0))));
        when(passwordEncoder.encode(PASSWORD)).thenReturn("$2a$10$hash-produccion");

        // When
        run(runner(PASSWORD));

        // Then: todos los usuarios de prueba quedan inactivos
        assertThat(SeedHardeningRunner.SEED_USER_EMAILS)
                .allSatisfy(email -> assertThat(users.get(email).getStatus()).isEqualTo(User.Status.INACTIVE));
        // ...el admin sigue activo con la contraseña de la variable de entorno cifrada
        assertThat(admin.getStatus()).isEqualTo(User.Status.ACTIVE);
        assertThat(admin.getPasswordHash()).isEqualTo("$2a$10$hash-produccion");
        assertThat(admin.getPasswordChangedAt()).isNotNull();
        verify(userRepository).save(admin);
        // 10 desactivados + el admin (el que ya estaba inactivo no se vuelve a guardar)
        verify(userRepository, times(11)).save(any(User.class));

        // ...y queda la marca de idempotencia
        ArgumentCaptor<Config> captor = ArgumentCaptor.forClass(Config.class);
        verify(configRepository).save(captor.capture());
        assertThat(captor.getValue().getConfigKey()).isEqualTo("seed.hardened.at");
        assertThat(captor.getValue().getConfigValue()).isNotBlank();
        assertThat(captor.getValue().getDataType()).isEqualTo(Config.DataType.STRING);
    }

    @Test
    void shouldHarden_WhenAlreadyHardened_DoNothing() throws Exception {
        // Given
        when(configRepository.existsByConfigKey("seed.hardened.at")).thenReturn(true);

        // When
        run(runner(PASSWORD));

        // Then
        verifyNoInteractions(userRepository, passwordEncoder);
        verify(configRepository, never()).save(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldFailStartup_WithoutAdminInitialPassword(String password) {
        // When/Then: mensaje claro con la variable a definir, sin tocar la BD
        assertThatThrownBy(() -> run(runner(password)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_INITIAL_PASSWORD");
        verifyNoInteractions(userRepository, configRepository, passwordEncoder);
    }

    @Test
    void shouldFailStartup_WithTooShortAdminInitialPassword() {
        // When/Then
        assertThatThrownBy(() -> run(runner("corta")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("al menos 8");
        verifyNoInteractions(userRepository, configRepository, passwordEncoder);
    }

    @Test
    void shouldHarden_WithoutSeedAdmin_StillDeactivateSeedUsers() throws Exception {
        // Given: BD sin el admin sembrado ni el resto de usuarios de prueba salvo uno
        User clerk = user("clerk1@transport.com", User.Role.CLERK, User.Status.ACTIVE);
        when(configRepository.existsByConfigKey("seed.hardened.at")).thenReturn(false);
        when(userRepository.findByEmail(anyString())).thenAnswer(inv ->
                "clerk1@transport.com".equals(inv.getArgument(0)) ? Optional.of(clerk) : Optional.empty());

        // When
        run(runner(PASSWORD));

        // Then
        assertThat(clerk.getStatus()).isEqualTo(User.Status.INACTIVE);
        verify(userRepository, times(1)).save(any(User.class));
        verifyNoInteractions(passwordEncoder);
        verify(configRepository).save(any(Config.class));
    }

    @Test
    void shouldOnlyBeActive_WhenSeedHardenPropertyIsTrue() {
        // When
        ConditionalOnProperty condition = SeedHardeningRunner.class.getAnnotation(ConditionalOnProperty.class);

        // Then: solo el perfil prod (app.seed.harden=true) lo activa
        assertThat(condition.name()).containsExactly("app.seed.harden");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }
}
