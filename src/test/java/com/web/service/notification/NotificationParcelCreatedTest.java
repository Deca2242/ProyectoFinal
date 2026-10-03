package com.web.service.notification;

import com.web.dto.notification.mapper.NotificationMapper;
import com.web.entity.Notification;
import com.web.entity.Parcel;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.repository.NotificationRepository;
import com.web.repository.TicketRepository;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Aviso simulado al registrar una encomienda: OTP al destinatario y código de rastreo al remitente
@ExtendWith(MockitoExtension.class)
class NotificationParcelCreatedTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationDelivery notificationDelivery;
    @Mock
    private NotificationMapper notificationMapper;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private Parcel parcel;

    @BeforeEach
    void setUp() {
        Route route = Route.builder().id(1L).origin("Santa Marta").destination("Barranquilla").build();
        Trip trip = Trip.builder().id(5L).route(route).build();
        parcel = Parcel.builder()
                .id(9L)
                .code("PCL-1")
                .trip(trip)
                .senderName("Remitente").senderPhone("3001112233")
                .receiverName("Destinatario").receiverPhone("6015551234")
                .fromStop(Stop.builder().id(1L).name("Santa Marta").build())
                .toStop(Stop.builder().id(2L).name("Barranquilla").build())
                .deliveryOtp("hash")
                .build();
    }

    @Test
    void shouldNotifyParcelCreated_SendOtpToReceiverAndCodeToSender() {
        // Given: ninguno de los dos teléfonos es de un usuario registrado
        when(userRepository.findFirstByPhone(anyString())).thenReturn(Optional.empty());

        when(notificationDelivery.sendOnly(any(), anyString(), anyString())).thenReturn(true);

        // When
        notificationService.notifyParcelCreated(parcel, "123456");

        // Then: el móvil (3...) va por WhatsApp, el fijo por SMS; solo el destinatario recibe el OTP
        verify(notificationDelivery).sendOnly(eq(Notification.Channel.SMS), eq("6015551234"), contains("123456"));
        ArgumentCaptor<String> senderMessage = ArgumentCaptor.forClass(String.class);
        verify(notificationDelivery).sendOnly(eq(Notification.Channel.WHATSAPP), eq("3001112233"), senderMessage.capture());
        assertThat(senderMessage.getValue()).contains("PCL-1").doesNotContain("123456");
        // Sin usuario no queda registro (la notificación exige user_id)
        verify(notificationDelivery, never()).record(any());
        verifyNoInteractions(notificationRepository);
    }

    @Test
    void shouldNotifyParcelCreated_LogForRegisteredUserWithoutPlainOtp() {
        // Given: el destinatario es un usuario registrado
        User receiver = User.builder().id(3L).phone("6015551234").build();
        when(userRepository.findFirstByPhone("6015551234")).thenReturn(Optional.of(receiver));
        when(userRepository.findFirstByPhone("3001112233")).thenReturn(Optional.empty());
        when(notificationDelivery.sendOnly(any(), anyString(), anyString())).thenReturn(true);

        // When
        notificationService.notifyParcelCreated(parcel, "123456");

        // Then: el registro guarda el mensaje con el OTP enmascarado
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationDelivery).record(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getUser()).isSameAs(receiver);
        assertThat(saved.getType()).isEqualTo(Notification.NotificationType.PARCEL_CREATED);
        assertThat(saved.getRecipient()).isEqualTo("6015551234");
        assertThat(saved.getTrip()).isSameAs(parcel.getTrip());
        assertThat(saved.getStatus()).isEqualTo(Notification.NotificationStatus.SENT);
        assertThat(saved.getMessage()).contains("PCL-1").contains("******").doesNotContain("123456");
    }

    @Test
    void shouldNotifyParcelCreated_WhenSenderFails_NotPropagate() {
        // Given: el envío falla y el destinatario es usuario registrado
        when(notificationDelivery.sendOnly(any(), anyString(), anyString())).thenReturn(false);
        User receiver = User.builder().id(3L).phone("6015551234").build();
        when(userRepository.findFirstByPhone("6015551234")).thenReturn(Optional.of(receiver));
        when(userRepository.findFirstByPhone("3001112233")).thenReturn(Optional.empty());

        // When/Then: un fallo del envío nunca hace fallar el registro de la encomienda, y queda como FAILED
        assertThatCode(() -> notificationService.notifyParcelCreated(parcel, "123456")).doesNotThrowAnyException();
        verify(notificationDelivery, times(2)).sendOnly(any(), anyString(), anyString());
        verify(notificationDelivery).record(argThat(n -> n.getStatus() == Notification.NotificationStatus.FAILED));
    }
}
