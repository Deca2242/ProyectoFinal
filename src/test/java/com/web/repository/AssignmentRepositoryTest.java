package com.web.repository;

import com.web.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;


@DisplayName("AssignmentRepository Integration Tests")
class AssignmentRepositoryTest extends BaseRepositoryTest {

        @Autowired
        private TestEntityManager entityManager;

        @Autowired
        private AssignmentRepository assignmentRepository;

        private User driver1;
        private User driver2;
        private User dispatcher;
        private Trip trip1;
        private Trip trip2;
        private Trip trip3;
        private Route route;
        private Bus bus;

        @BeforeEach
        void setUp() {

                
                // Crear ruta
                route = Route.builder()
                                .code("BOG-BGA")
                                .name("Bogotá - Bucaramanga")
                                .origin("Bogotá")
                                .destination("Bucaramanga")
                                .distanceKm(new BigDecimal("398.50"))
                                .durationMin(420)
                                .isActive(true)
                                .build();
                entityManager.persist(route);

                // Crear bus
                bus = Bus.builder()
                                .plate("ABC123")
                                .capacity(40)
                                .amenities(new HashMap<>())
                                .status(Bus.BusStatus.ACTIVE)
                                .build();
                entityManager.persist(bus);

                // Crear conductores
                driver1 = User.builder()
                                .name("Juan Conductor")
                                .email("juan.conductor@bus.com")
                                .phone("3001234567")
                                .role(User.Role.DRIVER)
                                .status(User.Status.ACTIVE)
                                .passwordHash("$2a$10$hashedpassword")
                                .build();

                driver2 = User.builder()
                                .name("María Conductora")
                                .email("maria.conductora@bus.com")
                                .phone("3007654321")
                                .role(User.Role.DRIVER)
                                .status(User.Status.ACTIVE)
                                .passwordHash("$2a$10$hashedpassword")
                                .build();

                // Crear despachador
                dispatcher = User.builder()
                                .name("Carlos Despachador")
                                .email("carlos.despachador@bus.com")
                                .phone("3003456789")
                                .role(User.Role.DISPATCHER)
                                .status(User.Status.ACTIVE)
                                .passwordHash("$2a$10$hashedpassword")
                                .build();

                entityManager.persist(driver1);
                entityManager.persist(driver2);
                entityManager.persist(dispatcher);

                // Crear viajes
                LocalDate today = LocalDate.now();

                trip1 = Trip.builder()
                                .route(route)
                                .bus(bus)
                                .tripDate(today)
                                .departureTime(LocalDateTime.now().plusHours(2))
                                .arrivalEta(LocalDateTime.now().plusHours(9))
                                .status(Trip.TripStatus.SCHEDULED)
                                .build();

                trip2 = Trip.builder()
                                .route(route)
                                .bus(bus)
                                .tripDate(today)
                                .departureTime(LocalDateTime.now().plusHours(10))
                                .arrivalEta(LocalDateTime.now().plusHours(17))
                                .status(Trip.TripStatus.SCHEDULED)
                                .build();

                trip3 = Trip.builder()
                                .route(route)
                                .bus(bus)
                                .tripDate(today.plusDays(1))
                                .departureTime(LocalDateTime.now().plusDays(1).plusHours(8))
                                .arrivalEta(LocalDateTime.now().plusDays(1).plusHours(15))
                                .status(Trip.TripStatus.SCHEDULED)
                                .build();

                entityManager.persist(trip1);
                entityManager.persist(trip2);
                entityManager.persist(trip3);
                entityManager.flush();
        }

        //Buscar asignación por ID de viaje
        @Test
        @DisplayName("Debe encontrar asignación por viaje")
        void shouldFindAssignmentByTripId() {
                // Given
                Assignment assignment = Assignment.builder()
                                .trip(trip1)
                                .driver(driver1)
                                .dispatcher(dispatcher)
                                .checklistOk(true)
                                .build();
                entityManager.persist(assignment);
                entityManager.flush();

                // When
                Optional<Assignment> result = assignmentRepository.findByTripId(trip1.getId());

                // Then
                assertThat(result).isPresent();
                assertThat(result.get().getDriver().getName()).isEqualTo("Juan Conductor");
                assertThat(result.get().getChecklistOk()).isTrue();
        }

