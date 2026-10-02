package com.web.service.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.sync.BoardingSyncRequest;
import com.web.dto.sync.OfflineBoarding;
import com.web.dto.sync.OfflineTicketSale;
import com.web.dto.sync.SyncBatchResponse;
import com.web.dto.sync.SyncConflictResponse;
import com.web.dto.sync.SyncItemResult;
import com.web.dto.sync.TicketSyncRequest;
import com.web.dto.sync.mapper.SyncMapper;
import com.web.entity.SyncBatch;
import com.web.entity.SyncConflict;
import com.web.entity.Ticket;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.exception.SeatNotAvailableException;
import com.web.repository.SyncBatchRepository;
import com.web.repository.SyncConflictRepository;
import com.web.repository.TicketRepository;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Sincronización de ventas y abordajes offline: resultados por operación, auditoría del lote y conflictos
@ExtendWith(MockitoExtension.class)
class SyncServiceImplTest {

    @Mock
    private OfflineSyncProcessor processor;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SyncBatchRepository syncBatchRepository;
    @Mock
    private SyncConflictRepository syncConflictRepository;
    @Mock
    private SyncMapper syncMapper;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private SyncServiceImpl syncService;

    private User clerk;

    @BeforeEach
    void setUp() {
        clerk = User.builder().id(7L).email("clerk@test.com").role(User.Role.CLERK).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------- Utilidades ----------

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void givenAuthenticatedClerk() {
        authenticate("clerk@test.com", "CLERK");
        when(userRepository.findByEmail("clerk@test.com")).thenReturn(Optional.of(clerk));
    }

    private void givenBatchSaved() {
        when(syncBatchRepository.save(any(SyncBatch.class))).thenAnswer(inv -> {
            SyncBatch batch = inv.getArgument(0);
            batch.setId(100L);
            return batch;
        });
    }

    private OfflineTicketSale sale(String clientId, LocalDateTime soldAt) {
        return new OfflineTicketSale(clientId, 1L, 2L, 10, 1L, 2L, Ticket.PaymentMethod.CASH, null, soldAt, null);
    }

    private OfflineTicketSale sale(String clientId) {
        return sale(clientId, LocalDateTime.now().minusMinutes(30));
    }

    private SyncItemResult synced(String clientId, long ticketId) {
        return new SyncItemResult(clientId, SyncItemResult.Status.SYNCED, ticketId, "QR-" + ticketId, null, null);
    }

    private SyncBatch savedBatch() {
        ArgumentCaptor<SyncBatch> captor = ArgumentCaptor.forClass(SyncBatch.class);
        verify(syncBatchRepository).save(captor.capture());
        return captor.getValue();
    }

    // ---------- syncTickets ----------

    @Test
    void shouldSyncTickets_WithNewSales_ReturnSyncedAndAuditBatch() {
        // Given
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineTicketSale s1 = sale("c-1");
        OfflineTicketSale s2 = sale("c-2");
        when(processor.syncSale(s1)).thenReturn(synced("c-1", 1L));
        when(processor.syncSale(s2)).thenReturn(synced("c-2", 2L));

        // When
        SyncBatchResponse response = syncService.syncTickets(new TicketSyncRequest("tablet-1", List.of(s1, s2)));

        // Then
        assertThat(response.batchId()).isEqualTo(100L);
        assertThat(response.total()).isEqualTo(2);
        assertThat(response.synced()).isEqualTo(2);
        assertThat(response.duplicates()).isZero();
        assertThat(response.conflicts()).isZero();
        assertThat(response.results()).extracting(SyncItemResult::ticketId).containsExactly(1L, 2L);

        SyncBatch batch = savedBatch();
        assertThat(batch.getDeviceId()).isEqualTo("tablet-1");
        assertThat(batch.getUser()).isSameAs(clerk);
        assertThat(batch.getType()).isEqualTo(SyncBatch.SyncType.TICKETS);
        assertThat(batch.getReceivedAt()).isNotNull();
        assertThat(batch.getSynced()).isEqualTo(2);
        assertThat(batch.getConflictItems()).isEmpty();
    }

    @Test
    void shouldSyncTickets_WithAlreadySyncedSale_ReturnDuplicate() {
        // Given
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineTicketSale s1 = sale("c-1");
        when(processor.syncSale(s1)).thenReturn(
                new SyncItemResult("c-1", SyncItemResult.Status.DUPLICATE, 1L, "QR-1", null, null));

        // When
        SyncBatchResponse response = syncService.syncTickets(new TicketSyncRequest("tablet-1", List.of(s1)));

        // Then
        assertThat(response.duplicates()).isEqualTo(1);
        assertThat(response.synced()).isZero();
        assertThat(response.results().get(0).ticketId()).isEqualTo(1L);
        assertThat(savedBatch().getDuplicates()).isEqualTo(1);
    }

    @Test
    void shouldSyncTickets_WithSeatTaken_ReturnConflictAndRegisterIt() {
        // Given
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineTicketSale s1 = sale("c-1");
        when(processor.syncSale(s1)).thenThrow(new SeatNotAvailableException("El asiento 10 no está disponible"));
        when(ticketRepository.findByOfflineClientId("c-1")).thenReturn(Optional.empty());

        // When
        SyncBatchResponse response = syncService.syncTickets(new TicketSyncRequest("tablet-1", List.of(s1)));

        // Then
        SyncItemResult result = response.results().get(0);
        assertThat(result.status()).isEqualTo(SyncItemResult.Status.CONFLICT);
        assertThat(result.code()).isEqualTo("SEAT_NOT_AVAILABLE");
        assertThat(result.message()).contains("asiento 10");
        assertThat(result.ticketId()).isNull();
        assertThat(response.conflicts()).isEqualTo(1);

        SyncBatch batch = savedBatch();
        assertThat(batch.getConflictItems()).hasSize(1);
        SyncConflict conflict = batch.getConflictItems().get(0);
        assertThat(conflict.getBatch()).isSameAs(batch);
        assertThat(conflict.getOfflineClientId()).isEqualTo("c-1");
        assertThat(conflict.getCode()).isEqualTo("SEAT_NOT_AVAILABLE");
        assertThat(conflict.getPayload()).contains("\"offlineClientId\":\"c-1\"").contains("\"seatNumber\":10");
    }

    @Test
    void shouldSyncTickets_WithNotFoundTrip_ReturnConflictWithCode() {
        // Given
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineTicketSale s1 = sale("c-1");
        when(processor.syncSale(s1)).thenThrow(new ResourceNotFoundException("Viaje", 1L));
        when(ticketRepository.findByOfflineClientId("c-1")).thenReturn(Optional.empty());

        // When
        SyncBatchResponse response = syncService.syncTickets(new TicketSyncRequest("tablet-1", List.of(s1)));

        // Then
        assertThat(response.results().get(0).code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void shouldSyncTickets_WithConflictButSaleRegisteredByParallelResend_ReturnDuplicate() {
        // Given: el mismo lote se envió dos veces a la vez y el otro envío vendió la silla primero
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineTicketSale s1 = sale("c-1");
        when(processor.syncSale(s1)).thenThrow(new SeatNotAvailableException("ocupado"));
        when(ticketRepository.findByOfflineClientId("c-1"))
                .thenReturn(Optional.of(Ticket.builder().id(9L).qrCode("QR-9").build()));

        // When
        SyncBatchResponse response = syncService.syncTickets(new TicketSyncRequest("tablet-1", List.of(s1)));

        // Then
        assertThat(response.results().get(0).status()).isEqualTo(SyncItemResult.Status.DUPLICATE);
        assertThat(response.results().get(0).ticketId()).isEqualTo(9L);
        assertThat(response.conflicts()).isZero();
        assertThat(savedBatch().getConflictItems()).isEmpty();
    }

    @Test
    void shouldSyncTickets_WithUniqueViolationOnOfflineId_ReturnDuplicate() {
        // Given
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineTicketSale s1 = sale("c-1");
        when(processor.syncSale(s1)).thenThrow(new DataIntegrityViolationException("uk_offline_client_id"));
        when(ticketRepository.findByOfflineClientId("c-1"))
                .thenReturn(Optional.of(Ticket.builder().id(9L).qrCode("QR-9").build()));

        // When
        SyncBatchResponse response = syncService.syncTickets(new TicketSyncRequest("tablet-1", List.of(s1)));

        // Then
        assertThat(response.duplicates()).isEqualTo(1);
    }

    @Test
    void shouldSyncTickets_WithIntegrityViolationAndNoTicket_ReturnConflict() {
        // Given
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineTicketSale s1 = sale("c-1");
        when(processor.syncSale(s1)).thenThrow(new DataIntegrityViolationException("fk"));
        when(ticketRepository.findByOfflineClientId("c-1")).thenReturn(Optional.empty());

        // When
        SyncBatchResponse response = syncService.syncTickets(new TicketSyncRequest("tablet-1", List.of(s1)));

        // Then
        assertThat(response.results().get(0).code()).isEqualTo("DATA_INTEGRITY_VIOLATION");
    }

    @Test
    void shouldSyncTickets_WithUnexpectedError_ReturnConflictWithoutStoppingBatch() {
        // Given
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineTicketSale s1 = sale("c-1");
        OfflineTicketSale s2 = sale("c-2");
        when(processor.syncSale(s1)).thenThrow(new IllegalStateException("boom"));
        when(processor.syncSale(s2)).thenReturn(synced("c-2", 2L));

        // When
        SyncBatchResponse response = syncService.syncTickets(new TicketSyncRequest("tablet-1", List.of(s1, s2)));

        // Then
        assertThat(response.results().get(0).code()).isEqualTo("SYNC_ERROR");
        assertThat(response.results().get(0).message()).doesNotContain("boom");
        assertThat(response.synced()).isEqualTo(1);
        assertThat(response.conflicts()).isEqualTo(1);
    }

    @Test
    void shouldSyncTickets_WithSoldAtInFuture_ReturnConflictWithoutSelling() {
        // Given
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineTicketSale future = sale("c-1", LocalDateTime.now().plusMinutes(30));

        // When
        SyncBatchResponse response = syncService.syncTickets(new TicketSyncRequest("tablet-1", List.of(future)));

        // Then
        assertThat(response.results().get(0).status()).isEqualTo(SyncItemResult.Status.CONFLICT);
        assertThat(response.results().get(0).code()).isEqualTo("INVALID_SOLD_AT");
        verifyNoInteractions(processor);
        assertThat(savedBatch().getConflictItems()).extracting(SyncConflict::getCode).containsExactly("INVALID_SOLD_AT");
    }

    @Test
    void shouldSyncTickets_WithSoldAtWithinClockTolerance_ProcessSale() {
        // Given: reloj del dispositivo 3 minutos adelantado
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineTicketSale s1 = sale("c-1", LocalDateTime.now().plusMinutes(3));
        when(processor.syncSale(s1)).thenReturn(synced("c-1", 1L));

        // When
        SyncBatchResponse response = syncService.syncTickets(new TicketSyncRequest("tablet-1", List.of(s1)));

        // Then
        assertThat(response.synced()).isEqualTo(1);
    }

    @Test
    void shouldSyncTickets_WithSoldAfterDepartureFromTicketService_ReturnConflict() {
        // Given
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineTicketSale s1 = sale("c-1");
        when(processor.syncSale(s1)).thenThrow(new BusinessException("La venta offline es posterior a la salida del viaje",
                HttpStatus.BAD_REQUEST, "SOLD_AFTER_DEPARTURE"));
        when(ticketRepository.findByOfflineClientId("c-1")).thenReturn(Optional.empty());

        // When
        SyncBatchResponse response = syncService.syncTickets(new TicketSyncRequest("tablet-1", List.of(s1)));

        // Then
        assertThat(response.results().get(0).code()).isEqualTo("SOLD_AFTER_DEPARTURE");
    }

    @Test
    void shouldSyncTickets_WithMixedBatch_CountEachStatusInOrder() {
        // Given
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineTicketSale ok = sale("c-ok");
        OfflineTicketSale dup = sale("c-dup");
        OfflineTicketSale taken = sale("c-taken");
        OfflineTicketSale future = sale("c-future", LocalDateTime.now().plusHours(1));
        when(processor.syncSale(ok)).thenReturn(synced("c-ok", 1L));
        when(processor.syncSale(dup)).thenReturn(
                new SyncItemResult("c-dup", SyncItemResult.Status.DUPLICATE, 2L, "QR-2", null, null));
        when(processor.syncSale(taken)).thenThrow(new SeatNotAvailableException("ocupado"));
        when(ticketRepository.findByOfflineClientId("c-taken")).thenReturn(Optional.empty());

        // When
        SyncBatchResponse response = syncService.syncTickets(
                new TicketSyncRequest("tablet-1", List.of(ok, dup, taken, future)));

        // Then
        assertThat(response.total()).isEqualTo(4);
        assertThat(response.synced()).isEqualTo(1);
        assertThat(response.duplicates()).isEqualTo(1);
        assertThat(response.conflicts()).isEqualTo(2);
        assertThat(response.results()).extracting(SyncItemResult::status).containsExactly(
                SyncItemResult.Status.SYNCED, SyncItemResult.Status.DUPLICATE,
                SyncItemResult.Status.CONFLICT, SyncItemResult.Status.CONFLICT);

        SyncBatch batch = savedBatch();
        assertThat(batch.getTotal()).isEqualTo(4);
        assertThat(batch.getConflicts()).isEqualTo(2);
        assertThat(batch.getConflictItems()).extracting(SyncConflict::getOfflineClientId)
                .containsExactly("c-taken", "c-future");
    }

    @Test
    void shouldSyncTickets_WithMoreThanMaxSales_ThrowBatchTooLarge() {
        // Given
        List<OfflineTicketSale> sales = new ArrayList<>();
        for (int i = 0; i <= TicketSyncRequest.MAX_BATCH_SIZE; i++) {
            sales.add(sale("c-" + i));
        }

        // When/Then
        assertThatThrownBy(() -> syncService.syncTickets(new TicketSyncRequest("tablet-1", sales)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("SYNC_BATCH_TOO_LARGE");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(processor, syncBatchRepository);
    }

    @Test
    void shouldSyncTickets_WithExactlyMaxSales_ProcessAll() {
        // Given
        givenAuthenticatedClerk();
        givenBatchSaved();
        List<OfflineTicketSale> sales = new ArrayList<>();
        for (int i = 0; i < TicketSyncRequest.MAX_BATCH_SIZE; i++) {
            sales.add(sale("c-" + i));
        }
        when(processor.syncSale(any(OfflineTicketSale.class)))
                .thenAnswer(inv -> synced(((OfflineTicketSale) inv.getArgument(0)).offlineClientId(), 1L));

        // When
        SyncBatchResponse response = syncService.syncTickets(new TicketSyncRequest("tablet-1", sales));

        // Then
        assertThat(response.synced()).isEqualTo(TicketSyncRequest.MAX_BATCH_SIZE);
        verify(processor, times(TicketSyncRequest.MAX_BATCH_SIZE)).syncSale(any(OfflineTicketSale.class));
    }

    @Test
    void shouldSyncTickets_WithUnknownAuthenticatedUser_ThrowUnauthorized() {
        // Given
        authenticate("ghost@test.com", "CLERK");
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> syncService.syncTickets(new TicketSyncRequest("tablet-1", List.of(sale("c-1")))))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("USER_NOT_FOUND");
        verifyNoInteractions(processor);
    }

    // ---------- syncBoardings ----------

    @Test
    void shouldSyncBoardings_WithNewAndAlreadyBoarded_ReturnSyncedAndDuplicate() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        User driver = User.builder().id(8L).email("driver@test.com").build();
        when(userRepository.findByEmail("driver@test.com")).thenReturn(Optional.of(driver));
        givenBatchSaved();
        OfflineBoarding b1 = new OfflineBoarding("QR-1", LocalDateTime.now().minusMinutes(10));
        OfflineBoarding b2 = new OfflineBoarding("QR-2", LocalDateTime.now().minusMinutes(9));
        when(processor.syncBoarding(b1)).thenReturn(
                new SyncItemResult(null, SyncItemResult.Status.SYNCED, 1L, "QR-1", null, null));
        when(processor.syncBoarding(b2)).thenReturn(
                new SyncItemResult(null, SyncItemResult.Status.DUPLICATE, 2L, "QR-2", null, null));

        // When
        SyncBatchResponse response = syncService.syncBoardings(new BoardingSyncRequest("phone-1", List.of(b1, b2)));

        // Then
        assertThat(response.synced()).isEqualTo(1);
        assertThat(response.duplicates()).isEqualTo(1);
        SyncBatch batch = savedBatch();
        assertThat(batch.getType()).isEqualTo(SyncBatch.SyncType.BOARDINGS);
        assertThat(batch.getUser()).isSameAs(driver);
    }

    @Test
    void shouldSyncBoardings_WithAlreadyBoardedException_ReturnDuplicate() {
        // Given: otro envío registró el abordaje entre la consulta y la escritura
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineBoarding b1 = new OfflineBoarding("QR-1", LocalDateTime.now().minusMinutes(10));
        when(processor.syncBoarding(b1)).thenThrow(new BusinessException("El pasajero ya abordó con este ticket",
                HttpStatus.CONFLICT, "TICKET_ALREADY_BOARDED"));
        when(ticketRepository.findByQrCode("QR-1")).thenReturn(Optional.of(Ticket.builder().id(4L).build()));

        // When
        SyncBatchResponse response = syncService.syncBoardings(new BoardingSyncRequest("phone-1", List.of(b1)));

        // Then
        SyncItemResult result = response.results().get(0);
        assertThat(result.status()).isEqualTo(SyncItemResult.Status.DUPLICATE);
        assertThat(result.ticketId()).isEqualTo(4L);
        assertThat(result.qrCode()).isEqualTo("QR-1");
    }

    @Test
    void shouldSyncBoardings_WithBoardingClosed_ReturnConflictAndRegisterIt() {
        // Given
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineBoarding b1 = new OfflineBoarding("QR-1", LocalDateTime.now().minusMinutes(10));
        when(processor.syncBoarding(b1)).thenThrow(new BusinessException("El abordaje no está abierto",
                HttpStatus.BAD_REQUEST, "BOARDING_NOT_OPEN"));

        // When
        SyncBatchResponse response = syncService.syncBoardings(new BoardingSyncRequest("phone-1", List.of(b1)));

        // Then
        SyncItemResult result = response.results().get(0);
        assertThat(result.status()).isEqualTo(SyncItemResult.Status.CONFLICT);
        assertThat(result.code()).isEqualTo("BOARDING_NOT_OPEN");
        assertThat(result.qrCode()).isEqualTo("QR-1");
        SyncConflict conflict = savedBatch().getConflictItems().get(0);
        assertThat(conflict.getOfflineClientId()).isEqualTo("QR-1");
        assertThat(conflict.getPayload()).contains("\"qrCode\":\"QR-1\"");
    }

    @Test
    void shouldSyncBoardings_WithUnexpectedError_ReturnSyncError() {
        // Given
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineBoarding b1 = new OfflineBoarding("QR-1", LocalDateTime.now().minusMinutes(10));
        when(processor.syncBoarding(b1)).thenThrow(new IllegalStateException("boom"));

        // When
        SyncBatchResponse response = syncService.syncBoardings(new BoardingSyncRequest("phone-1", List.of(b1)));

        // Then
        assertThat(response.results().get(0).code()).isEqualTo("SYNC_ERROR");
    }

    @Test
    void shouldSyncBoardings_WithMoreThanMaxBoardings_ThrowBatchTooLarge() {
        // Given
        List<OfflineBoarding> boardings = new ArrayList<>();
        for (int i = 0; i <= TicketSyncRequest.MAX_BATCH_SIZE; i++) {
            boardings.add(new OfflineBoarding("QR-" + i, LocalDateTime.now()));
        }

        // When/Then
        assertThatThrownBy(() -> syncService.syncBoardings(new BoardingSyncRequest("phone-1", boardings)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SYNC_BATCH_TOO_LARGE");
        verifyNoInteractions(processor);
    }

    // ---------- getConflicts ----------

    @Test
    void shouldGetConflicts_AsClerk_ReturnOnlyOwnConflicts() {
        // Given
        givenAuthenticatedClerk();
        List<SyncConflict> conflicts = List.of(SyncConflict.builder().id(1L).build());
        List<SyncConflictResponse> mapped = List.of(mock(SyncConflictResponse.class));
        when(syncConflictRepository.findByBatchUserIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(conflicts);
        when(syncMapper.toConflictResponseList(conflicts)).thenReturn(mapped);

        // When
        List<SyncConflictResponse> result = syncService.getConflicts(null);

        // Then
        assertThat(result).isSameAs(mapped);
        verify(syncConflictRepository, never()).findAllByOrderByCreatedAtDescIdDesc();
    }

    @Test
    void shouldGetConflicts_AsClerkWithDevice_FilterByUserAndDevice() {
        // Given
        givenAuthenticatedClerk();
        when(syncConflictRepository.findByBatchUserIdAndBatchDeviceIdOrderByCreatedAtDescIdDesc(7L, "tablet-1"))
                .thenReturn(List.of());
        when(syncMapper.toConflictResponseList(List.of())).thenReturn(List.of());

        // When
        List<SyncConflictResponse> result = syncService.getConflicts("tablet-1");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void shouldGetConflicts_AsAdmin_ReturnAllConflicts() {
        // Given
        authenticate("admin@test.com", "ADMIN");
        when(syncConflictRepository.findAllByOrderByCreatedAtDescIdDesc()).thenReturn(List.of());
        when(syncMapper.toConflictResponseList(List.of())).thenReturn(List.of());

        // When
        syncService.getConflicts("  ");

        // Then
        verify(syncConflictRepository).findAllByOrderByCreatedAtDescIdDesc();
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldGetConflicts_AsAdminWithDevice_FilterByDevice() {
        // Given
        authenticate("admin@test.com", "ADMIN");
        when(syncConflictRepository.findByBatchDeviceIdOrderByCreatedAtDescIdDesc("tablet-1")).thenReturn(List.of());
        when(syncMapper.toConflictResponseList(List.of())).thenReturn(List.of());

        // When
        syncService.getConflicts("tablet-1");

        // Then
        verify(syncConflictRepository).findByBatchDeviceIdOrderByCreatedAtDescIdDesc("tablet-1");
    }

    // ---------- Payload ----------

    @Test
    void shouldSyncTickets_WithBaggage_KeepWeightInConflictPayload() {
        // Given
        givenAuthenticatedClerk();
        givenBatchSaved();
        OfflineTicketSale s1 = new OfflineTicketSale("c-1", 1L, 2L, 10, 1L, 2L, Ticket.PaymentMethod.CARD,
                "STUDENT", LocalDateTime.now().minusMinutes(5), new BigDecimal("25.5"));
        when(processor.syncSale(s1)).thenThrow(new SeatNotAvailableException("ocupado"));
        when(ticketRepository.findByOfflineClientId("c-1")).thenReturn(Optional.empty());

        // When
        syncService.syncTickets(new TicketSyncRequest("tablet-1", List.of(s1)));

        // Then
        assertThat(savedBatch().getConflictItems().get(0).getPayload())
                .contains("\"baggageWeightKg\":25.5")
                .contains("\"passengerType\":\"STUDENT\"");
    }
}
