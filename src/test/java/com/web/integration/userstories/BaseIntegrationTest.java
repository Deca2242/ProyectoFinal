package com.web.integration.userstories;

import com.web.dto.auth.Login.RegisterRequest;
import com.web.entity.User;
import com.web.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
public abstract class BaseIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("proyecto_final_test")
            .withUsername("test")
            .withPassword("test");

    @Autowired
    private UserRepository baseUserRepository;

    @Autowired
    private PasswordEncoder basePasswordEncoder;

    // El registro público solo crea pasajeros: los usuarios con otros roles (ADMIN, CLERK,
    // DRIVER, DISPATCHER) se crean directamente en la BD, como lo haría un administrador
    protected User createUser(RegisterRequest request) {
        User user = User.builder()
                .name(request.name())
                .email(request.email())
                .phone(request.phone())
                .role(request.role())
                .passwordHash(basePasswordEncoder.encode(request.password()))
                .status(User.Status.ACTIVE)
                .build();
        return baseUserRepository.save(user);
    }
}
