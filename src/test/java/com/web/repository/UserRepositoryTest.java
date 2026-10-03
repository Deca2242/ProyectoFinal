package com.web.repository;

import com.web.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;


@DisplayName("UserRepository Integration Tests")
class UserRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    private User driver1;
    private User driver2;
    private User clerk;
    private User dispatcher;
    private User passenger1;
    private User passenger2;
    private User admin;
    private User inactiveDriver;

    @BeforeEach
    void setUp() {
        entityManager.clear();

        // Crear conductores activos
        driver1 = User.builder()
                .name("Juan Conductor")
                .email("juan.conductor@bus.com")
                .phone("3001234567")
                .role(User.Role.DRIVER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword1")
                .build();

        driver2 = User.builder()
                .name("María Conductora")
                .email("maria.conductora@bus.com")
                .phone("3007654321")
                .role(User.Role.DRIVER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword2")
                .build();

        // Conductor inactivo
        inactiveDriver = User.builder()
                .name("Pedro Inactivo")
                .email("pedro.inactivo@bus.com")
                .phone("3009999999")
                .role(User.Role.DRIVER)
                .status(User.Status.INACTIVE)
                .passwordHash("$2a$10$hashedpassword3")
                .build();

        // Taquillero
        clerk = User.builder()
                .name("Ana Taquillera")
                .email("ana.taquillera@bus.com")
                .phone("3002345678")
                .role(User.Role.CLERK)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword4")
                .build();

        // Despachador
        dispatcher = User.builder()
                .name("Carlos Despachador")
                .email("carlos.despachador@bus.com")
                .phone("3003456789")
                .role(User.Role.DISPATCHER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword5")
                .build();

        // Pasajeros
        passenger1 = User.builder()
                .name("Laura Pasajera")
                .email("laura.pasajera@example.com")
                .phone("3004567890")
                .role(User.Role.PASSENGER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword6")
                .build();

        passenger2 = User.builder()
                .name("Jorge Pasajero")
                .email("jorge.pasajero@example.com")
                .phone("3005678901")
                .role(User.Role.PASSENGER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword7")
                .build();

        // Admin
        admin = User.builder()
                .name("Admin Principal")
                .email("admin@bus.com")
                .phone("3006789012")
                .role(User.Role.ADMIN)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword8")
                .build();

        entityManager.persist(driver1);
        entityManager.persist(driver2);
        entityManager.persist(inactiveDriver);
        entityManager.persist(clerk);
        entityManager.persist(dispatcher);
        entityManager.persist(passenger1);
        entityManager.persist(passenger2);
        entityManager.persist(admin);
        entityManager.flush();
    }

    @Test
    @DisplayName("Debe encontrar usuario por email")
    void shouldFindUserByEmail() {
        // When
        Optional<User> result = userRepository.findByEmail("juan.conductor@bus.com");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Juan Conductor");
        assertThat(result.get().getRole()).isEqualTo(User.Role.DRIVER);
    }

    @Test
    @DisplayName("Debe retornar Optional vacío cuando el email no existe")
    void shouldReturnEmptyWhenEmailNotFound() {
        // When
        Optional<User> result = userRepository.findByEmail("noexiste@bus.com");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Debe verificar si un email existe")
    void shouldCheckIfEmailExists() {
        // When
        boolean exists = userRepository.existsByEmail("laura.pasajera@example.com");
        boolean notExists = userRepository.existsByEmail("noregistrado@example.com");

        // Then
        assertThat(exists).isTrue();
        assertThat(notExists).isFalse();
    }

    @Test
    @DisplayName("Debe encontrar usuarios por rol")
    void shouldFindUsersByRole() {
        // When
        List<User> drivers = userRepository.findByRole(User.Role.DRIVER);
        List<User> passengers = userRepository.findByRole(User.Role.PASSENGER);
        List<User> admins = userRepository.findByRole(User.Role.ADMIN);

        // Then
        assertThat(drivers).hasSize(3); // 2 activos + 1 inactivo
        assertThat(passengers).hasSize(2);
        assertThat(admins).hasSize(1);
    }

    @Test
    @DisplayName("Debe encontrar conductores activos disponibles")
    void shouldFindAvailableDrivers() {
        // When
        List<User> availableDrivers = userRepository.findAvailableDrivers();

        // Then
        assertThat(availableDrivers).hasSize(2);
        assertThat(availableDrivers).extracting(User::getName)
                .containsExactlyInAnyOrder("Juan Conductor", "María Conductora");
        assertThat(availableDrivers).allMatch(user -> user.getStatus() == User.Status.ACTIVE);
    }

    @Test
    @DisplayName("Debe encontrar despachadores activos disponibles")
    void shouldFindAvailableDispatchers() {
        // When
        List<User> dispatchers = userRepository.findAvailableDispatchers();

        // Then
        assertThat(dispatchers).hasSize(1);
        assertThat(dispatchers.get(0).getName()).isEqualTo("Carlos Despachador");
        assertThat(dispatchers.get(0).getRole()).isEqualTo(User.Role.DISPATCHER);
    }

    @Test
    @DisplayName("Debe encontrar empleados que manejan efectivo (CLERK y DRIVER)")
    void shouldFindCashHandlers() {
        // When
        List<User> cashHandlers = userRepository.findCashHandlers();

        // Then
        assertThat(cashHandlers).hasSize(3); // 2 drivers activos + 1 clerk
        assertThat(cashHandlers).extracting(User::getRole)
                .containsOnly(User.Role.DRIVER, User.Role.CLERK);
        assertThat(cashHandlers).allMatch(user -> user.getStatus() == User.Status.ACTIVE);
    }

    @Test
    @DisplayName("Debe retornar lista vacía cuando no hay usuarios del rol especificado")
    void shouldReturnEmptyListWhenNoUsersOfRole() {
        // Given - eliminar todos los admins
        entityManager.remove(admin);
        entityManager.flush();

        // When
        List<User> admins = userRepository.findByRole(User.Role.ADMIN);

        // Then
        assertThat(admins).isEmpty();
    }
}
