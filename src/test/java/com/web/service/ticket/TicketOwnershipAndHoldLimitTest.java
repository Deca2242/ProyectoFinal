package com.web.service.ticket;

import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.dto.ticket.reservations.SeatHoldRequest;
import com.web.dto.ticket.reservations.mapper.SeatHoldMapper;
import com.web.entity.*;
import com.web.exception.BusinessException;
import com.web.repository.*;
import com.web.service.admin.ConfigService;
import com.web.util.QrCodeGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Un PASSENGER solo opera sobre sus propios tickets y holds; tope de holds activos por usuario y viaje
class TicketOwnershipAndHoldLimitTest {

    private static Route route = Route.builder().id(1L).build();
    private static Stop stopA = Stop.builder().id(10L).route(route).order(1).build();
    private static Stop stopB = Stop.builder().id(11L).route(route).order(2).build();

    private static void authenticateAs(String email, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static Trip trip() {
        return Trip.builder()
                .id(1L)
                .route(route)
                .bus(Bus.builder().id(1L).capacity(40).build())
                .departureTime(LocalDateTime.now().plusDays(1))
                .status(Trip.TripStatus.SCHEDULED)
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Tickets {

        @Mock
        private TicketRepository ticketRepository;
        @Mock
        private TripRepository tripRepository;
        @Mock
        private StopRepository stopRepository;
        @Mock
        private UserRepository userRepository;
        @Mock
        private FareRuleRepository fareRuleRepository;
        @Mock
        private BaggageRepository baggageRepository;
        @Mock
        private SeatHoldRepository seatHoldRepository;
        @Mock
        private TicketMapper ticketMapper;
        @Mock
        private SeatHoldService seatHoldService;
        @Mock
        private QrCodeGenerator qrCodeGenerator;
        @Mock
        private ConfigService configService;
        @Mock
        private AssignmentRepository assignmentRepository;
        @Mock
        private com.web.service.notification.NotificationService notificationService;

        @InjectMocks
        private TicketServiceImpl ticketService;

        private final User ana = User.builder().id(1L).email("ana@test.com").build();
        private final User luis = User.builder().id(2L).email("luis@test.com").build();

        @Test
        void shouldPurchaseTicket_AsPassengerForAnotherPassenger_ThrowForbidden() {
            // Given
            authenticateAs("luis@test.com", "PASSENGER");
            when(tripRepository.findById(1L)).thenReturn(Optional.of(trip()));
            when(userRepository.findById(1L)).thenReturn(Optional.of(ana));
            TicketCreateRequest request = new TicketCreateRequest(1L, 1L, 5, 10L, null, null, 11L, null, null,
                    BigDecimal.TEN, Ticket.PaymentMethod.CASH, null, null);

            // When/Then
            assertThatThrownBy(() -> ticketService.purchaseTicket(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        assertThat(((BusinessException) ex).getCode()).isEqualTo("NOT_TICKET_OWNER");
                        assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    });
            verifyNoInteractions(stopRepository);
        }

        @Test
        void shouldGetTicketById_AsOtherPassenger_ThrowForbidden() {
            // Given
            authenticateAs("luis@test.com", "PASSENGER");
            Ticket ticket = Ticket.builder().id(7L).passenger(ana).build();
            when(ticketRepository.findById(7L)).thenReturn(Optional.of(ticket));

            // When/Then
            assertThatThrownBy(() -> ticketService.getTicketById(7L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo("NOT_TICKET_OWNER");
            verifyNoInteractions(ticketMapper);
        }

        @Test
        void shouldGetTicketById_AsOwnerWithDifferentEmailCase_ReturnTicket() {
            // Given
            authenticateAs("ANA@test.com", "PASSENGER");
            Ticket ticket = Ticket.builder().id(7L).passenger(ana).build();
            when(ticketRepository.findById(7L)).thenReturn(Optional.of(ticket));

            // When
            ticketService.getTicketById(7L);

            // Then
            verify(ticketMapper).toResponse(ticket);
        }

        @Test
        void shouldCancelTicket_AsOtherPassenger_ThrowForbidden() {
            // Given
            authenticateAs("ana@test.com", "PASSENGER");
            Ticket ticket = Ticket.builder().id(8L).passenger(luis).status(Ticket.TicketStatus.SOLD).build();
            when(ticketRepository.findById(8L)).thenReturn(Optional.of(ticket));

            // When/Then
            assertThatThrownBy(() -> ticketService.cancelTicket(8L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo("NOT_TICKET_OWNER");
            verify(ticketRepository, never()).save(any());
        }

        @Test
        void shouldGetTicketById_AsClerk_ReturnAnyTicket() {
            // Given: la taquilla puede consultar tickets de terceros
            authenticateAs("clerk@test.com", "CLERK");
            Ticket ticket = Ticket.builder().id(7L).passenger(ana).build();
            when(ticketRepository.findById(7L)).thenReturn(Optional.of(ticket));

            // When
            ticketService.getTicketById(7L);

            // Then
            verify(ticketMapper).toResponse(ticket);
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Holds {

        @Mock
        private SeatHoldRepository seatHoldRepository;
        @Mock
        private TicketRepository ticketRepository;
        @Mock
        private TripRepository tripRepository;
        @Mock
        private StopRepository stopRepository;
        @Mock
        private UserRepository userRepository;
        @Mock
        private SeatHoldMapper seatHoldMapper;
        @Mock
        private ConfigService configService;

        @InjectMocks
        private SeatHoldServiceImpl seatHoldService;

        private final User ana = User.builder().id(1L).email("ana@test.com").build();

        @BeforeEach
        void givenTripAndStops() {
            when(tripRepository.findById(1L)).thenReturn(Optional.of(trip()));
            when(stopRepository.findById(10L)).thenReturn(Optional.of(stopA));
            when(stopRepository.findById(11L)).thenReturn(Optional.of(stopB));
            when(userRepository.findById(1L)).thenReturn(Optional.of(ana));
        }

        @Test
        void shouldCreateHold_AsPassengerForAnotherUser_ThrowForbidden() {
            // Given
            authenticateAs("luis@test.com", "PASSENGER");

            // When/Then
            assertThatThrownBy(() -> seatHoldService.createHold(1L, 5, new SeatHoldRequest(1L, 10L, 11L)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo("NOT_HOLD_OWNER");
            verifyNoInteractions(seatHoldRepository);
        }

        @Test
        void shouldCreateHold_WithFourActiveHolds_ThrowHoldLimitReached() {
            // Given
            List<SeatHold> active = List.of(hold(1L), hold(2L), hold(3L), hold(4L));
            when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(5), anyInt(), anyInt(), any(LocalDateTime.class)))
                    .thenReturn(List.of());
            when(ticketRepository.isSeatAvailableForSegment(1L, 5, 1, 2)).thenReturn(true);
            when(seatHoldRepository.findUserActiveHoldsForTrip(eq(1L), eq(1L), any(LocalDateTime.class))).thenReturn(active);

            // When/Then
            assertThatThrownBy(() -> seatHoldService.createHold(1L, 5, new SeatHoldRequest(1L, 10L, 11L)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo("HOLD_LIMIT_REACHED");
            verify(seatHoldRepository, never()).save(any());
        }

        @Test
        void shouldCreateHold_ReplacingOwnOverlappingHold_NotCountItTowardsTheLimit() {
            // Given: 4 holds activos, pero uno de ellos es el que se reemplaza (mismo asiento, otro tramo)
            SeatHold replaced = hold(4L);
            replaced.setSeatNumber(5);
            replaced.setFromStop(stopA);
            replaced.setToStop(Stop.builder().id(12L).route(route).order(3).build());
            List<SeatHold> active = List.of(hold(1L), hold(2L), hold(3L), replaced);
            when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(5), anyInt(), anyInt(), any(LocalDateTime.class)))
                    .thenReturn(List.of(replaced));
            when(ticketRepository.isSeatAvailableForSegment(1L, 5, 1, 2)).thenReturn(true);
            when(seatHoldRepository.findUserActiveHoldsForTrip(eq(1L), eq(1L), any(LocalDateTime.class))).thenReturn(active);
            when(configService.getHoldDurationMinutes()).thenReturn(10);
            when(seatHoldRepository.save(any(SeatHold.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            seatHoldService.createHold(1L, 5, new SeatHoldRequest(1L, 10L, 11L));

            // Then
            assertThat(replaced.getStatus()).isEqualTo(SeatHold.HoldStatus.EXPIRED);
            verify(seatHoldRepository, times(2)).save(any(SeatHold.class));
        }

        private SeatHold hold(Long id) {
            return SeatHold.builder().id(id).user(ana).seatNumber(id.intValue()).status(SeatHold.HoldStatus.HOLD).build();
        }
    }
}
