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

@DisplayName("TicketRepository Integration Tests")
class TicketRepositoryTest extends BaseRepositoryTest {

        @Autowired
        private TestEntityManager entityManager;

        @Autowired
        private TicketRepository ticketRepository;

        private Trip trip;
        private User passenger1;
        private User passenger2;
        private Stop stopBogota;
        private Stop stopTunja;
        private Stop stopBucaramanga;
        private Route route;

        @BeforeEach
        void setUp() {
                entityManager.clear();

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

                // Crear paradas
                stopBogota = Stop.builder()
                                .route(route)
                                .name("Terminal Bogotá")
                                .order(1)
                                .latitude(new BigDecimal("4.6097"))
                                .longitude(new BigDecimal("-74.0817"))
                                .build();

                stopTunja = Stop.builder()
                                .route(route)
                                .name("Terminal Tunja")
                                .order(2)
                                .latitude(new BigDecimal("5.5353"))
                                .longitude(new BigDecimal("-73.3678"))
                                .build();

                stopBucaramanga = Stop.builder()
                                .route(route)
                                .name("Terminal Bucaramanga")
                                .order(3)
                                .latitude(new BigDecimal("7.1193"))
                                .longitude(new BigDecimal("-73.1227"))
                                .build();

                entityManager.persist(stopBogota);
                entityManager.persist(stopTunja);
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

                // Crear pasajeros
                passenger1 = User.builder()
                                .name("Laura Pasajera")
                                .email("laura@example.com")
                                .phone("3004567890")
                                .role(User.Role.PASSENGER)
                                .status(User.Status.ACTIVE)
                                .passwordHash("$2a$10$hashedpassword")
                                .build();

                passenger2 = User.builder()
                                .name("Jorge Pasajero")
                                .email("jorge@example.com")
                                .phone("3005678901")
                                .role(User.Role.PASSENGER)
                                .status(User.Status.ACTIVE)
                                .passwordHash("$2a$10$hashedpassword")
                                .build();

                entityManager.persist(passenger1);
                entityManager.persist(passenger2);
                entityManager.flush();
        }

        // TEST: Buscar tickets por ID de viaje y número de asiento específico
        @Test
        @DisplayName("Debe encontrar tickets por viaje y asiento")
        void shouldFindTicketsByTripIdAndSeatNumber() {
                // Given
                Ticket ticket = Ticket.builder()
                                .trip(trip)
                                .passenger(passenger1)
                                .seatNumber(15)
                                .fromStop(stopBogota)
                                .toStop(stopBucaramanga)
                                .price(new BigDecimal("50000.00"))
                                .paymentMethod(Ticket.PaymentMethod.CASH)
                                .status(Ticket.TicketStatus.SOLD)
                                .qrCode("QR-123")
                                .build();
                entityManager.persist(ticket);
                entityManager.flush();

                // When
                List<Ticket> tickets = ticketRepository.findByTripIdAndSeatNumber(trip.getId(), 15);

                // Then
                assertThat(tickets).hasSize(1);
                assertThat(tickets.get(0).getPassenger().getName()).isEqualTo("Laura Pasajera");
        }

        // TEST: Obtener todos los tickets asociados a un viaje
        @Test
        @DisplayName("Debe encontrar todos los tickets de un viaje")
        void shouldFindTicketsByTripId() {
                // Given - crear 3 tickets
                for (int i = 1; i <= 3; i++) {
                        Ticket ticket = Ticket.builder()
                                        .trip(trip)
                                        .passenger(passenger1)
                                        .seatNumber(i)
                                        .fromStop(stopBogota)
                                        .toStop(stopBucaramanga)
                                        .price(new BigDecimal("50000.00"))
                                        .paymentMethod(Ticket.PaymentMethod.CASH)
                                        .status(Ticket.TicketStatus.SOLD)
                                        .qrCode("QR-" + i)
                                        .build();
                        entityManager.persist(ticket);
                }
                entityManager.flush();

                // When
                List<Ticket> tickets = ticketRepository.findByTripId(trip.getId());

                // Then
                assertThat(tickets).hasSize(3);
        }