        //Buscar todas las asignaciones de un conductor específico
        @Test
        @DisplayName("Debe encontrar asignaciones por conductor")
        void shouldFindAssignmentsByDriverId() {
                // Given - asignar 2 viajes al driver1
                Assignment assignment1 = Assignment.builder()
                                .trip(trip1)
                                .driver(driver1)
                                .dispatcher(dispatcher)
                                .checklistOk(false)
                                .build();

                Assignment assignment2 = Assignment.builder()
                                .trip(trip3)
                                .driver(driver1)
                                .dispatcher(dispatcher)
                                .checklistOk(true)
                                .build();

                entityManager.persist(assignment1);
                entityManager.persist(assignment2);
                entityManager.flush();

                // When
                List<Assignment> assignments = assignmentRepository.findByDriverId(driver1.getId());

                // Then
                assertThat(assignments).hasSize(2);
                assertThat(assignments).extracting(assignment -> assignment.getTrip().getId())
                                .containsExactlyInAnyOrder(trip1.getId(), trip3.getId());
        }

        //Filtrar asignaciones de conductor por fecha específica
        @Test
        @DisplayName("Debe encontrar asignaciones de un conductor para una fecha")
        void shouldFindDriverAssignmentsForDate() {
                // Given
                Assignment assignment1 = Assignment.builder()
                                .trip(trip1)
                                .driver(driver1)
                                .dispatcher(dispatcher)
                                .checklistOk(true)
                                .build();

                Assignment assignment2 = Assignment.builder()
                                .trip(trip2)
                                .driver(driver1)
                                .dispatcher(dispatcher)
                                .checklistOk(false)
                                .build();

                // Asignación en fecha diferente
                Assignment assignment3 = Assignment.builder()
                                .trip(trip3)
                                .driver(driver1)
                                .dispatcher(dispatcher)
                                .checklistOk(true)
                                .build();

                entityManager.persist(assignment1);
                entityManager.persist(assignment2);
                entityManager.persist(assignment3);
                entityManager.flush();

                // When
                List<Assignment> todayAssignments = assignmentRepository.findDriverAssignmentsForDate(
                                driver1.getId(),
                                LocalDate.now());

                // Then
                assertThat(todayAssignments).hasSize(2); // trip1 y trip2
                assertThat(todayAssignments).extracting(assignment -> assignment.getTrip().getId())
                                .containsExactlyInAnyOrder(trip1.getId(), trip2.getId());
        }

        //Verificar disponibilidad del conductor (evitar conflictos de horario)
        @Test
        @DisplayName("Debe verificar si el conductor está disponible (sin conflictos)")
        void shouldCheckIfDriverIsAvailable() {
                // Given - driver1 asignado a trip1 (sale en 2h, llega en 9h)
                Assignment assignment = Assignment.builder()
                                .trip(trip1)
                                .driver(driver1)
                                .dispatcher(dispatcher)
                                .checklistOk(true)
                                .build();
                entityManager.persist(assignment);
                entityManager.flush();

                // When - verificar disponibilidad en horario que solapa
                boolean availableDuringTrip = assignmentRepository.isDriverAvailable(
                                driver1.getId(),
                                LocalDate.now(),
                                LocalDateTime.now().plusHours(5), // Durante el viaje
                                LocalDateTime.now().plusHours(7));

                // Verificar disponibilidad en horario que no solapa
                boolean availableAfterTrip = assignmentRepository.isDriverAvailable(
                                driver1.getId(),
                                LocalDate.now(),
                                LocalDateTime.now().plusHours(15), // Después del viaje
                                LocalDateTime.now().plusHours(20));

                // Then
                assertThat(availableDuringTrip).isFalse();
                assertThat(availableAfterTrip).isTrue();
        }

