package com.web.dto.mapper;

import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.auth.User.UserResponse;
import com.web.dto.auth.User.UserUpdateRequest;
import com.web.dto.auth.User.mapper.UserMapper;
import com.web.dto.auth.User.mapper.UserMapperImpl;
import com.web.dto.baggage.BaggageCreateRequest;
import com.web.dto.baggage.BaggageResponse;
import com.web.dto.baggage.BaggageUpdateRequest;
import com.web.dto.baggage.mapper.BaggageMapper;
import com.web.dto.baggage.mapper.BaggageMapperImpl;
import com.web.dto.catalog.Bus.BusCreateRequest;
import com.web.dto.catalog.Bus.BusResponse;
import com.web.dto.catalog.Bus.BusUpdateRequest;
import com.web.dto.catalog.Bus.mapper.BusMapper;
import com.web.dto.catalog.Bus.mapper.BusMapperImpl;
import com.web.dto.catalog.Route.RouteCreateRequest;
import com.web.dto.catalog.Route.RouteDetailResponse;
import com.web.dto.catalog.Route.RouteResponse;
import com.web.dto.catalog.Route.RouteUpdateRequest;
import com.web.dto.catalog.Route.mapper.RouteMapper;
import com.web.dto.catalog.Route.mapper.RouteMapperImpl;
import com.web.dto.catalog.Stop.StopCreateRequest;
import com.web.dto.catalog.Stop.StopResponse;
import com.web.dto.catalog.Stop.mapper.StopMapper;
import com.web.dto.catalog.Stop.mapper.StopMapperImpl;
import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentResponse;
import com.web.dto.dispatch.Assignment.AssignmentUpdateRequest;
import com.web.dto.dispatch.Assignment.mapper.AssignmentMapper;
import com.web.dto.dispatch.Assignment.mapper.AssignmentMapperImpl;
import com.web.dto.parcel.ParcelCreateRequest;
import com.web.dto.parcel.ParcelResponse;
import com.web.dto.parcel.mapper.ParcelMapper;
import com.web.dto.parcel.mapper.ParcelMapperImpl;
import com.web.dto.payment.PaymentResponse;
import com.web.dto.payment.mapper.PaymentMapper;
import com.web.dto.payment.mapper.PaymentMapperImpl;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.dto.ticket.mapper.TicketMapperImpl;
import com.web.dto.ticket.reservations.SeatHoldCreateRequest;
import com.web.dto.ticket.reservations.SeatHoldResponse;
import com.web.dto.ticket.reservations.mapper.SeatHoldMapper;
import com.web.dto.ticket.reservations.mapper.SeatHoldMapperImpl;
import com.web.dto.trip.TripCreateRequest;
import com.web.dto.trip.TripDetailResponse;
import com.web.dto.trip.TripResponse;
import com.web.dto.trip.TripUpdateRequest;
import com.web.dto.trip.mapper.TripMapper;
import com.web.dto.trip.mapper.TripMapperImpl;
import com.web.entity.Assignment;
import com.web.entity.Baggage;
import com.web.entity.Bus;
import com.web.entity.Parcel;
import com.web.entity.Route;
import com.web.entity.SeatHold;
import com.web.entity.Stop;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Pruebas de las implementaciones generadas por MapStruct, cargadas en un contexto Spring mínimo
// para que las dependencias entre mappers (uses = {...}) se inyecten igual que en producción
@SpringJUnitConfig(classes = {
        UserMapperImpl.class, BusMapperImpl.class, StopMapperImpl.class, RouteMapperImpl.class,
        BaggageMapperImpl.class, TicketMapperImpl.class, AssignmentMapperImpl.class, TripMapperImpl.class,
        ParcelMapperImpl.class, SeatHoldMapperImpl.class, PaymentMapperImpl.class
})
class MapperTest {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private BusMapper busMapper;
    @Autowired
    private StopMapper stopMapper;
    @Autowired
    private RouteMapper routeMapper;
    @Autowired
    private BaggageMapper baggageMapper;
    @Autowired
    private TicketMapper ticketMapper;
    @Autowired
    private AssignmentMapper assignmentMapper;
    @Autowired
    private TripMapper tripMapper;
    @Autowired
    private ParcelMapper parcelMapper;
    @Autowired
    private SeatHoldMapper seatHoldMapper;
    @Autowired
    private PaymentMapper paymentMapper;

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2025, 1, 10, 8, 30);
    private static final LocalDate TRIP_DATE = LocalDate.of(2025, 2, 1);
    private static final LocalDateTime DEPARTURE = LocalDateTime.of(2025, 2, 1, 6, 0);
    private static final LocalDateTime ARRIVAL = LocalDateTime.of(2025, 2, 1, 9, 30);

    private User passenger;
    private User driver;
    private User dispatcher;
    private Route route;
    private Stop fromStop;
    private Stop toStop;
    private Bus bus;
    private Trip trip;

    @BeforeEach
    void setUp() {
        passenger = User.builder()
                .id(10L).name("Ana Pasajera").email("ana@test.com").phone("3001111111")
                .role(User.Role.PASSENGER).passwordHash("hash-ana").status(User.Status.ACTIVE)
                .createdAt(CREATED_AT)
                .build();
        driver = User.builder()
                .id(20L).name("Carlos Conductor").email("carlos@test.com").phone("3002222222")
                .role(User.Role.DRIVER).passwordHash("hash-carlos")
                .build();
        dispatcher = User.builder()
                .id(30L).name("Diana Despachadora").email("diana@test.com").phone("3003333333")
                .role(User.Role.DISPATCHER).passwordHash("hash-diana")
                .build();

        route = Route.builder()
                .id(1L).code("SM-BAQ").name("Santa Marta - Barranquilla")
                .origin("Santa Marta").destination("Barranquilla")
                .distanceKm(new BigDecimal("105.50")).durationMin(150).isActive(true)
                .createdAt(CREATED_AT)
                .build();
        fromStop = Stop.builder()
                .id(100L).route(route).name("Terminal Santa Marta").order(1)
                .latitude(new BigDecimal("11.24079000")).longitude(new BigDecimal("-74.19904000"))
                .build();
        toStop = Stop.builder()
                .id(101L).route(route).name("Ciénaga").order(2)
                .build();
        route.setStops(List.of(fromStop, toStop));

        Map<String, Object> amenities = new HashMap<>();
        amenities.put("wifi", true);
        amenities.put("banos", 1);
        bus = Bus.builder()
                .id(5L).plate("ABC123").capacity(40).amenities(amenities)
                .status(Bus.BusStatus.ACTIVE).createdAt(CREATED_AT)
                .build();

        trip = Trip.builder()
                .id(50L).route(route).bus(bus).tripDate(TRIP_DATE)
                .departureTime(DEPARTURE).arrivalEta(ARRIVAL)
                .status(Trip.TripStatus.BOARDING).createdAt(CREATED_AT)
                .build();
    }

    // ==================== UserMapper ====================

    @Test
    void userMapper_ShouldMapEntityToResponse() {
        // When
        UserResponse response = userMapper.toResponse(passenger);

        // Then
        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.name()).isEqualTo("Ana Pasajera");
        assertThat(response.email()).isEqualTo("ana@test.com");
        assertThat(response.phone()).isEqualTo("3001111111");
        assertThat(response.role()).isEqualTo(User.Role.PASSENGER);
        assertThat(response.status()).isEqualTo(User.Status.ACTIVE);
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void userMapper_ShouldMapList_AndHandleNulls() {
        // When
        List<UserResponse> responses = userMapper.toResponseList(List.of(passenger, driver));

        // Then
        assertThat(responses).extracting(UserResponse::id).containsExactly(10L, 20L);
        assertThat(userMapper.toResponse(null)).isNull();
        assertThat(userMapper.toResponseList(null)).isNull();
        assertThat(userMapper.toResponseList(List.of())).isEmpty();
        assertThat(userMapper.toEntity(null)).isNull();
    }

    @Test
    void userMapper_ShouldMapRegisterRequestToEntity_WithoutPasswordAndActiveStatus() {
        // Given
        RegisterRequest request = new RegisterRequest("Nuevo", "nuevo@test.com", "3009999999",
                "claveSecreta", User.Role.CLERK);

        // When
        User user = userMapper.toEntity(request);

        // Then
        assertThat(user.getId()).isNull();
        assertThat(user.getName()).isEqualTo("Nuevo");
        assertThat(user.getEmail()).isEqualTo("nuevo@test.com");
        assertThat(user.getPhone()).isEqualTo("3009999999");
        assertThat(user.getRole()).isEqualTo(User.Role.CLERK);
        assertThat(user.getStatus()).isEqualTo(User.Status.ACTIVE);
        // La contraseña en claro nunca se copia; el hash lo calcula el servicio
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getTickets()).isNull();
        assertThat(user.getSeatHolds()).isNull();
    }

    @Test
    void userMapper_ShouldUpdateOnlyNonNullFields() {
        // Given
        UserUpdateRequest request = new UserUpdateRequest(null, "3110000000", "nuevaClave");

        // When
        userMapper.updateEntityFromRequest(request, passenger);

        // Then
        assertThat(passenger.getName()).isEqualTo("Ana Pasajera");
        assertThat(passenger.getPhone()).isEqualTo("3110000000");
        // Campos protegidos: el update nunca toca email, rol, estado ni hash
        assertThat(passenger.getEmail()).isEqualTo("ana@test.com");
        assertThat(passenger.getRole()).isEqualTo(User.Role.PASSENGER);
        assertThat(passenger.getStatus()).isEqualTo(User.Status.ACTIVE);
        assertThat(passenger.getPasswordHash()).isEqualTo("hash-ana");
        assertThat(passenger.getId()).isEqualTo(10L);
    }

    @Test
    void userMapper_ShouldIgnoreNullUpdateRequest() {
        // When
        userMapper.updateEntityFromRequest(null, passenger);

        // Then
        assertThat(passenger.getName()).isEqualTo("Ana Pasajera");
        assertThat(passenger.getPhone()).isEqualTo("3001111111");
    }

    // ==================== BusMapper ====================

    @Test
    void busMapper_ShouldMapEntityToResponse_WithCopyOfAmenities() {
        // When
        BusResponse response = busMapper.toResponse(bus);

        // Then
        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.plate()).isEqualTo("ABC123");
        assertThat(response.capacity()).isEqualTo(40);
        assertThat(response.status()).isEqualTo(Bus.BusStatus.ACTIVE);
        assertThat(response.amenities()).containsEntry("wifi", true).containsEntry("banos", 1);
        assertThat(response.amenities()).isNotSameAs(bus.getAmenities());
    }

    @Test
    void busMapper_ShouldMapList_AndHandleNulls() {
        // Given
        Bus other = Bus.builder().id(6L).plate("XYZ789").capacity(20).build();

        // When
        List<BusResponse> responses = busMapper.toResponseList(List.of(bus, other));

        // Then
        assertThat(responses).extracting(BusResponse::plate).containsExactly("ABC123", "XYZ789");
        assertThat(responses.get(1).amenities()).isNull();
        assertThat(busMapper.toResponse(null)).isNull();
        assertThat(busMapper.toResponseList(null)).isNull();
        assertThat(busMapper.toEntity(null)).isNull();
    }

    @Test
    void busMapper_ShouldMapCreateRequestToEntity_WithActiveStatus() {
        // Given
        BusCreateRequest request = new BusCreateRequest("NEW001", 30, Map.of("aire", true));

        // When
        Bus entity = busMapper.toEntity(request);

        // Then
        assertThat(entity.getId()).isNull();
        assertThat(entity.getPlate()).isEqualTo("NEW001");
        assertThat(entity.getCapacity()).isEqualTo(30);
        assertThat(entity.getAmenities()).containsEntry("aire", true);
        assertThat(entity.getStatus()).isEqualTo(Bus.BusStatus.ACTIVE);
        assertThat(entity.getSeats()).isNull();
        assertThat(entity.getTrips()).isNull();
    }

    @Test
    void busMapper_ShouldUpdateOnlyNonNullFields_AndNeverChangePlate() {
        // Given
        BusUpdateRequest request = new BusUpdateRequest(null, null, Bus.BusStatus.MAINTENANCE);

        // When
        busMapper.updateEntityFromRequest(request, bus);

        // Then
        assertThat(bus.getCapacity()).isEqualTo(40);
        assertThat(bus.getAmenities()).containsEntry("wifi", true);
        assertThat(bus.getStatus()).isEqualTo(Bus.BusStatus.MAINTENANCE);
        assertThat(bus.getPlate()).isEqualTo("ABC123");
        assertThat(bus.getId()).isEqualTo(5L);
    }

    @Test
    void busMapper_ShouldReplaceAmenities_WhenProvided() {
        // Given
        BusUpdateRequest request = new BusUpdateRequest(45, Map.of("tv", true), null);

        // When
        busMapper.updateEntityFromRequest(request, bus);

        // Then
        assertThat(bus.getCapacity()).isEqualTo(45);
        assertThat(bus.getAmenities()).containsOnlyKeys("tv");
        assertThat(bus.getStatus()).isEqualTo(Bus.BusStatus.ACTIVE);
    }

    @Test
    void busMapper_ShouldSetAmenities_WhenEntityHadNone() {
        // Given
        Bus withoutAmenities = Bus.builder().id(7L).plate("SIN001").capacity(10).build();
        BusUpdateRequest request = new BusUpdateRequest(null, Map.of("usb", true), null);

        // When
        busMapper.updateEntityFromRequest(request, withoutAmenities);

        // Then
        assertThat(withoutAmenities.getAmenities()).containsEntry("usb", true);
        assertThat(withoutAmenities.getCapacity()).isEqualTo(10);
    }

    // ==================== StopMapper ====================

    @Test
    void stopMapper_ShouldMapEntityToResponse() {
        // When
        StopResponse response = stopMapper.toResponse(fromStop);

        // Then
        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.name()).isEqualTo("Terminal Santa Marta");
        assertThat(response.order()).isEqualTo(1);
        assertThat(response.latitude()).isEqualByComparingTo("11.24079");
        assertThat(response.longitude()).isEqualByComparingTo("-74.19904");
    }

    @Test
    void stopMapper_ShouldMapList_AndHandleNulls() {
        // When
        List<StopResponse> responses = stopMapper.toResponseList(List.of(fromStop, toStop));

        // Then
        assertThat(responses).extracting(StopResponse::name).containsExactly("Terminal Santa Marta", "Ciénaga");
        assertThat(stopMapper.toResponse(null)).isNull();
        assertThat(stopMapper.toResponseList(null)).isNull();
        assertThat(stopMapper.toEntity(null)).isNull();
    }

    @Test
    void stopMapper_ShouldMapCreateRequestToEntity_WithoutRoute() {
        // Given
        StopCreateRequest request = new StopCreateRequest(1L, "Nueva Parada", 3,
                new BigDecimal("10.5"), new BigDecimal("-74.1"));

        // When
        Stop entity = stopMapper.toEntity(request);

        // Then
        assertThat(entity.getId()).isNull();
        assertThat(entity.getName()).isEqualTo("Nueva Parada");
        assertThat(entity.getOrder()).isEqualTo(3);
        assertThat(entity.getLatitude()).isEqualByComparingTo("10.5");
        assertThat(entity.getLongitude()).isEqualByComparingTo("-74.1");
        // La ruta la resuelve el servicio a partir de routeId
        assertThat(entity.getRoute()).isNull();
    }

    // ==================== RouteMapper ====================

    @Test
    void routeMapper_ShouldMapEntityToResponse() {
        // When
        RouteResponse response = routeMapper.toResponse(route);

        // Then
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.code()).isEqualTo("SM-BAQ");
        assertThat(response.name()).isEqualTo("Santa Marta - Barranquilla");
        assertThat(response.origin()).isEqualTo("Santa Marta");
        assertThat(response.destination()).isEqualTo("Barranquilla");
        assertThat(response.distanceKm()).isEqualByComparingTo("105.50");
        assertThat(response.durationMin()).isEqualTo(150);
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void routeMapper_ShouldMapDetailResponse_WithNestedStops() {
        // When
        RouteDetailResponse response = routeMapper.toDetailResponse(route);

        // Then
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.code()).isEqualTo("SM-BAQ");
        assertThat(response.stops()).hasSize(2);
        assertThat(response.stops()).extracting(StopResponse::id).containsExactly(100L, 101L);
        assertThat(response.stops()).extracting(StopResponse::order).containsExactly(1, 2);
    }

    @Test
    void routeMapper_ShouldMapDetailResponse_WithNullStops() {
        // Given
        route.setStops(null);

        // When
        RouteDetailResponse response = routeMapper.toDetailResponse(route);

        // Then
        assertThat(response.stops()).isNull();
        assertThat(routeMapper.toDetailResponse(null)).isNull();
    }

    @Test
    void routeMapper_ShouldMapList_AndHandleNulls() {
        // When
        List<RouteResponse> responses = routeMapper.toResponseList(List.of(route));

        // Then
        assertThat(responses).singleElement().extracting(RouteResponse::code).isEqualTo("SM-BAQ");
        assertThat(routeMapper.toResponse(null)).isNull();
        assertThat(routeMapper.toResponseList(null)).isNull();
        assertThat(routeMapper.toEntity(null)).isNull();
    }

    @Test
    void routeMapper_ShouldMapCreateRequestToEntity_ActiveByDefault() {
        // Given
        RouteCreateRequest request = new RouteCreateRequest("SM-RIO", "Santa Marta - Riohacha",
                "Santa Marta", "Riohacha", new BigDecimal("170"), 180);

        // When
        Route entity = routeMapper.toEntity(request);

        // Then
        assertThat(entity.getId()).isNull();
        assertThat(entity.getCode()).isEqualTo("SM-RIO");
        assertThat(entity.getName()).isEqualTo("Santa Marta - Riohacha");
        assertThat(entity.getOrigin()).isEqualTo("Santa Marta");
        assertThat(entity.getDestination()).isEqualTo("Riohacha");
        assertThat(entity.getDistanceKm()).isEqualByComparingTo("170");
        assertThat(entity.getDurationMin()).isEqualTo(180);
        assertThat(entity.getIsActive()).isTrue();
        assertThat(entity.getStops()).isNull();
        assertThat(entity.getTrips()).isNull();
        assertThat(entity.getFareRules()).isNull();
    }

    @Test
    void routeMapper_ShouldUpdateOnlyNonNullFields_AndKeepImmutableOnes() {
        // Given
        RouteUpdateRequest request = new RouteUpdateRequest(null, null, 160, false);

        // When
        routeMapper.updateEntityFromRequest(request, route);

        // Then
        assertThat(route.getName()).isEqualTo("Santa Marta - Barranquilla");
        assertThat(route.getDistanceKm()).isEqualByComparingTo("105.50");
        assertThat(route.getDurationMin()).isEqualTo(160);
        assertThat(route.getIsActive()).isFalse();
        assertThat(route.getCode()).isEqualTo("SM-BAQ");
        assertThat(route.getOrigin()).isEqualTo("Santa Marta");
        assertThat(route.getDestination()).isEqualTo("Barranquilla");
        assertThat(route.getStops()).hasSize(2);
    }

    // ==================== BaggageMapper ====================

    @Test
    void baggageMapper_ShouldMapEntityToResponse_WithTicketId() {
        // Given
        Ticket ticket = Ticket.builder().id(77L).build();
        Baggage baggage = Baggage.builder()
                .id(3L).ticket(ticket).weightKg(new BigDecimal("23.5"))
                .excessFee(new BigDecimal("5000")).tagCode("BAG-1-000001").createdAt(CREATED_AT)
                .build();

        // When
        BaggageResponse response = baggageMapper.toResponse(baggage);

        // Then
        assertThat(response.id()).isEqualTo(3L);
        assertThat(response.ticketId()).isEqualTo(77L);
        assertThat(response.weightKg()).isEqualByComparingTo("23.5");
        assertThat(response.excessFee()).isEqualByComparingTo("5000");
        assertThat(response.tagCode()).isEqualTo("BAG-1-000001");
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void baggageMapper_ShouldMapList_AndHandleNulls() {
        // Given
        Baggage withoutTicket = Baggage.builder().id(4L).weightKg(BigDecimal.TEN).build();

        // When
        List<BaggageResponse> responses = baggageMapper.toResponseList(List.of(withoutTicket));

        // Then
        assertThat(responses).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(4L);
            assertThat(r.ticketId()).isNull();
        });
        assertThat(baggageMapper.toResponse(null)).isNull();
        assertThat(baggageMapper.toResponseList(null)).isNull();
        assertThat(baggageMapper.toEntity(null)).isNull();
    }

    @Test
    void baggageMapper_ShouldMapCreateRequestToEntity_WithoutTagOrTicket() {
        // Given
        BaggageCreateRequest request = new BaggageCreateRequest(new BigDecimal("30"), new BigDecimal("12000"));

        // When
        Baggage entity = baggageMapper.toEntity(request);

        // Then
        assertThat(entity.getId()).isNull();
        assertThat(entity.getWeightKg()).isEqualByComparingTo("30");
        assertThat(entity.getExcessFee()).isEqualByComparingTo("12000");
        // La etiqueta la genera el servicio y el ticket se asigna después
        assertThat(entity.getTagCode()).isNull();
        assertThat(entity.getTicket()).isNull();
    }

    @Test
    void baggageMapper_ShouldUpdateOnlyNonNullFields() {
        // Given
        Ticket ticket = Ticket.builder().id(77L).build();
        Baggage baggage = Baggage.builder()
                .id(3L).ticket(ticket).weightKg(new BigDecimal("20"))
                .excessFee(new BigDecimal("1000")).tagCode("BAG-TAG")
                .build();
        BaggageUpdateRequest request = new BaggageUpdateRequest(new BigDecimal("25"), null);

        // When
        baggageMapper.updateEntityFromRequest(request, baggage);

        // Then
        assertThat(baggage.getWeightKg()).isEqualByComparingTo("25");
        assertThat(baggage.getExcessFee()).isEqualByComparingTo("1000");
        assertThat(baggage.getTagCode()).isEqualTo("BAG-TAG");
        assertThat(baggage.getTicket()).isSameAs(ticket);
    }

    // ==================== TicketMapper ====================

    private Ticket buildTicket() {
        Ticket ticket = Ticket.builder()
                .id(200L).trip(trip).passenger(passenger).seatNumber(12)
                .fromStop(fromStop).toStop(toStop).price(new BigDecimal("25000"))
                .paymentMethod(Ticket.PaymentMethod.CARD).status(Ticket.TicketStatus.SOLD)
                .qrCode("QR-200").purchasedAt(CREATED_AT)
                .build();
        Baggage baggage = Baggage.builder()
                .id(300L).ticket(ticket).weightKg(new BigDecimal("15")).excessFee(BigDecimal.ZERO)
                .tagCode("BAG-300").build();
        ticket.setBaggage(baggage);
        return ticket;
    }

    @Test
    void ticketMapper_ShouldMapEntityToResponse_WithNestedRelations() {
        // Given
        Ticket ticket = buildTicket();

        // When
        TicketResponse response = ticketMapper.toResponse(ticket);

        // Then
        assertThat(response.id()).isEqualTo(200L);
        assertThat(response.tripId()).isEqualTo(50L);
        assertThat(response.routeName()).isEqualTo("Santa Marta - Barranquilla");
        assertThat(response.tripDate()).isEqualTo(TRIP_DATE);
        assertThat(response.departureTime()).isEqualTo(DEPARTURE);
        assertThat(response.passengerId()).isEqualTo(10L);
        assertThat(response.passengerName()).isEqualTo("Ana Pasajera");
        assertThat(response.passengerEmail()).isEqualTo("ana@test.com");
        assertThat(response.seatNumber()).isEqualTo(12);
        assertThat(response.fromStopId()).isEqualTo(100L);
        assertThat(response.fromStopName()).isEqualTo("Terminal Santa Marta");
        assertThat(response.fromStopOrder()).isEqualTo(1);
        assertThat(response.toStopId()).isEqualTo(101L);
        assertThat(response.toStopName()).isEqualTo("Ciénaga");
        assertThat(response.toStopOrder()).isEqualTo(2);
        assertThat(response.price()).isEqualByComparingTo("25000");
        assertThat(response.paymentMethod()).isEqualTo(Ticket.PaymentMethod.CARD);
        assertThat(response.status()).isEqualTo(Ticket.TicketStatus.SOLD);
        assertThat(response.qrCode()).isEqualTo("QR-200");
        assertThat(response.purchasedAt()).isEqualTo(CREATED_AT);
        // El equipaje se mapea mediante BaggageMapper (uses)
        assertThat(response.baggage()).isNotNull();
        assertThat(response.baggage().id()).isEqualTo(300L);
        assertThat(response.baggage().ticketId()).isEqualTo(200L);
        assertThat(response.baggage().tagCode()).isEqualTo("BAG-300");
    }

    @Test
    void ticketMapper_ShouldMapResponse_WithMissingRelationsAsNull() {
        // Given
        Ticket ticket = Ticket.builder().id(201L).seatNumber(3).build();

        // When
        TicketResponse response = ticketMapper.toResponse(ticket);

        // Then
        assertThat(response.id()).isEqualTo(201L);
        assertThat(response.tripId()).isNull();
        assertThat(response.routeName()).isNull();
        assertThat(response.passengerId()).isNull();
        assertThat(response.fromStopId()).isNull();
        assertThat(response.toStopName()).isNull();
        assertThat(response.baggage()).isNull();
    }

    @Test
    void ticketMapper_ShouldMapResponse_WithTripWithoutRoute() {
        // Given
        Trip tripWithoutRoute = Trip.builder().id(51L).tripDate(TRIP_DATE).build();
        Ticket ticket = Ticket.builder().id(202L).trip(tripWithoutRoute).build();

        // When
        TicketResponse response = ticketMapper.toResponse(ticket);

        // Then
        assertThat(response.tripId()).isEqualTo(51L);
        assertThat(response.tripDate()).isEqualTo(TRIP_DATE);
        assertThat(response.routeName()).isNull();
    }

    @Test
    void ticketMapper_ShouldMapList_AndHandleNulls() {
        // When
        List<TicketResponse> responses = ticketMapper.toResponseList(List.of(buildTicket()));

        // Then
        assertThat(responses).singleElement().extracting(TicketResponse::id).isEqualTo(200L);
        assertThat(ticketMapper.toResponse(null)).isNull();
        assertThat(ticketMapper.toResponseList(null)).isNull();
        assertThat(ticketMapper.toEntity(null)).isNull();
    }

    @Test
    void ticketMapper_ShouldMapCreateRequestToEntity_WithSoldStatusAndNoRelations() {
        // Given
        TicketCreateRequest request = new TicketCreateRequest(50L, 10L, 7, 100L, "Terminal Santa Marta", 1,
                101L, "Ciénaga", 2, new BigDecimal("18000"), Ticket.PaymentMethod.CASH,
                new BaggageCreateRequest(new BigDecimal("10"), null), "STUDENT");

        // When
        Ticket entity = ticketMapper.toEntity(request);

        // Then
        assertThat(entity.getId()).isNull();
        assertThat(entity.getSeatNumber()).isEqualTo(7);
        assertThat(entity.getPrice()).isEqualByComparingTo("18000");
        assertThat(entity.getPaymentMethod()).isEqualTo(Ticket.PaymentMethod.CASH);
        assertThat(entity.getStatus()).isEqualTo(Ticket.TicketStatus.SOLD);
        // Las relaciones, el QR y el equipaje los resuelve el servicio
        assertThat(entity.getTrip()).isNull();
        assertThat(entity.getPassenger()).isNull();
        assertThat(entity.getFromStop()).isNull();
        assertThat(entity.getToStop()).isNull();
        assertThat(entity.getQrCode()).isNull();
        assertThat(entity.getBaggage()).isNull();
    }

    // ==================== AssignmentMapper ====================

    @Test
    void assignmentMapper_ShouldMapEntityToResponse_WithDriverAndDispatcher() {
        // Given
        Assignment assignment = Assignment.builder()
                .id(9L).trip(trip).driver(driver).dispatcher(dispatcher)
                .checklistOk(true).soatValid(true).revisionValid(false).assignedAt(CREATED_AT)
                .build();

        // When
        AssignmentResponse response = assignmentMapper.toResponse(assignment);

        // Then
        assertThat(response.id()).isEqualTo(9L);
        assertThat(response.tripId()).isEqualTo(50L);
        assertThat(response.driverId()).isEqualTo(20L);
        assertThat(response.driverName()).isEqualTo("Carlos Conductor");
        assertThat(response.driverPhone()).isEqualTo("3002222222");
        assertThat(response.dispatcherId()).isEqualTo(30L);
        assertThat(response.dispatcherName()).isEqualTo("Diana Despachadora");
        assertThat(response.checklistOk()).isTrue();
        assertThat(response.soatValid()).isTrue();
        assertThat(response.revisionValid()).isFalse();
        assertThat(response.assignedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void assignmentMapper_ShouldMapResponse_WithoutDispatcher() {
        // Given: el despachador es opcional en la entidad
        Assignment assignment = Assignment.builder().id(10L).trip(trip).driver(driver).build();

        // When
        AssignmentResponse response = assignmentMapper.toResponse(assignment);

        // Then
        assertThat(response.driverId()).isEqualTo(20L);
        assertThat(response.dispatcherId()).isNull();
        assertThat(response.dispatcherName()).isNull();
    }

    @Test
    void assignmentMapper_ShouldMapList_AndHandleNulls() {
        // Given
        Assignment assignment = Assignment.builder().id(9L).trip(trip).driver(driver).build();

        // When
        List<AssignmentResponse> responses = assignmentMapper.toResponseList(List.of(assignment));

        // Then
        assertThat(responses).singleElement().extracting(AssignmentResponse::driverName)
                .isEqualTo("Carlos Conductor");
        assertThat(assignmentMapper.toResponse(null)).isNull();
        assertThat(assignmentMapper.toResponseList(null)).isNull();
        assertThat(assignmentMapper.toEntity(null)).isNull();
    }

    @Test
    void assignmentMapper_ShouldMapCreateRequestToEntity_WithChecklistFalseAndNoRelations() {
        // Given
        AssignmentCreateRequest request = new AssignmentCreateRequest(50L, 20L, 30L);

        // When
        Assignment entity = assignmentMapper.toEntity(request);

        // Then
        assertThat(entity.getId()).isNull();
        assertThat(entity.getChecklistOk()).isFalse();
        assertThat(entity.getSoatValid()).isFalse();
        assertThat(entity.getRevisionValid()).isFalse();
        assertThat(entity.getTrip()).isNull();
        assertThat(entity.getDriver()).isNull();
        assertThat(entity.getDispatcher()).isNull();
    }

    @Test
    void assignmentMapper_ShouldUpdateOnlyNonNullFields_AndNeverChangeRelations() {
        // Given
        Assignment assignment = Assignment.builder()
                .id(9L).trip(trip).driver(driver).dispatcher(dispatcher)
                .checklistOk(false).soatValid(true).revisionValid(true).assignedAt(CREATED_AT)
                .build();
        // driverId no se mapea: el cambio de conductor lo gestiona el servicio
        AssignmentUpdateRequest request = new AssignmentUpdateRequest(99L, true, null, null);

        // When
        assignmentMapper.updateEntityFromRequest(request, assignment);

        // Then
        assertThat(assignment.getChecklistOk()).isTrue();
        assertThat(assignment.getSoatValid()).isTrue();
        assertThat(assignment.getRevisionValid()).isTrue();
        assertThat(assignment.getDriver()).isSameAs(driver);
        assertThat(assignment.getTrip()).isSameAs(trip);
        assertThat(assignment.getDispatcher()).isSameAs(dispatcher);
        assertThat(assignment.getAssignedAt()).isEqualTo(CREATED_AT);
    }

    // ==================== TripMapper ====================

    @Test
    void tripMapper_ShouldMapEntityToResponse_WithRouteAndBusInfo() {
        // When
        TripResponse response = tripMapper.toResponse(trip);

        // Then
        assertThat(response.id()).isEqualTo(50L);
        assertThat(response.routeId()).isEqualTo(1L);
        assertThat(response.routeName()).isEqualTo("Santa Marta - Barranquilla");
        assertThat(response.routeOrigin()).isEqualTo("Santa Marta");
        assertThat(response.routeDestination()).isEqualTo("Barranquilla");
        assertThat(response.busId()).isEqualTo(5L);
        assertThat(response.busPlate()).isEqualTo("ABC123");
        assertThat(response.busCapacity()).isEqualTo(40);
        assertThat(response.tripDate()).isEqualTo(TRIP_DATE);
        assertThat(response.departureTime()).isEqualTo(DEPARTURE);
        assertThat(response.arrivalEta()).isEqualTo(ARRIVAL);
        assertThat(response.status()).isEqualTo(Trip.TripStatus.BOARDING);
        // Los campos calculados los completa el servicio
        assertThat(response.soldSeats()).isNull();
        assertThat(response.occupancyPercentage()).isNull();
    }

    @Test
    void tripMapper_ShouldMapDetailResponse_WithNestedRouteBusAndAssignment() {
        // Given
        Assignment assignment = Assignment.builder()
                .id(9L).trip(trip).driver(driver).dispatcher(dispatcher).checklistOk(true)
                .build();
        trip.setAssignment(assignment);

        // When
        TripDetailResponse response = tripMapper.toDetailResponse(trip);

        // Then
        assertThat(response.id()).isEqualTo(50L);
        assertThat(response.route()).isNotNull();
        assertThat(response.route().id()).isEqualTo(1L);
        assertThat(response.route().code()).isEqualTo("SM-BAQ");
        assertThat(response.bus()).isNotNull();
        assertThat(response.bus().plate()).isEqualTo("ABC123");
        assertThat(response.assignment()).isNotNull();
        assertThat(response.assignment().driverName()).isEqualTo("Carlos Conductor");
        assertThat(response.assignment().tripId()).isEqualTo(50L);
        assertThat(response.status()).isEqualTo(Trip.TripStatus.BOARDING);
        assertThat(response.soldSeats()).isNull();
        assertThat(response.availableSeats()).isNull();
        assertThat(response.occupancyPercentage()).isNull();
        assertThat(response.availableSeatNumbers()).isNull();
    }

    @Test
    void tripMapper_ShouldMapDetailResponse_WithoutAssignment() {
        // When
        TripDetailResponse response = tripMapper.toDetailResponse(trip);

        // Then
        assertThat(response.assignment()).isNull();
        assertThat(response.route()).isNotNull();
    }

    @Test
    void tripMapper_ShouldMapList_AndHandleNulls() {
        // Given
        Trip tripWithoutRelations = Trip.builder().id(60L).build();

        // When
        List<TripResponse> responses = tripMapper.toResponseList(List.of(trip, tripWithoutRelations));

        // Then
        assertThat(responses).extracting(TripResponse::id).containsExactly(50L, 60L);
        assertThat(responses.get(1).routeId()).isNull();
        assertThat(responses.get(1).busPlate()).isNull();
        assertThat(tripMapper.toResponse(null)).isNull();
        assertThat(tripMapper.toResponseList(null)).isNull();
        assertThat(tripMapper.toDetailResponse(null)).isNull();
        assertThat(tripMapper.toEntity(null)).isNull();
    }

    @Test
    void tripMapper_ShouldMapCreateRequestToEntity_WithScheduledStatus() {
        // Given
        TripCreateRequest request = new TripCreateRequest(1L, 5L, TRIP_DATE, DEPARTURE, ARRIVAL);

        // When
        Trip entity = tripMapper.toEntity(request);

        // Then
        assertThat(entity.getId()).isNull();
        assertThat(entity.getTripDate()).isEqualTo(TRIP_DATE);
        assertThat(entity.getDepartureTime()).isEqualTo(DEPARTURE);
        assertThat(entity.getArrivalEta()).isEqualTo(ARRIVAL);
        assertThat(entity.getStatus()).isEqualTo(Trip.TripStatus.SCHEDULED);
        assertThat(entity.getRoute()).isNull();
        assertThat(entity.getBus()).isNull();
        assertThat(entity.getAssignment()).isNull();
        assertThat(entity.getTickets()).isNull();
    }

    @Test
    void tripMapper_ShouldUpdateOnlyNonNullFields() {
        // Given
        LocalDateTime newArrival = ARRIVAL.plusHours(1);
        TripUpdateRequest request = new TripUpdateRequest(null, newArrival, null);

        // When
        tripMapper.updateEntityFromRequest(request, trip);

        // Then
        assertThat(trip.getDepartureTime()).isEqualTo(DEPARTURE);
        assertThat(trip.getArrivalEta()).isEqualTo(newArrival);
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.BOARDING);
        assertThat(trip.getTripDate()).isEqualTo(TRIP_DATE);
        assertThat(trip.getRoute()).isSameAs(route);
        assertThat(trip.getBus()).isSameAs(bus);
    }

    @Test
    void tripMapper_ShouldNotChangeStatusOrBus_OnReschedule() {
        // Given: el bus se valida y asigna en el servicio; el estado no se cambia por la reprogramación
        LocalDateTime newDeparture = DEPARTURE.plusHours(2);
        TripUpdateRequest request = new TripUpdateRequest(newDeparture, null, 999L);

        // When
        tripMapper.updateEntityFromRequest(request, trip);

        // Then
        assertThat(trip.getDepartureTime()).isEqualTo(newDeparture);
        assertThat(trip.getStatus()).isEqualTo(Trip.TripStatus.BOARDING);
        assertThat(trip.getBus()).isSameAs(bus);
        assertThat(trip.getArrivalEta()).isEqualTo(ARRIVAL);
    }

    // ==================== ParcelMapper ====================

    private Parcel buildParcel() {
        return Parcel.builder()
                .id(400L).code("PCL-1700000000000-123456").trip(trip)
                .senderName("Remitente").senderPhone("3004444444")
                .receiverName("Destinatario").receiverPhone("3005555555")
                .fromStop(fromStop).toStop(toStop)
                .price(new BigDecimal("15000")).weightKg(new BigDecimal("3.5"))
                .status(Parcel.ParcelStatus.IN_TRANSIT).deliveryOtp("654321")
                .proofPhotoUrl("https://fotos/prueba.jpg").createdAt(CREATED_AT)
                .build();
    }

    @Test
    void parcelMapper_ShouldMapEntityToResponse_IncludingOtp() {
        // Given
        Parcel parcel = buildParcel();

        // When
        ParcelResponse response = parcelMapper.toResponseWithOtp(parcel);

        // Then
        assertThat(response.id()).isEqualTo(400L);
        assertThat(response.code()).isEqualTo("PCL-1700000000000-123456");
        assertThat(response.tripId()).isEqualTo(50L);
        assertThat(response.routeName()).isEqualTo("Santa Marta - Barranquilla");
        assertThat(response.tripDate()).isEqualTo(TRIP_DATE);
        assertThat(response.senderName()).isEqualTo("Remitente");
        assertThat(response.senderPhone()).isEqualTo("3004444444");
        assertThat(response.receiverName()).isEqualTo("Destinatario");
        assertThat(response.receiverPhone()).isEqualTo("3005555555");
        assertThat(response.fromStopId()).isEqualTo(100L);
        assertThat(response.fromStopName()).isEqualTo("Terminal Santa Marta");
        assertThat(response.toStopId()).isEqualTo(101L);
        assertThat(response.toStopName()).isEqualTo("Ciénaga");
        assertThat(response.price()).isEqualByComparingTo("15000");
        assertThat(response.weightKg()).isEqualByComparingTo("3.5");
        assertThat(response.status()).isEqualTo(Parcel.ParcelStatus.IN_TRANSIT);
        assertThat(response.deliveryOtp()).isEqualTo("654321");
        assertThat(response.proofPhotoUrl()).isEqualTo("https://fotos/prueba.jpg");
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void parcelMapper_ToPublicResponse_ShouldNeverExposeDeliveryOtp() {
        // Given: caso de seguridad, el rastreo público no puede filtrar el OTP de entrega
        Parcel parcel = buildParcel();

        // When
        ParcelResponse publicResponse = parcelMapper.toPublicResponse(parcel);
        ParcelResponse internalResponse = parcelMapper.toResponseWithOtp(parcel);

        // Then
        assertThat(publicResponse.deliveryOtp()).isNull();
        assertThat(internalResponse.deliveryOtp()).isEqualTo("654321");
        // El rastreo público tampoco expone datos personales de remitente y destinatario
        assertThat(publicResponse.senderName()).isNull();
        assertThat(publicResponse.senderPhone()).isNull();
        assertThat(publicResponse.receiverName()).isNull();
        assertThat(publicResponse.receiverPhone()).isNull();
        // El resto de la información se hereda de toResponse
        assertThat(publicResponse.id()).isEqualTo(400L);
        assertThat(publicResponse.code()).isEqualTo(parcel.getCode());
        assertThat(publicResponse.tripId()).isEqualTo(50L);
        assertThat(publicResponse.routeName()).isEqualTo("Santa Marta - Barranquilla");
        assertThat(publicResponse.fromStopName()).isEqualTo("Terminal Santa Marta");
        assertThat(publicResponse.toStopName()).isEqualTo("Ciénaga");
        assertThat(publicResponse.status()).isEqualTo(Parcel.ParcelStatus.IN_TRANSIT);
        assertThat(publicResponse).usingRecursiveComparison()
                .ignoringFields("deliveryOtp", "senderName", "senderPhone", "receiverName", "receiverPhone")
                .isEqualTo(internalResponse);
        // El OTP no debe aparecer ni siquiera en la representación textual del record
        assertThat(publicResponse.toString()).doesNotContain("654321");
        assertThat(parcelMapper.toPublicResponse(null)).isNull();
    }

    @Test
    void parcelMapper_ToResponseList_ShouldUseToResponse() {
        // Given
        Parcel parcel = buildParcel();

        // When
        List<ParcelResponse> responses = parcelMapper.toResponseList(List.of(parcel));

        // Then: la lista es para el personal: usa toResponse, que no incluye el OTP (solo lo conoce el destinatario)
        assertThat(responses).singleElement().satisfies(r -> {
            assertThat(r.deliveryOtp()).isNull();
            assertThat(r.receiverPhone()).isEqualTo("3005555555");
            assertThat(r).isEqualTo(parcelMapper.toResponse(parcel));
        });
        assertThat(parcelMapper.toResponseList(null)).isNull();
        assertThat(parcelMapper.toResponse(null)).isNull();
    }

    @Test
    void parcelMapper_ShouldMapCreateRequestToEntity_WithCreatedStatusAndNoOtp() {
        // Given
        ParcelCreateRequest request = new ParcelCreateRequest(50L, "Remitente", "3004444444",
                "Destinatario", "3005555555", 100L, "Terminal Santa Marta", 101L, "Ciénaga",
                new BigDecimal("15000"), new BigDecimal("2"), "Documentos");

        // When
        Parcel entity = parcelMapper.toEntity(request);

        // Then
        assertThat(entity.getId()).isNull();
        assertThat(entity.getSenderName()).isEqualTo("Remitente");
        assertThat(entity.getSenderPhone()).isEqualTo("3004444444");
        assertThat(entity.getReceiverName()).isEqualTo("Destinatario");
        assertThat(entity.getReceiverPhone()).isEqualTo("3005555555");
        assertThat(entity.getPrice()).isEqualByComparingTo("15000");
        assertThat(entity.getWeightKg()).isEqualByComparingTo("2");
        assertThat(entity.getStatus()).isEqualTo(Parcel.ParcelStatus.CREATED);
        // Código, OTP y relaciones los genera/resuelve el servicio
        assertThat(entity.getCode()).isNull();
        assertThat(entity.getDeliveryOtp()).isNull();
        assertThat(entity.getProofPhotoUrl()).isNull();
        assertThat(entity.getDeliveredAt()).isNull();
        assertThat(entity.getTrip()).isNull();
        assertThat(entity.getFromStop()).isNull();
        assertThat(entity.getToStop()).isNull();
        assertThat(parcelMapper.toEntity(null)).isNull();
    }

    // ==================== SeatHoldMapper ====================

    @Test
    void seatHoldMapper_ShouldMapEntityToResponse_WithTripAndUserIds() {
        // Given
        LocalDateTime expiresAt = CREATED_AT.plusMinutes(10);
        SeatHold hold = SeatHold.builder()
                .id(600L).trip(trip).user(passenger).seatNumber(8)
                .expiresAt(expiresAt).status(SeatHold.HoldStatus.HOLD).createdAt(CREATED_AT)
                .build();

        // When
        SeatHoldResponse response = seatHoldMapper.toResponse(hold);

        // Then
        assertThat(response.id()).isEqualTo(600L);
        assertThat(response.tripId()).isEqualTo(50L);
        assertThat(response.userId()).isEqualTo(10L);
        assertThat(response.seatNumber()).isEqualTo(8);
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
        assertThat(response.status()).isEqualTo(SeatHold.HoldStatus.HOLD);
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void seatHoldMapper_ShouldMapList_AndHandleNulls() {
        // Given
        SeatHold hold = SeatHold.builder().id(601L).seatNumber(9).status(SeatHold.HoldStatus.EXPIRED).build();

        // When
        List<SeatHoldResponse> responses = seatHoldMapper.toResponseList(List.of(hold));

        // Then
        assertThat(responses).singleElement().satisfies(r -> {
            assertThat(r.tripId()).isNull();
            assertThat(r.userId()).isNull();
            assertThat(r.status()).isEqualTo(SeatHold.HoldStatus.EXPIRED);
        });
        assertThat(seatHoldMapper.toResponse(null)).isNull();
        assertThat(seatHoldMapper.toResponseList(null)).isNull();
        assertThat(seatHoldMapper.toEntity(null)).isNull();
    }

    @Test
    void seatHoldMapper_ShouldMapCreateRequestToEntity_WithHoldStatus() {
        // Given
        SeatHoldCreateRequest request = new SeatHoldCreateRequest(50L, 14, 10L);

        // When
        SeatHold entity = seatHoldMapper.toEntity(request);

        // Then
        assertThat(entity.getId()).isNull();
        assertThat(entity.getSeatNumber()).isEqualTo(14);
        assertThat(entity.getStatus()).isEqualTo(SeatHold.HoldStatus.HOLD);
        // La expiración se calcula en el servicio
        assertThat(entity.getExpiresAt()).isNull();
        assertThat(entity.getTrip()).isNull();
        assertThat(entity.getUser()).isNull();
    }

    // ==================== PaymentMapper ====================

    @Test
    void paymentMapper_ShouldBuildPaymentResponse_FromTicketAndParams() {
        // Given
        Ticket ticket = buildTicket();

        // When
        PaymentResponse response = paymentMapper.toPaymentResponse(ticket, "CONFIRMED", "TX-001");

        // Then
        assertThat(response.ticketId()).isEqualTo(200L);
        assertThat(response.paymentMethod()).isEqualTo(Ticket.PaymentMethod.CARD);
        assertThat(response.amount()).isEqualByComparingTo("25000");
        assertThat(response.paidAt()).isEqualTo(CREATED_AT);
        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.transactionReference()).isEqualTo("TX-001");
    }

    @Test
    void paymentMapper_ShouldHandleNullTicket_AndAllNullParams() {
        // When
        PaymentResponse withoutTicket = paymentMapper.toPaymentResponse(null, "PENDING", null);

        // Then
        assertThat(withoutTicket).isNotNull();
        assertThat(withoutTicket.ticketId()).isNull();
        assertThat(withoutTicket.amount()).isNull();
        assertThat(withoutTicket.status()).isEqualTo("PENDING");
        assertThat(withoutTicket.transactionReference()).isNull();
        assertThat(paymentMapper.toPaymentResponse(null, null, null)).isNull();
    }
}
