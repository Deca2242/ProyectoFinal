package com.web.service.baggage;

import com.web.dto.baggage.BaggageCreateRequest;
import com.web.dto.baggage.BaggageResponse;
import com.web.dto.baggage.mapper.BaggageMapper;
import com.web.entity.Baggage;
import com.web.entity.Ticket;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.BaggageRepository;
import com.web.repository.TicketRepository;
import com.web.service.admin.ConfigService;
import com.web.util.QrCodeGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.*;

// Equipaje: máximo absoluto (400), cargo por exceso con la tarifa de Config, maletero y registro posterior a la compra
@ExtendWith(MockitoExtension.class)
class BaggageServiceImplTest {

    @Mock
    private BaggageRepository baggageRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private ConfigService configService;
    @Mock
    private QrCodeGenerator qrCodeGenerator;
    @Mock
    private BaggageMapper baggageMapper;

    @InjectMocks
    private BaggageServiceImpl baggageService;

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = Ticket.builder()
                .id(10L)
                .status(Ticket.TicketStatus.SOLD)
                .passenger(User.builder().id(1L).email("pax@test.com").build())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void stubLimits() {
        when(configService.getBaggageWeightMax()).thenReturn(50.0);
        when(configService.getBaggageWeightLimit()).thenReturn(23.0);
    }

    // ==================== registerForTicket ====================

    @Test
    void shouldRegisterForTicket_WithoutRequest_DoNothing() {
        // When
        Baggage result = baggageService.registerForTicket(ticket, null);

        // Then
        assertThat(result).isNull();
        assertThat(ticket.getBaggage()).isNull();
        verifyNoInteractions(baggageRepository, configService, qrCodeGenerator);
    }

