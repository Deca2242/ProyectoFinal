package com.web.service.notification;

import com.web.dto.notification.NotificationResponse;
import com.web.dto.notification.mapper.NotificationMapper;
import com.web.dto.notification.mapper.NotificationMapperImpl;
import com.web.entity.Notification;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.NotificationRepository;
import com.web.repository.TicketRepository;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Notificaciones simuladas por WhatsApp/SMS: canal, destinatarios, mensaje y tolerancia a fallos del envío
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationSender notificationSender;
    @Spy
    private NotificationMapper notificationMapper = new NotificationMapperImpl();

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private Trip trip;
    private Stop fromStop;
    private Stop toStop;
    private User ana;
    private User luis;

    @BeforeEach
    void setUp() {
        Route route = Route.builder().id(1L).name("Ruta").origin("Bogotá").destination("Tunja").build();
        fromStop = Stop.builder().id(10L).name("Terminal Bogotá").order(1).route(route).build();
        toStop = Stop.builder().id(11L).name("Terminal Tunja").order(2).route(route).build();
        trip = Trip.builder()
                .id(5L)
                .route(route)
                .departureTime(LocalDateTime.of(2030, 1, 15, 8, 30))
                .arrivalEta(LocalDateTime.of(2030, 1, 15, 11, 0))
                .platform("A3")
                .build();
        ana = User.builder().id(1L).name("Ana").email("ana@test.com").phone("3001234567").build();
        luis = User.builder().id(2L).name("Luis").email("luis@test.com").phone("6017654321").build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Ticket ticket(Long id, User passenger, int seat) {
        return Ticket.builder()
                .id(id)
                .trip(trip)
                .passenger(passenger)
                .seatNumber(seat)
                .fromStop(fromStop)
                .toStop(toStop)
                .qrCode("QR-" + id)
                .build();
    }

    private Notification savedNotification() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        return captor.getValue();
    }

    // ---------- Regla de canal ----------

    @ParameterizedTest
    @CsvSource({
            "3001234567, WHATSAPP",
            "+57 300 123 4567, WHATSAPP",
            "573151234567, WHATSAPP",
            "6017654321, SMS",
            "+1 555 123 4567, SMS",
            "300, WHATSAPP"
    })
    void shouldResolveChannel_MobileStartingWith3IsWhatsappOtherwiseSms(String phone, Notification.Channel expected) {
        // When/Then
        assertThat(NotificationServiceImpl.resolveChannel(phone)).isEqualTo(expected);
    }

    // ---------- Compra ----------

    @Test
    void shouldNotifyTicketPurchased_SendWhatsappWithQrSeatSegmentAndDeparture() {
        // Given
        Ticket ticket = ticket(100L, ana, 7);

        // When
        notificationService.notifyTicketPurchased(ticket);

        // Then
        verify(notificationSender).send(eq(Notification.Channel.WHATSAPP), eq("3001234567"), anyString());
        Notification saved = savedNotification();
        assertThat(saved.getType()).isEqualTo(Notification.NotificationType.TICKET_PURCHASED);
        assertThat(saved.getChannel()).isEqualTo(Notification.Channel.WHATSAPP);
        assertThat(saved.getStatus()).isEqualTo(Notification.NotificationStatus.SENT);
        assertThat(saved.getUser()).isSameAs(ana);
        assertThat(saved.getTicket()).isSameAs(ticket);
        assertThat(saved.getTrip()).isSameAs(trip);
        assertThat(saved.getRecipient()).isEqualTo("3001234567");
        assertThat(saved.getMessage())
                .contains("QR-100")
                .contains("silla 7")
                .contains("Terminal Bogotá → Terminal Tunja")
                .contains("15/01/2030 08:30")
                .contains("andén A3");
    }

    @Test
    void shouldNotifyTicketPurchased_WithLandlinePhone_UseSms() {
        // Given
        Ticket ticket = ticket(101L, luis, 3);

        // When
        notificationService.notifyTicketPurchased(ticket);

        // Then
        verify(notificationSender).send(eq(Notification.Channel.SMS), eq("6017654321"), anyString());
        assertThat(savedNotification().getChannel()).isEqualTo(Notification.Channel.SMS);
    }

    @Test
    void shouldNotifyTicketPurchased_WithoutPhone_NotSendNorSave() {
        // Given
        ana.setPhone("  ");
        Ticket ticket = ticket(102L, ana, 1);

        // When
        notificationService.notifyTicketPurchased(ticket);

        // Then
        verifyNoInteractions(notificationSender, notificationRepository);
    }

    @Test
    void shouldNotifyTicketPurchased_WithNullPhone_NotSendNorSave() {
        // Given
        ana.setPhone(null);

        // When
        notificationService.notifyTicketPurchased(ticket(103L, ana, 1));

        // Then
        verifyNoInteractions(notificationSender, notificationRepository);
    }

    @Test
    void shouldNotifyTicketPurchased_WhenSenderFails_SaveFailedAndNotPropagate() {
        // Given
        doThrow(new IllegalStateException("proveedor caído"))
                .when(notificationSender).send(any(), anyString(), anyString());

        // When
        notificationService.notifyTicketPurchased(ticket(104L, ana, 2));

        // Then: la compra no se ve afectada y queda registro del fallo
        assertThat(savedNotification().getStatus()).isEqualTo(Notification.NotificationStatus.FAILED);
    }

    @Test
    void shouldNotifyTicketPurchased_WhenRepositoryFails_NotPropagate() {
        // Given
        when(notificationRepository.save(any())).thenThrow(new IllegalStateException("BD"));

        // When/Then: no lanza excepción
        notificationService.notifyTicketPurchased(ticket(105L, ana, 2));
        verify(notificationSender).send(any(), anyString(), anyString());
    }

    @Test
    void shouldNotifyTicketPurchased_WithoutRouteNorPlatform_StillBuildMessage() {
        // Given
        trip.setRoute(null);
        trip.setPlatform(null);
        Ticket ticket = ticket(106L, ana, 4);
        ticket.setFromStop(null);

        // When
        notificationService.notifyTicketPurchased(ticket);

        // Then
        assertThat(savedNotification().getMessage())
                .contains("#5")
                .contains("- → Terminal Tunja")
                .doesNotContain("andén");
    }

    // ---------- Notificaciones a los pasajeros del viaje ----------

    @Test
    void shouldNotifyPlatformChanged_NotifyEachSoldPassengerOnce() {
        // Given: Ana tiene dos tickets y Luis uno
        when(ticketRepository.findByTripIdAndStatus(5L, Ticket.TicketStatus.SOLD))
                .thenReturn(List.of(ticket(1L, ana, 1), ticket(2L, ana, 2), ticket(3L, luis, 3)));

        // When
        notificationService.notifyPlatformChanged(trip, "B1");

        // Then
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(n -> n.getUser().getId())
                .containsExactly(1L, 2L);
        assertThat(captor.getAllValues())
                .allSatisfy(n -> {
                    assertThat(n.getType()).isEqualTo(Notification.NotificationType.PLATFORM_CHANGED);
                    assertThat(n.getTrip()).isSameAs(trip);
                    assertThat(n.getMessage()).contains("andén A3").contains("(antes: B1)");
                });
        verify(notificationSender, times(2)).send(any(), anyString(), anyString());
    }

    @Test
    void shouldNotifyPlatformChanged_WithoutPreviousPlatform_OmitPrevious() {
        // Given
        when(ticketRepository.findByTripIdAndStatus(5L, Ticket.TicketStatus.SOLD))
                .thenReturn(List.of(ticket(1L, ana, 1)));

        // When
        notificationService.notifyPlatformChanged(trip, null);

        // Then
        assertThat(savedNotification().getMessage()).contains("andén A3").doesNotContain("antes");
    }

    @Test
    void shouldNotifyPlatformChanged_WithoutPassengers_SendNothing() {
        // Given
        when(ticketRepository.findByTripIdAndStatus(5L, Ticket.TicketStatus.SOLD)).thenReturn(List.of());

        // When
        notificationService.notifyPlatformChanged(trip, "B1");

        // Then
        verifyNoInteractions(notificationSender, notificationRepository);
    }

    @Test
    void shouldNotifyPassengers_WhenTicketQueryFails_NotPropagate() {
        // Given
        when(ticketRepository.findByTripIdAndStatus(5L, Ticket.TicketStatus.SOLD))
                .thenThrow(new IllegalStateException("BD"));

        // When/Then: no lanza excepción
        notificationService.notifyTripCancelled(trip);
        verifyNoInteractions(notificationSender, notificationRepository);
    }

    @Test
    void shouldNotifyPassengers_SkipTicketsWithoutPassengerAndPassengersWithoutPhone() {
        // Given
        luis.setPhone(null);
        Ticket orphan = ticket(9L, null, 9);
        when(ticketRepository.findByTripIdAndStatus(5L, Ticket.TicketStatus.SOLD))
                .thenReturn(List.of(orphan, ticket(1L, ana, 1), ticket(2L, luis, 2)));

        // When
        notificationService.notifyArrivalSoon(trip);

        // Then: solo Ana recibe el aviso
        Notification saved = savedNotification();
        assertThat(saved.getUser()).isSameAs(ana);
    }

    @Test
    void shouldNotifyArrivalSoon_IncludeEtaAndDropOffStop() {
        // Given
        when(ticketRepository.findByTripIdAndStatus(5L, Ticket.TicketStatus.SOLD))
                .thenReturn(List.of(ticket(1L, ana, 1)));

        // When
        notificationService.notifyArrivalSoon(trip);

        // Then
        Notification saved = savedNotification();
        assertThat(saved.getType()).isEqualTo(Notification.NotificationType.ARRIVAL_SOON);
        assertThat(saved.getMessage()).contains("15/01/2030 11:00").contains("Terminal Tunja");
    }

    @Test
    void shouldNotifyTripCancelled_MentionFullRefund() {
        // Given
        when(ticketRepository.findByTripIdAndStatus(5L, Ticket.TicketStatus.SOLD))
                .thenReturn(List.of(ticket(1L, ana, 1)));

        // When
        notificationService.notifyTripCancelled(trip);

        // Then
        Notification saved = savedNotification();
        assertThat(saved.getType()).isEqualTo(Notification.NotificationType.TRIP_CANCELLED);
        assertThat(saved.getMessage()).contains("cancelado").contains("100%").contains("#1");
    }

    @Test
    void shouldNotifyTripRescheduled_IncludeNewTimes() {
        // Given
        trip.setArrivalEta(null);
        when(ticketRepository.findByTripIdAndStatus(5L, Ticket.TicketStatus.SOLD))
                .thenReturn(List.of(ticket(1L, ana, 1)));

        // When
        notificationService.notifyTripRescheduled(trip);

        // Then
        Notification saved = savedNotification();
        assertThat(saved.getType()).isEqualTo(Notification.NotificationType.TRIP_RESCHEDULED);
        assertThat(saved.getMessage()).contains("reprogramado").contains("15/01/2030 08:30").contains("estimada -");
    }

    // ---------- Consultas ----------

    @Test
    void shouldGetMyNotifications_ReturnAuthenticatedUserNotifications() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "ana@test.com", null, List.of(new SimpleGrantedAuthority("ROLE_PASSENGER"))));
        Notification notification = Notification.builder()
                .id(50L).user(ana).trip(trip).channel(Notification.Channel.WHATSAPP)
                .type(Notification.NotificationType.TICKET_PURCHASED).recipient("3001234567").message("hola")
                .build();
        when(userRepository.findByEmail("ana@test.com")).thenReturn(Optional.of(ana));
        when(notificationRepository.findByUserIdOrderByCreatedAtDescIdDesc(1L)).thenReturn(List.of(notification));

        // When
        List<NotificationResponse> result = notificationService.getMyNotifications();

        // Then
        assertThat(result).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(50L);
            assertThat(r.userId()).isEqualTo(1L);
            assertThat(r.tripId()).isEqualTo(5L);
            assertThat(r.ticketId()).isNull();
            assertThat(r.status()).isEqualTo(Notification.NotificationStatus.SENT);
        });
    }

    @Test
    void shouldGetMyNotifications_WithoutAuthentication_ThrowUnauthorized() {
        // When/Then
        assertThatThrownBy(() -> notificationService.getMyNotifications())
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(userRepository, notificationRepository);
    }

    @Test
    void shouldGetMyNotifications_WithUnknownUser_ThrowNotFound() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "nadie@test.com", null, List.of()));
        when(userRepository.findByEmail("nadie@test.com")).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> notificationService.getMyNotifications())
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void shouldGetNotifications_WithoutTrip_ReturnAll() {
        // Given
        when(notificationRepository.findAllByOrderByCreatedAtDescIdDesc()).thenReturn(List.of());

        // When
        List<NotificationResponse> result = notificationService.getNotifications(null);

        // Then
        assertThat(result).isEmpty();
        verify(notificationRepository, never()).findByTripIdOrderByCreatedAtDescIdDesc(any());
    }

    @Test
    void shouldGetNotifications_WithTrip_FilterByTrip() {
        // Given
        Notification notification = Notification.builder().id(1L).user(ana).trip(trip)
                .channel(Notification.Channel.SMS).type(Notification.NotificationType.ARRIVAL_SOON)
                .recipient("1").message("m").build();
        when(notificationRepository.findByTripIdOrderByCreatedAtDescIdDesc(5L)).thenReturn(List.of(notification));

        // When
        List<NotificationResponse> result = notificationService.getNotifications(5L);

        // Then
        assertThat(result).extracting(NotificationResponse::tripId).containsExactly(5L);
        verify(notificationRepository, never()).findAllByOrderByCreatedAtDescIdDesc();
    }
}