        // TEST: Buscar historial de tickets de un pasajero específico
        @Test
        @DisplayName("Debe encontrar tickets por pasajero")
        void shouldFindTicketsByPassengerId() {
                // Given
                Ticket ticket1 = Ticket.builder()
                                .trip(trip)
                                .passenger(passenger1)
                                .seatNumber(10)
                                .fromStop(stopBogota)
                                .toStop(stopTunja)
                                .price(new BigDecimal("25000.00"))
                                .paymentMethod(Ticket.PaymentMethod.CARD)
                                .status(Ticket.TicketStatus.SOLD)
                                .qrCode("QR-P1-1")
                                .build();

                Ticket ticket2 = Ticket.builder()
                                .trip(trip)
                                .passenger(passenger1)
                                .seatNumber(11)
                                .fromStop(stopTunja)
                                .toStop(stopBucaramanga)
                                .price(new BigDecimal("30000.00"))
                                .paymentMethod(Ticket.PaymentMethod.CASH)
                                .status(Ticket.TicketStatus.SOLD)
                                .qrCode("QR-P1-2")
                                .build();

                entityManager.persist(ticket1);
                entityManager.persist(ticket2);
                entityManager.flush();

                // When
                List<Ticket> tickets = ticketRepository.findByPassengerId(passenger1.getId());

                // Then
                assertThat(tickets).hasSize(2);
        }

        // TEST: Buscar ticket mediante código QR único (para validación de abordaje)
        @Test
        @DisplayName("Debe encontrar ticket por código QR")
        void shouldFindTicketByQrCode() {
                // Given
                Ticket ticket = Ticket.builder()
                                .trip(trip)
                                .passenger(passenger1)
                                .seatNumber(20)
                                .fromStop(stopBogota)
                                .toStop(stopBucaramanga)
                                .price(new BigDecimal("50000.00"))
                                .paymentMethod(Ticket.PaymentMethod.QR)
                                .status(Ticket.TicketStatus.SOLD)
                                .qrCode("QR-UNIQUE-123")
                                .build();
                entityManager.persist(ticket);
                entityManager.flush();

                // When
                Optional<Ticket> result = ticketRepository.findByQrCode("QR-UNIQUE-123");

                // Then
                assertThat(result).isPresent();
                assertThat(result.get().getSeatNumber()).isEqualTo(20);
        }

        // TEST: Detectar tickets que ocupan el mismo asiento en un tramo específico
        @Test
        @DisplayName("Debe encontrar tickets conflictivos para un tramo")
        void shouldFindConflictingTicketsForSegment() {
                // Given - ticket en tramo Bogotá -> Bucaramanga (orden 1 -> 3)
                Ticket existingTicket = Ticket.builder()
                                .trip(trip)
                                .passenger(passenger1)
                                .seatNumber(15)
                                .fromStop(stopBogota)
                                .toStop(stopBucaramanga)
                                .price(new BigDecimal("50000.00"))
                                .paymentMethod(Ticket.PaymentMethod.CASH)
                                .status(Ticket.TicketStatus.SOLD)
                                .qrCode("QR-EXISTING")
                                .build();
                entityManager.persist(existingTicket);
                entityManager.flush();

                // When - buscar conflictos para tramo Bogotá -> Tunja (orden 1 -> 2)
                List<Ticket> conflicts = ticketRepository.findConflictingTicketsForSegment(
                                trip.getId(),
                                15,
                                stopBogota.getOrder(),
                                stopTunja.getOrder());

                // Then - debe encontrar el ticket existente porque solapa
                assertThat(conflicts).hasSize(1);
        }

        // TEST: Verificar disponibilidad de asiento en un tramo (evita doble reserva)
        @Test
        @DisplayName("Debe verificar si un asiento está disponible para un tramo")
        void shouldCheckIfSeatIsAvailableForSegment() {
                // Given - asiento 15 ocupado en tramo Bogotá -> Tunja
                Ticket ticket = Ticket.builder()
                                .trip(trip)
                                .passenger(passenger1)
                                .seatNumber(15)
                                .fromStop(stopBogota)
                                .toStop(stopTunja)
                                .price(new BigDecimal("25000.00"))
                                .paymentMethod(Ticket.PaymentMethod.CASH)
                                .status(Ticket.TicketStatus.SOLD)
                                .qrCode("QR-SEG1")
                                .build();
                entityManager.persist(ticket);
                entityManager.flush();

                // When - verificar disponibilidad
                boolean availableForConflict = ticketRepository.isSeatAvailableForSegment(
                                trip.getId(),
                                15,
                                stopBogota.getOrder(),
                                stopTunja.getOrder());

                boolean availableForNonConflict = ticketRepository.isSeatAvailableForSegment(
                                trip.getId(),
                                15,
                                stopTunja.getOrder(),
                                stopBucaramanga.getOrder());

                // Then
                assertThat(availableForConflict).isFalse(); // Solapa
                assertThat(availableForNonConflict).isTrue(); // No solapa
        }

