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

import static org.assertj.core.api.Assertions.assertThat;


@DisplayName("IncidentRepository Integration Tests")
class IncidentRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private IncidentRepository incidentRepository;

    private User driver;
    private User dispatcher;
    private Trip trip;
    private Ticket ticket;
    private Parcel parcel;

    @BeforeEach
    void setUp() {
        entityManager.clear();

        // Crear usuarios
        driver = User.builder()
                .name("Juan Conductor")
                .email("juan.conductor@bus.com")
                .phone("3001234567")
                .role(User.Role.DRIVER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword")
                .build();

        dispatcher = User.builder()
                .name("Carlos Despachador")
                .email("carlos.despachador@bus.com")
                .phone("3003456789")
                .role(User.Role.DISPATCHER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword")
                .build();

        User passenger = User.builder()
                .name("Laura Pasajera")
                .email("laura@example.com")
                .phone("3004567890")
                .role(User.Role.PASSENGER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$hashedpassword")
                .build();

        entityManager.persist(driver);
        entityManager.persist(dispatcher);
        entityManager.persist(passenger);

        // Crear ruta
        Route route = Route.builder()
                .code("BOG-BGA")
                .name("Bogotá - Bucaramanga")
                .origin("Bogotá")
                .destination("Bucaramanga")
                .distanceKm(new BigDecimal("398.50"))
                .durationMin(420)
                .isActive(true)
                .build();
        entityManager.persist(route);

        // Crear paradas
        Stop stopBogota = Stop.builder()
                .route(route)
                .name("Terminal Bogotá")
                .order(1)
                .latitude(new BigDecimal("4.6097"))
                .longitude(new BigDecimal("-74.0817"))
                .build();

        Stop stopBucaramanga = Stop.builder()
                .route(route)
                .name("Terminal Bucaramanga")
                .order(2)
                .latitude(new BigDecimal("7.1193"))
                .longitude(new BigDecimal("-73.1227"))
                .build();

        entityManager.persist(stopBogota);
        entityManager.persist(stopBucaramanga);

        // Crear bus
        Bus bus = Bus.builder()
                .plate("ABC123")
                .capacity(40)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build();
        entityManager.persist(bus);

        // Crear viaje
        trip = Trip.builder()
                .route(route)
                .bus(bus)
                .tripDate(LocalDate.now())
                .departureTime(LocalDateTime.now().plusHours(2))
                .arrivalEta(LocalDateTime.now().plusHours(9))
                .status(Trip.TripStatus.SCHEDULED)
                .build();
        entityManager.persist(trip);

        // Crear ticket
        ticket = Ticket.builder()
                .trip(trip)
                .passenger(passenger)
                .seatNumber(15)
                .fromStop(stopBogota)
                .toStop(stopBucaramanga)
                .price(new BigDecimal("50000.00"))
                .paymentMethod(Ticket.PaymentMethod.CASH)
                .status(Ticket.TicketStatus.SOLD)
                .qrCode("QR-123")
                .build();
        entityManager.persist(ticket);

        // Crear encomienda
        parcel = Parcel.builder()
                .code("PARC-001")
                .trip(trip)
                .senderName("Juan Remitente")
                .senderPhone("3001234567")
                .receiverName("María Destinataria")
                .receiverPhone("3007654321")
                .fromStop(stopBogota)
                .toStop(stopBucaramanga)
                .price(new BigDecimal("30000.00"))
                .status(Parcel.ParcelStatus.CREATED)
                .deliveryOtp("123456")
                .build();
        entityManager.persist(parcel);

        entityManager.flush();
    }

    @Test
    @DisplayName("Debe encontrar incidentes por tipo de entidad y ID")
    void shouldFindIncidentsByEntityTypeAndEntityId() {
        // Given - crear incidentes de diferentes entidades
        Incident incident1 = Incident.builder()
                .entityType(Incident.EntityType.TRIP)
                .entityId(trip.getId())
                .incidentType(Incident.IncidentType.VEHICLE)
                .description("Problema mecánico con el bus")
                .reportedBy(driver)
                .build();

        Incident incident2 = Incident.builder()
                .entityType(Incident.EntityType.TRIP)
                .entityId(trip.getId())
                .incidentType(Incident.IncidentType.SECURITY)
                .description("Incidente de seguridad")
                .reportedBy(driver)
                .build();

        entityManager.persist(incident1);
        entityManager.persist(incident2);
        entityManager.flush();

        // When
        List<Incident> incidents = incidentRepository.findByEntityTypeAndEntityId(
                Incident.EntityType.TRIP,
                trip.getId()
        );

        // Then
        assertThat(incidents).hasSize(2);
        assertThat(incidents).extracting(Incident::getIncidentType)
                .containsExactlyInAnyOrder(Incident.IncidentType.VEHICLE, Incident.IncidentType.SECURITY);
    }

    @Test
    @DisplayName("Debe encontrar incidentes por tipo")
    void shouldFindIncidentsByIncidentType() {
        // Given - crear incidentes de diferentes tipos
        Incident incident1 = Incident.builder()
                .entityType(Incident.EntityType.PARCEL)
                .entityId(parcel.getId())
                .incidentType(Incident.IncidentType.DELIVERY_FAIL)
                .description("No se pudo entregar la encomienda")
                .reportedBy(driver)
                .build();

        Incident incident2 = Incident.builder()
                .entityType(Incident.EntityType.PARCEL)
                .entityId(parcel.getId() + 1)
                .incidentType(Incident.IncidentType.DELIVERY_FAIL)
                .description("Destinatario no encontrado")
                .reportedBy(driver)
                .build();

        Incident incident3 = Incident.builder()
                .entityType(Incident.EntityType.TRIP)
                .entityId(trip.getId())
                .incidentType(Incident.IncidentType.OVERBOOK)
                .description("Sobreventa de asientos")
                .reportedBy(dispatcher)
                .build();

        entityManager.persist(incident1);
        entityManager.persist(incident2);
        entityManager.persist(incident3);
        entityManager.flush();

        // When
        List<Incident> deliveryFailures = incidentRepository.findByIncidentType(Incident.IncidentType.DELIVERY_FAIL);
        List<Incident> overbookings = incidentRepository.findByIncidentType(Incident.IncidentType.OVERBOOK);

        // Then
        assertThat(deliveryFailures).hasSize(2);
        assertThat(overbookings).hasSize(1);
    }

    @Test
    @DisplayName("Debe encontrar incidentes por reportador")
    void shouldFindIncidentsByReportedById() {
        // Given - crear incidentes reportados por diferentes usuarios
        Incident incident1 = Incident.builder()
                .entityType(Incident.EntityType.TRIP)
                .entityId(trip.getId())
                .incidentType(Incident.IncidentType.VEHICLE)
                .description("Problema con motor")
                .reportedBy(driver)
                .build();

        Incident incident2 = Incident.builder()
                .entityType(Incident.EntityType.TRIP)
                .entityId(trip.getId())
                .incidentType(Incident.IncidentType.SECURITY)
                .description("Incidente de seguridad")
                .reportedBy(driver)
                .build();

        Incident incident3 = Incident.builder()
                .entityType(Incident.EntityType.TRIP)
                .entityId(trip.getId())
                .incidentType(Incident.IncidentType.OVERBOOK)
                .description("Sobreventa")
                .reportedBy(dispatcher)
                .build();

        entityManager.persist(incident1);
        entityManager.persist(incident2);
        entityManager.persist(incident3);
        entityManager.flush();

        // When
        List<Incident> driverIncidents = incidentRepository.findByReportedById(driver.getId());
        List<Incident> dispatcherIncidents = incidentRepository.findByReportedById(dispatcher.getId());

        // Then
        assertThat(driverIncidents).hasSize(2);
        assertThat(dispatcherIncidents).hasSize(1);
    }

    @Test
    @DisplayName("Debe encontrar incidentes recientes")
    void shouldFindRecentIncidents() {
        // Given - crear incidentes con diferentes fechas
        Incident recentIncident = Incident.builder()
                .entityType(Incident.EntityType.TRIP)
                .entityId(trip.getId())
                .incidentType(Incident.IncidentType.VEHICLE)
                .description("Problema reciente")
                .reportedBy(driver)
                .build();

        entityManager.persist(recentIncident);
        entityManager.flush();

        // When - buscar incidentes desde hace 1 hora
        List<Incident> recentIncidents = incidentRepository.findRecentIncidents(
                LocalDateTime.now().minusHours(1)
        );

        // Then
        assertThat(recentIncidents).hasSize(1);
        assertThat(recentIncidents.get(0).getDescription()).isEqualTo("Problema reciente");
    }

    @Test
    @DisplayName("Debe encontrar incidentes por viaje")
    void shouldFindIncidentsByTrip() {
        // Given - crear múltiples incidentes para un viaje
        Incident incident1 = Incident.builder()
                .entityType(Incident.EntityType.TRIP)
                .entityId(trip.getId())
                .incidentType(Incident.IncidentType.VEHICLE)
                .description("Problema con el aire acondicionado")
                .reportedBy(driver)
                .build();

        Incident incident2 = Incident.builder()
                .entityType(Incident.EntityType.TRIP)
                .entityId(trip.getId())
                .incidentType(Incident.IncidentType.SECURITY)
                .description("Pasajero sin identificación")
                .reportedBy(driver)
                .build();

        entityManager.persist(incident1);
        entityManager.persist(incident2);
        entityManager.flush();

        // When
        List<Incident> tripIncidents = incidentRepository.findIncidentsByTrip(trip.getId());

        // Then
        assertThat(tripIncidents).hasSize(2);
        assertThat(tripIncidents).allMatch(incident -> 
                incident.getEntityType() == Incident.EntityType.TRIP &&
                incident.getEntityId().equals(trip.getId())
        );
    }

    @Test
    @DisplayName("Debe encontrar fallos de entrega de encomiendas")
    void shouldFindParcelDeliveryFailures() {
        // Given - crear incidentes de fallo de entrega
        Incident incident1 = Incident.builder()
                .entityType(Incident.EntityType.PARCEL)
                .entityId(parcel.getId())
                .incidentType(Incident.IncidentType.DELIVERY_FAIL)
                .description("Destinatario no encontrado")
                .reportedBy(driver)
                .build();

        Incident incident2 = Incident.builder()
                .entityType(Incident.EntityType.PARCEL)
                .entityId(parcel.getId() + 1)
                .incidentType(Incident.IncidentType.DELIVERY_FAIL)
                .description("OTP incorrecto")
                .reportedBy(driver)
                .build();

        entityManager.persist(incident1);
        entityManager.persist(incident2);
        entityManager.flush();

        // When
        List<Incident> deliveryFailures = incidentRepository.findParcelDeliveryFailures(
                LocalDateTime.now().minusHours(1)
        );

        // Then
        assertThat(deliveryFailures).hasSize(2);
        assertThat(deliveryFailures).allMatch(incident ->
                incident.getIncidentType() == Incident.IncidentType.DELIVERY_FAIL &&
                incident.getEntityType() == Incident.EntityType.PARCEL
        );
    }

    @Test
    @DisplayName("Debe contar incidentes por tipo en rango de fechas")
    void shouldCountIncidentsByType() {
        // Given - crear incidentes de diferentes tipos
        Incident incident1 = Incident.builder()
                .entityType(Incident.EntityType.TRIP)
                .entityId(trip.getId())
                .incidentType(Incident.IncidentType.VEHICLE)
                .description("Problema 1")
                .reportedBy(driver)
                .build();

        Incident incident2 = Incident.builder()
                .entityType(Incident.EntityType.TRIP)
                .entityId(trip.getId())
                .incidentType(Incident.IncidentType.VEHICLE)
                .description("Problema 2")
                .reportedBy(driver)
                .build();

        Incident incident3 = Incident.builder()
                .entityType(Incident.EntityType.PARCEL)
                .entityId(parcel.getId())
                .incidentType(Incident.IncidentType.DELIVERY_FAIL)
                .description("Fallo de entrega")
                .reportedBy(driver)
                .build();

        Incident incident4 = Incident.builder()
                .entityType(Incident.EntityType.TRIP)
                .entityId(trip.getId())
                .incidentType(Incident.IncidentType.SECURITY)
                .description("Incidente de seguridad")
                .reportedBy(driver)
                .build();

        entityManager.persist(incident1);
        entityManager.persist(incident2);
        entityManager.persist(incident3);
        entityManager.persist(incident4);
        entityManager.flush();

        // When
        List<Object[]> counts = incidentRepository.countIncidentsByType(
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1)
        );

        // Then
        assertThat(counts).hasSize(3); // VEHICLE, DELIVERY_FAIL, SECURITY
        // Verificar que los conteos son correctos
        assertThat(counts).anySatisfy(row -> {
            if (row[0] == Incident.IncidentType.VEHICLE) {
                assertThat((Long) row[1]).isEqualTo(2L);
            }
        });
    }

    @Test
    @DisplayName("Debe retornar lista vacía cuando no hay incidentes para la entidad")
    void shouldReturnEmptyListWhenNoIncidentsForEntity() {
        // When - buscar incidentes para un ticket sin incidentes
        List<Incident> incidents = incidentRepository.findByEntityTypeAndEntityId(
                Incident.EntityType.TICKET,
                ticket.getId()
        );

        // Then
        assertThat(incidents).isEmpty();
    }

    @Test
    @DisplayName("Debe retornar lista vacía cuando no hay incidentes recientes")
    void shouldReturnEmptyListWhenNoRecentIncidents() {
        // When - buscar incidentes desde hace 10 días
        List<Incident> incidents = incidentRepository.findRecentIncidents(
                LocalDateTime.now().minusDays(10)
        );

        // Then
        assertThat(incidents).isEmpty();
    }
}