        //Buscar asignaciones que tienen checklist sin completar
        @Test
        @DisplayName("Debe encontrar asignaciones con checklist pendiente")
        void shouldFindPendingChecklists() {
                // Given - asignaciones con diferentes estados de checklist
                Assignment assignment1 = Assignment.builder()
                                .trip(trip1)
                                .driver(driver1)
                                .dispatcher(dispatcher)
                                .checklistOk(false) // Pendiente
                                .build();

                Assignment assignment2 = Assignment.builder()
                                .trip(trip2)
                                .driver(driver2)
                                .dispatcher(dispatcher)
                                .checklistOk(true) // Completado
                                .build();

                entityManager.persist(assignment1);
                entityManager.persist(assignment2);
                entityManager.flush();

                // When
                List<Assignment> pendingChecklists = assignmentRepository.findPendingChecklists(LocalDate.now());

                // Then
                assertThat(pendingChecklists).hasSize(1);
                assertThat(pendingChecklists.get(0).getTrip().getId()).isEqualTo(trip1.getId());
                assertThat(pendingChecklists.get(0).getChecklistOk()).isFalse();
        }

        //Buscar asignaciones creadas por un despachador específico
        @Test
        @DisplayName("Debe encontrar asignaciones por despachador")
        void shouldFindAssignmentsByDispatcherId() {
                // Given
                Assignment assignment1 = Assignment.builder()
                                .trip(trip1)
                                .driver(driver1)
                                .dispatcher(dispatcher)
                                .checklistOk(true)
                                .build();

                Assignment assignment2 = Assignment.builder()
                                .trip(trip2)
                                .driver(driver2)
                                .dispatcher(dispatcher)
                                .checklistOk(false)
                                .build();

                entityManager.persist(assignment1);
                entityManager.persist(assignment2);
                entityManager.flush();

                // When
                List<Assignment> dispatcherAssignments = assignmentRepository.findByDispatcherId(
                                dispatcher.getId(),
                                LocalDate.now());

                // Then
                assertThat(dispatcherAssignments).hasSize(2);
        }

        // Obtener asignación con todas las relaciones cargadas
        @Test
        @DisplayName("Debe obtener asignación con detalles completos (fetch join)")
        void shouldFindAssignmentByIdWithDetails() {
                // Given
                Assignment assignment = Assignment.builder()
                                .trip(trip1)
                                .driver(driver1)
                                .dispatcher(dispatcher)
                                .checklistOk(true)
                                .build();
                entityManager.persist(assignment);
                entityManager.flush();

                // When
                Optional<Assignment> result = assignmentRepository.findByIdWithDetails(assignment.getId());

                // Then
                assertThat(result).isPresent();
                Assignment fetchedAssignment = result.get();
                assertThat(fetchedAssignment.getTrip()).isNotNull();
                assertThat(fetchedAssignment.getTrip().getBus().getPlate()).isEqualTo("ABC123");
                assertThat(fetchedAssignment.getTrip().getRoute().getCode()).isEqualTo("BOG-BGA");
                assertThat(fetchedAssignment.getDriver().getName()).isEqualTo("Juan Conductor");
                assertThat(fetchedAssignment.getDispatcher().getName()).isEqualTo("Carlos Despachador");
        }

        //Verificar que retorna Optional.empty() cuando no existe la asignación
        @Test
        @DisplayName("Debe retornar Optional vacío cuando no hay asignación para el viaje")
        void shouldReturnEmptyWhenNoAssignmentForTrip() {
                // When - trip2 no tiene asignación
                Optional<Assignment> result = assignmentRepository.findByTripId(trip2.getId());

                // Then
                assertThat(result).isEmpty();
        }

        //Verificar que retorna lista vacía cuando conductor no tiene asignaciones
        @Test
        @DisplayName("Debe retornar lista vacía cuando el conductor no tiene asignaciones")
        void shouldReturnEmptyListWhenDriverHasNoAssignments() {
                // When - driver2 no tiene asignaciones
                List<Assignment> assignments = assignmentRepository.findByDriverId(driver2.getId());

                // Then
                assertThat(assignments).isEmpty();
        }
}