        // TEST: Contar cuántos asientos únicos se han vendido en un viaje
        @Test
        @DisplayName("Debe contar asientos vendidos únicos por viaje")
        void shouldCountSoldSeats() {
                // Given - 5 tickets en diferentes asientos
                for (int i = 1; i <= 5; i++) {
                        Ticket ticket = Ticket.builder()
                                        .trip(trip)
                                        .passenger(passenger1)
                                        .seatNumber(i)
                                        .fromStop(stopBogota)
                                        .toStop(stopBucaramanga)
                                        .price(new BigDecimal("50000.00"))
                                        .paymentMethod(Ticket.PaymentMethod.CASH)
                                        .status(Ticket.TicketStatus.SOLD)
                                        .qrCode("QR-" + i)
                                        .build();
                        entityManager.persist(ticket);
                }
                entityManager.flush();

                // When
                Long count = ticketRepository.countSoldSeats(trip.getId());

                // Then
                assertThat(count).isEqualTo(5);
        }

        // TEST: Buscar tickets que pasan por una parada específica (origen o destino)
        @Test
        @DisplayName("Debe encontrar tickets en una parada específica")
        void shouldFindTicketsAtStop() {
                // Given - varios tickets con diferentes tramos
                Ticket ticket1 = Ticket.builder()
                                .trip(trip)
                                .passenger(passenger1)
                                .seatNumber(1)
                                .fromStop(stopBogota) // orden 1
                                .toStop(stopBucaramanga) // orden 3
                                .price(new BigDecimal("50000.00"))
                                .paymentMethod(Ticket.PaymentMethod.CASH)
                                .status(Ticket.TicketStatus.SOLD)
                                .qrCode("QR-T1")
                                .build();

                Ticket ticket2 = Ticket.builder()
                                .trip(trip)
                                .passenger(passenger2)
                                .seatNumber(2)
                                .fromStop(stopBogota) // orden 1
                                .toStop(stopTunja) // orden 2 - baja en Tunja
                                .price(new BigDecimal("25000.00"))
                                .paymentMethod(Ticket.PaymentMethod.CASH)
                                .status(Ticket.TicketStatus.SOLD)
                                .qrCode("QR-T2")
                                .build();

                entityManager.persist(ticket1);
                entityManager.persist(ticket2);
                entityManager.flush();

                // When - buscar tickets en parada Tunja (orden 2)
                List<Ticket> ticketsAtTunja = ticketRepository.findTicketsAtStop(
                                trip.getId(),
                                stopTunja.getOrder());

                // Then - solo ticket1 sigue en el bus (ticket2 baja aquí)
                assertThat(ticketsAtTunja).hasSize(1);
                assertThat(ticketsAtTunja.get(0).getSeatNumber()).isEqualTo(1);
        }

        // TEST: Buscar ticket que puede ser cancelado (estado SOLD, viaje futuro)
        @Test
        @DisplayName("Debe encontrar ticket cancelable")
        void shouldFindCancellableTicket() {
                // Given - ticket con viaje futuro
                Ticket ticket = Ticket.builder()
                                .trip(trip)
                                .passenger(passenger1)
                                .seatNumber(25)
                                .fromStop(stopBogota)
                                .toStop(stopBucaramanga)
                                .price(new BigDecimal("50000.00"))
                                .paymentMethod(Ticket.PaymentMethod.TRANSFER)
                                .status(Ticket.TicketStatus.SOLD)
                                .qrCode("QR-CANCEL")
                                .build();
                entityManager.persist(ticket);
                entityManager.flush();

                // When
                Optional<Ticket> result = ticketRepository.findCancellableTicket(
                                ticket.getId(),
                                LocalDateTime.now());

                // Then
                assertThat(result).isPresent();
        }

        // TEST: Calcular ingresos totales de tickets vendidos en un rango de fechas
        @Test
        @DisplayName("Debe calcular ingresos por rango de fechas")
        void shouldCalculateRevenue() {
                // Given - 3 tickets vendidos
                for (int i = 1; i <= 3; i++) {
                        Ticket ticket = Ticket.builder()
                                        .trip(trip)
                                        .passenger(passenger1)
                                        .seatNumber(i)
                                        .fromStop(stopBogota)
                                        .toStop(stopBucaramanga)
                                        .price(new BigDecimal("50000.00"))
                                        .paymentMethod(Ticket.PaymentMethod.CASH)
                                        .status(Ticket.TicketStatus.SOLD)
                                        .qrCode("QR-REV-" + i)
                                        .build();
                        entityManager.persist(ticket);
                }
                entityManager.flush();

                // When
                BigDecimal revenue = ticketRepository.calculateRevenue(LocalDate.now(), LocalDate.now());

                // Then
                assertThat(revenue).isEqualByComparingTo(new BigDecimal("150000.00"));
        }

