package com.web.integration;

import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.reservations.SeatHoldRequest;
import com.web.entity.*;
import com.web.exception.SeatNotAvailableException;
import com.web.integration.userstories.BaseIntegrationTest;
import com.web.repository.*;
import com.web.service.ticket.SeatHoldService;
import com.web.service.ticket.TicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Compras y holds simultáneos sobre la misma silla y tramo: solo uno puede ganar.
// Sin transacción de test para que cada hilo haga commit real (el bloqueo por viaje es lo que se prueba)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConcurrentSeatSaleIntegrationTest extends BaseIntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private SeatHoldRepository seatHoldRepository;

    @Autowired
    private UserRepository userRepository;

    private Route route;
    private Stop stopA;
    private Stop stopC;
    private Bus bus;
    private Trip trip;
    private final List<User> passengers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        long suffix = System.nanoTime();
        route = routeRepository.save(Route.builder()
                .code("CONC-" + suffix)
                .name("Ruta concurrencia")
                .origin("A")
                .destination("C")
                .distanceKm(new BigDecimal("100.00"))
                .durationMin(120)
                .isActive(true)
                .build());
        stopA = stopRepository.save(Stop.builder().route(route).name("A").order(1).build());
        stopRepository.save(Stop.builder().route(route).name("B").order(2).build());
        stopC = stopRepository.save(Stop.builder().route(route).name("C").order(3).build());
        bus = busRepository.save(Bus.builder()
                .plate("CC" + (suffix % 1000000))
                .capacity(40)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build());
        LocalDate date = LocalDate.now().plusDays(5);
        trip = tripRepository.save(Trip.builder()
                .route(route)
                .bus(bus)
                .tripDate(date)
                .departureTime(date.atTime(12, 0))
                .arrivalEta(date.atTime(14, 0))
                .status(Trip.TripStatus.SCHEDULED)
                .build());
        for (int i = 0; i < THREADS; i++) {
            passengers.add(createUser(new RegisterRequest("Pasajero " + i, "conc" + i + "-" + suffix + "@test.com",
                    "300", "secreto1", User.Role.PASSENGER)));
        }
    }

    @AfterEach
    void cleanUp() {
        seatHoldRepository.findAll().stream()
                .filter(h -> h.getTrip().getId().equals(trip.getId()))
                .forEach(seatHoldRepository::delete);
        ticketRepository.deleteAll(ticketRepository.findByTripId(trip.getId()));
        tripRepository.deleteById(trip.getId());
        busRepository.deleteById(bus.getId());
        stopRepository.deleteAll(stopRepository.findAll().stream()
                .filter(s -> s.getRoute().getId().equals(route.getId())).toList());
        routeRepository.deleteById(route.getId());
        userRepository.deleteAll(passengers);
    }

    @Test
    void concurrentPurchases_ofSameSeatAndSegment_shouldSellOnlyOnce() throws Exception {
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (User passenger : passengers) {
            attempts.add(() -> {
                ticketService.purchaseTicket(new TicketCreateRequest(
                        trip.getId(), passenger.getId(), 7,
                        stopA.getId(), null, null, stopC.getId(), null, null,
                        new BigDecimal("50000"), Ticket.PaymentMethod.CASH, null, null));
                return true;
            });
        }

        int successes = runConcurrently(attempts);

        assertThat(successes).isEqualTo(1);
        assertThat(ticketRepository.findByTripIdAndSeatNumber(trip.getId(), 7))
                .filteredOn(t -> t.getStatus() == Ticket.TicketStatus.SOLD)
                .hasSize(1);
    }

    @Test
    void concurrentHolds_ofSameSeatAndSegment_shouldGrantOnlyOne() throws Exception {
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (User passenger : passengers) {
            attempts.add(() -> {
                seatHoldService.createHold(trip.getId(), 9,
                        new SeatHoldRequest(passenger.getId(), stopA.getId(), stopC.getId()));
                return true;
            });
        }

        int successes = runConcurrently(attempts);

        assertThat(successes).isEqualTo(1);
        assertThat(seatHoldRepository.findActiveHolds(trip.getId(), 9, java.time.LocalDateTime.now())).hasSize(1);
    }

    // Lanza todos los intentos a la vez; cuenta los exitosos y exige que los demás fallen por silla ocupada
    private int runConcurrently(List<Callable<Boolean>> attempts) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(attempts.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> attempt : attempts) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return attempt.call();
                    } catch (SeatNotAvailableException expected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            return successes;
        } finally {
            executor.shutdownNow();
        }
    }
}
