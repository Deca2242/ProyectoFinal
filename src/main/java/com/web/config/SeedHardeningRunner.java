package com.web.config;

import com.web.entity.Config;
import com.web.entity.User;
import com.web.repository.ConfigRepository;
import com.web.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Endurecimiento de los datos semilla en producción (app.seed.harden=true, perfil prod).
 * V2 siembra usuarios de prueba con una contraseña conocida (V5): al arrancar se desactivan todos salvo el ADMIN
 * sembrado, cuya contraseña pasa a ser la de app.admin.initial-password (ADMIN_INITIAL_PASSWORD) cifrada con BCrypt.
 * Si la propiedad falta o está vacía la aplicación no arranca. Solo se ejecuta una vez: deja la marca
 * "seed.hardened.at" en config y en los arranques siguientes no toca nada.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.seed.harden", havingValue = "true")
public class SeedHardeningRunner implements ApplicationRunner {

    static final String HARDENED_MARK_KEY = "seed.hardened.at";
    static final String SEED_ADMIN_EMAIL = "cmbarrera@gmail.com";

    // Usuarios de prueba sembrados por V2 (excepto el admin)
    static final List<String> SEED_USER_EMAILS = List.of(
            "juan.perez@email.com",
            "maria.lopez@email.com",
            "carlos.rodriguez@email.com",
            "ana.garcia@email.com",
            "clerk1@transport.com",
            "clerk2@transport.com",
            "driver1@transport.com",
            "driver2@transport.com",
            "driver3@transport.com",
            "dispatcher1@transport.com",
            "dispatcher2@transport.com");

    static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final ConfigRepository configRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminInitialPassword;

    public SeedHardeningRunner(UserRepository userRepository,
                               ConfigRepository configRepository,
                               PasswordEncoder passwordEncoder,
                               @Value("${app.admin.initial-password:}") String adminInitialPassword) {
        this.userRepository = userRepository;
        this.configRepository = configRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminInitialPassword = adminInitialPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminInitialPassword == null || adminInitialPassword.isBlank()) {
            throw new IllegalStateException("app.seed.harden=true exige la contraseña inicial del administrador: "
                    + "define la variable de entorno ADMIN_INITIAL_PASSWORD (propiedad app.admin.initial-password)");
        }
        if (adminInitialPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("ADMIN_INITIAL_PASSWORD (app.admin.initial-password) debe tener al menos "
                    + MIN_PASSWORD_LENGTH + " caracteres");
        }

        if (configRepository.existsByConfigKey(HARDENED_MARK_KEY)) {
            log.info("Los datos semilla ya estaban endurecidos ({}): no se modifica nada", HARDENED_MARK_KEY);
            return;
        }

        int deactivated = 0;
        for (String email : SEED_USER_EMAILS) {
            User user = userRepository.findByEmail(email).orElse(null);
            if (user != null && user.getStatus() == User.Status.ACTIVE) {
                user.setStatus(User.Status.INACTIVE);
                userRepository.save(user);
                deactivated++;
            }
        }

        LocalDateTime now = LocalDateTime.now();
        userRepository.findByEmail(SEED_ADMIN_EMAIL).ifPresentOrElse(admin -> {
            admin.setPasswordHash(passwordEncoder.encode(adminInitialPassword));
            // Invalida cualquier token emitido con la contraseña de prueba
            admin.setPasswordChangedAt(now);
            userRepository.save(admin);
        }, () -> log.warn("No existe el administrador sembrado {}: no se fijó la contraseña inicial", SEED_ADMIN_EMAIL));

        configRepository.save(Config.builder()
                .configKey(HARDENED_MARK_KEY)
                .configValue(now.toString())
                .description("Fecha en que se desactivaron los usuarios de prueba y se fijó la contraseña del admin sembrado")
                .dataType(Config.DataType.STRING)
                .updatedAt(now)
                .build());

        log.info("Datos semilla endurecidos: {} usuario(s) de prueba desactivado(s) y contraseña del admin actualizada",
                deactivated);
    }
}