    @Test
    void shouldRegisterForTicket_UnderLimit_SaveWithoutExcessFeeInMainCompartment() {
        // Given
        stubLimits();
        when(qrCodeGenerator.generateBaggageTag()).thenReturn("BAG-001");
        when(baggageRepository.save(any(Baggage.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        Baggage saved = baggageService.registerForTicket(ticket, new BaggageCreateRequest(BigDecimal.valueOf(20), null));

        // Then
        assertThat(saved.getTicket()).isSameAs(ticket);
        assertThat(saved.getTagCode()).isEqualTo("BAG-001");
        assertThat(saved.getWeightKg()).isEqualByComparingTo("20");
        assertThat(saved.getExcessFee()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getCompartment()).isEqualTo("MAIN");
        assertThat(ticket.getBaggage()).isSameAs(saved);
        verify(configService, never()).getExcessFeePerKg();
    }

    @Test
    void shouldRegisterForTicket_ExactlyAtLimit_NotChargeExcess() {
        // Given
        stubLimits();
        when(baggageRepository.save(any(Baggage.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        Baggage saved = baggageService.registerForTicket(ticket, new BaggageCreateRequest(BigDecimal.valueOf(23), null));

        // Then
        assertThat(saved.getExcessFee()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(configService, never()).getExcessFeePerKg();
    }

    @ParameterizedTest
    @CsvSource({"30, 35000.00", "25.5, 12500.00", "23.01, 50.00", "50, 135000.00"})
    void shouldRegisterForTicket_OverLimit_ChargeExcessPerKg(BigDecimal weightKg, BigDecimal expectedFee) {
        // Given: límite 23 kg, máximo 50 kg y 5000 por kg de exceso
        stubLimits();
        when(configService.getExcessFeePerKg()).thenReturn(BigDecimal.valueOf(5000));
        when(baggageRepository.save(any(Baggage.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        Baggage saved = baggageService.registerForTicket(ticket, new BaggageCreateRequest(weightKg, null));

        // Then
        assertThat(saved.getExcessFee()).isEqualByComparingTo(expectedFee);
        assertThat(saved.getExcessFee().scale()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"50.01", "60", "999.99"})
    void shouldRegisterForTicket_OverAbsoluteMax_ThrowBaggageWeightExceeded(BigDecimal weightKg) {
        // Given
        when(configService.getBaggageWeightMax()).thenReturn(50.0);

        // When/Then: exceso de equipaje → 400 (documento §10)
        assertThatThrownBy(() -> baggageService.registerForTicket(ticket, new BaggageCreateRequest(weightKg, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("50.0 kg")
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("BAGGAGE_WEIGHT_EXCEEDED");
                });
        verifyNoInteractions(baggageRepository, qrCodeGenerator);
        assertThat(ticket.getBaggage()).isNull();
    }

    @ParameterizedTest
    @CsvSource(value = {"b, B", "' a ', A", "MAIN, MAIN", "'', MAIN", "NULL, MAIN"}, nullValues = "NULL")
    void shouldRegisterForTicket_NormalizeCompartment(String requested, String expected) {
        // Given
        stubLimits();
        when(baggageRepository.save(any(Baggage.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        Baggage saved = baggageService.registerForTicket(ticket,
                new BaggageCreateRequest(BigDecimal.TEN, null, requested));

        // Then
        assertThat(saved.getCompartment()).isEqualTo(expected);
    }

    @Test
    void shouldCalculateExcessFee_UseCurrentConfiguredRate() {
        // Given: el ADMIN cambió el límite a 10 kg y la tarifa a 1000 por kg
        when(configService.getBaggageWeightLimit()).thenReturn(10.0);
        when(configService.getExcessFeePerKg()).thenReturn(BigDecimal.valueOf(1000));

        // When/Then
        assertThat(baggageService.calculateExcessFee(new BigDecimal("12.5"))).isEqualByComparingTo("2500.00");
    }

    // ==================== addBaggage ====================

    @Test
    void shouldAddBaggage_ToSoldTicketWithoutBaggage_RegisterAndMap() {
        // Given
        BaggageResponse response = new BaggageResponse(1L, 10L, BigDecimal.TEN, BigDecimal.ZERO, "BAG-1", "A",
                LocalDateTime.now());
        stubLimits();
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(baggageRepository.findByTicketId(10L)).thenReturn(Optional.empty());
        when(qrCodeGenerator.generateBaggageTag()).thenReturn("BAG-1");
        when(baggageRepository.save(any(Baggage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(baggageMapper.toResponse(any(Baggage.class))).thenReturn(response);

        // When
        BaggageResponse result = baggageService.addBaggage(10L, new BaggageCreateRequest(BigDecimal.TEN, null, "a"));

        // Then
        assertThat(result).isSameAs(response);
        ArgumentCaptor<Baggage> captor = ArgumentCaptor.forClass(Baggage.class);
        verify(baggageRepository).save(captor.capture());
        assertThat(captor.getValue().getTicket()).isSameAs(ticket);
        assertThat(captor.getValue().getCompartment()).isEqualTo("A");
    }

    @Test
    void shouldAddBaggage_WithUnknownTicket_ThrowNotFound() {
        // Given
        when(ticketRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> baggageService.addBaggage(99L, new BaggageCreateRequest(BigDecimal.TEN, null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(baggageRepository);
    }

    @ParameterizedTest
    @EnumSource(value = Ticket.TicketStatus.class, names = {"CANCELLED", "NO_SHOW"})
    void shouldAddBaggage_ToTicketNotSold_ThrowConflict(Ticket.TicketStatus status) {
        // Given
        ticket.setStatus(status);
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));

        // When/Then
        assertThatThrownBy(() -> baggageService.addBaggage(10L, new BaggageCreateRequest(BigDecimal.TEN, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("TICKET_NOT_SOLD");
                });
        verifyNoInteractions(baggageRepository);
    }

    @Test
    void shouldAddBaggage_ToTicketWithBaggage_ThrowConflict() {
        // Given
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(baggageRepository.findByTicketId(10L)).thenReturn(Optional.of(Baggage.builder().id(1L).build()));

        // When/Then
        assertThatThrownBy(() -> baggageService.addBaggage(10L, new BaggageCreateRequest(BigDecimal.TEN, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("BAGGAGE_ALREADY_REGISTERED");
                });
        verify(baggageRepository, never()).save(any());
    }

    // ==================== getBaggage ====================

    @Test
    void shouldGetBaggage_AsClerk_ReturnIt() {
        // Given
        authenticate("clerk@test.com", "CLERK");
        Baggage baggage = Baggage.builder().id(1L).ticket(ticket).build();
        BaggageResponse response = new BaggageResponse(1L, 10L, BigDecimal.TEN, BigDecimal.ZERO, "BAG-1", "MAIN",
                LocalDateTime.now());
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(baggageRepository.findByTicketId(10L)).thenReturn(Optional.of(baggage));
        when(baggageMapper.toResponse(baggage)).thenReturn(response);

        // When/Then
        assertThat(baggageService.getBaggage(10L)).isSameAs(response);
    }

    @Test
    void shouldGetBaggage_AsOtherPassenger_ThrowForbidden() {
        // Given
        authenticate("otro@test.com", "PASSENGER");
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));

        // When/Then
        assertThatThrownBy(() -> baggageService.getBaggage(10L))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(baggageRepository);
    }

    @Test
    void shouldGetBaggage_WithoutBaggage_ThrowNotFound() {
        // Given
        authenticate("pax@test.com", "PASSENGER");
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(baggageRepository.findByTicketId(10L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> baggageService.getBaggage(10L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================== removeBaggage ====================

    @Test
    void shouldRemoveBaggage_BeforeBoarding_DeleteIt() {
        // Given
        Baggage baggage = Baggage.builder().id(1L).ticket(ticket).build();
        ticket.setBaggage(baggage);
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(baggageRepository.findByTicketId(10L)).thenReturn(Optional.of(baggage));

        // When
        baggageService.removeBaggage(10L);

        // Then
        verify(baggageRepository).delete(baggage);
        assertThat(ticket.getBaggage()).isNull();
    }

    @Test
    void shouldRemoveBaggage_AfterBoarding_ThrowConflict() {
        // Given
        ticket.setBoardedAt(LocalDateTime.now());
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(baggageRepository.findByTicketId(10L)).thenReturn(Optional.of(Baggage.builder().id(1L).build()));

        // When/Then
        assertThatThrownBy(() -> baggageService.removeBaggage(10L))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        verify(baggageRepository, never()).delete(any());
    }

    @Test
    void shouldRemoveBaggage_WithoutBaggage_ThrowNotFound() {
        // Given
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket));
        when(baggageRepository.findByTicketId(10L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> baggageService.removeBaggage(10L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(baggageRepository, never()).delete(any());
    }
}