        // TEST: Calcular ingresos agrupados por método de pago (CASH/CARD)
        @Test
        @DisplayName("Debe calcular ingresos por método de pago")
        void shouldCalculateRevenueByPaymentMethod() {
                // Given - tickets con diferentes métodos de pago
                Ticket ticket1 = Ticket.builder()
                                .trip(trip)
                                .passenger(passenger1)
                                .seatNumber(1)
                                .fromStop(stopBogota)
                                .toStop(stopBucaramanga)
                                .price(new BigDecimal("50000.00"))
                                .paymentMethod(Ticket.PaymentMethod.CASH)
                                .status(Ticket.TicketStatus.SOLD)
                                .qrCode("QR-1")
                                .build();

                Ticket ticket2 = Ticket.builder()
                                .trip(trip)
                                .passenger(passenger1)
                                .seatNumber(2)
                                .fromStop(stopBogota)
                                .toStop(stopBucaramanga)
                                .price(new BigDecimal("50000.00"))
                                .paymentMethod(Ticket.PaymentMethod.CARD)
                                .status(Ticket.TicketStatus.SOLD)
                                .qrCode("QR-2")
                                .build();

                Ticket ticket3 = Ticket.builder()
                                .trip(trip)
                                .passenger(passenger2)
                                .seatNumber(3)
                                .fromStop(stopBogota)
                                .toStop(stopBucaramanga)
                                .price(new BigDecimal("50000.00"))
                                .paymentMethod(Ticket.PaymentMethod.CASH)
                                .status(Ticket.TicketStatus.SOLD)
                                .qrCode("QR-3")
                                .build();

                entityManager.persist(ticket1);
                entityManager.persist(ticket2);
                entityManager.persist(ticket3);
                entityManager.flush();

                // When
                List<Object[]> revenueByMethod = ticketRepository.calculateRevenueByPaymentMethod(
                                LocalDate.now(),
                                LocalDate.now());

                // Then
                assertThat(revenueByMethod).hasSize(2);
                // CASH: 100000, CARD: 50000
        }

        // TEST: Contar número de tickets cancelados en un rango de fechas
        @Test
        @DisplayName("Debe contar cancelaciones")
        void shouldcountCancellationsInRange() {
                // Given - 2 tickets cancelados
                Ticket ticket1 = Ticket.builder()
                                .trip(trip)
                                .passenger(passenger1)
                                .seatNumber(10)
                                .fromStop(stopBogota)
                                .toStop(stopBucaramanga)
                                .price(new BigDecimal("50000.00"))
                                .paymentMethod(Ticket.PaymentMethod.CASH)
                                .status(Ticket.TicketStatus.CANCELLED)
                                .qrCode("QR-CANC1")
                                .build();

                Ticket ticket2 = Ticket.builder()
                                .trip(trip)
                                .passenger(passenger2)
                                .seatNumber(11)
                                .fromStop(stopBogota)
                                .toStop(stopBucaramanga)
                                .price(new BigDecimal("50000.00"))
                                .paymentMethod(Ticket.PaymentMethod.CARD)
                                .status(Ticket.TicketStatus.CANCELLED)
                                .qrCode("QR-CANC2")
                                .build();

                entityManager.persist(ticket1);
                entityManager.persist(ticket2);
                entityManager.flush();

                // When
                Long count = ticketRepository.countCancellationsInRange(LocalDate.now(), LocalDate.now());

                // Then
                assertThat(count).isEqualTo(2);
        }

        // TEST: Buscar tickets no usados después de la salida del viaje (no-show)
        @Test
        @DisplayName("Debe encontrar tickets elegibles para NO_SHOW")
        void shouldFindNoShowEligibleTickets() {
                // Given - ticket que aborda en el origen con hora de salida pasada
                Trip pastTrip = Trip.builder()
                                .route(route)
                                .bus(trip.getBus())
                                .tripDate(LocalDate.now())
                                .departureTime(LocalDateTime.now().minusMinutes(10)) // Ya pasó
                                .arrivalEta(LocalDateTime.now().plusHours(7))
                                .status(Trip.TripStatus.BOARDING)
                                .build();
                entityManager.persist(pastTrip);

                Ticket ticket = Ticket.builder()
                                .trip(pastTrip)
                                .passenger(passenger1)
                                .seatNumber(5)
                                .fromStop(stopBogota) // Aborda en origen (orden 1)
                                .toStop(stopBucaramanga)
                                .price(new BigDecimal("50000.00"))
                                .paymentMethod(Ticket.PaymentMethod.CASH)
                                .status(Ticket.TicketStatus.SOLD)
                                .qrCode("QR-NOSHOW")
                                .build();
                entityManager.persist(ticket);
                entityManager.flush();

                // When
                List<Ticket> noShowTickets = ticketRepository.findNoShowEligibleTickets(
                                pastTrip.getId(),
                                LocalDateTime.now());

                // Then
                assertThat(noShowTickets).hasSize(1);
                assertThat(noShowTickets.get(0).getSeatNumber()).isEqualTo(5);
        }
}
