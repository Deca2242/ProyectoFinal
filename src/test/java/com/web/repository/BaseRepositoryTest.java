package com.web.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class BaseRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("reservaciones_test")
            .withUsername("test")
            .withPassword("test");

    @PersistenceContext
    protected EntityManager entityManager;

    @BeforeEach
    @Transactional
    void cleanDatabaseBeforeTest() {
        try {

            entityManager.createNativeQuery("TRUNCATE TABLE users, routes, buses, stops, fare_rules, " +
                    "trips, seats, seat_holds, tickets, baggage, parcels, assignments, config " +
                    "RESTART IDENTITY CASCADE").executeUpdate();
            entityManager.flush();
            entityManager.clear();
        } catch (Exception e) {
            // Si falla , continuar - las tablas ya están vacías
            entityManager.clear();
        }
    }
}
