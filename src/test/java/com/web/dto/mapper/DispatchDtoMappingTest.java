package com.web.dto.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.catalog.Bus.BusCreateRequest;
import com.web.dto.catalog.Bus.BusResponse;
import com.web.dto.catalog.Bus.BusUpdateRequest;
import com.web.dto.catalog.Bus.mapper.BusMapper;
import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentResponse;
import com.web.dto.dispatch.Assignment.AssignmentUpdateRequest;
import com.web.dto.dispatch.Assignment.mapper.AssignmentMapper;
import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyCreateRequest;
import com.web.dto.notification.mapper.NotificationMapper;
import com.web.dto.sync.mapper.SyncMapper;
import com.web.entity.*;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

// DTOs y mappers del despacho: vigencia del checklist del bus, bus opcional en la asignación,
// lectura de notificaciones y resolución de conflictos offline
class DispatchDtoMappingTest {

    private final BusMapper busMapper = Mappers.getMapper(BusMapper.class);
    private final AssignmentMapper assignmentMapper = Mappers.getMapper(AssignmentMapper.class);
    private final NotificationMapper notificationMapper = Mappers.getMapper(NotificationMapper.class);
    private final SyncMapper syncMapper = Mappers.getMapper(SyncMapper.class);
    private final ObjectMapper om = new ObjectMapper().findAndRegisterModules();

    private final LocalDate tripDate = LocalDate.of(2030, 1, 15);

    @Test
    void busMapper_ShouldMapChecklistExpirationDates() {
        // Given
        BusCreateRequest request = new BusCreateRequest("ABC123", 40, new HashMap<>(),
                tripDate.plusMonths(6), tripDate.plusMonths(3));

        // When
        Bus bus = busMapper.toEntity(request);
        BusResponse response = busMapper.toResponse(bus);

        // Then
        assertThat(bus.getSoatExpiresAt()).isEqualTo(tripDate.plusMonths(6));
        assertThat(bus.getTechnicalReviewExpiresAt()).isEqualTo(tripDate.plusMonths(3));
        assertThat(response.soatExpiresAt()).isEqualTo(tripDate.plusMonths(6));
        assertThat(response.technicalReviewExpiresAt()).isEqualTo(tripDate.plusMonths(3));
    }

    @Test
    void busMapper_UpdateWithoutDates_ShouldKeepCurrentDates() {
        // Given
        Bus bus = Bus.builder().id(1L).plate("ABC123").capacity(40).soatExpiresAt(tripDate).build();

        // When
        busMapper.updateEntityFromRequest(new BusUpdateRequest(45, null, null), bus);
        busMapper.updateEntityFromRequest(new BusUpdateRequest(null, null, null, null, tripDate.plusYears(1)), bus);

        // Then
        assertThat(bus.getCapacity()).isEqualTo(45);
        assertThat(bus.getSoatExpiresAt()).isEqualTo(tripDate);
        assertThat(bus.getTechnicalReviewExpiresAt()).isEqualTo(tripDate.plusYears(1));
    }

    @Test
    void busRequests_ShouldDeserializeWithAndWithoutChecklistDates() throws Exception {
        // When
        BusCreateRequest withDates = om.readValue(
                "{\"plate\":\"ABC123\",\"capacity\":40,\"soatExpiresAt\":\"2030-07-15\"}", BusCreateRequest.class);
        BusCreateRequest withoutDates = om.readValue("{\"plate\":\"ABC123\",\"capacity\":40}", BusCreateRequest.class);

        // Then
        assertThat(withDates.soatExpiresAt()).isEqualTo(LocalDate.of(2030, 7, 15));
        assertThat(withDates.technicalReviewExpiresAt()).isNull();
        assertThat(withoutDates).isEqualTo(new BusCreateRequest("ABC123", 40, null));
    }

    @Test
    void assignmentRequests_ShouldDeserializeOptionalDispatcherAndBus() throws Exception {
        // When
        AssignmentCreateRequest create = om.readValue("{\"tripId\":1,\"driverId\":3,\"busId\":12}",
                AssignmentCreateRequest.class);
        AssignmentUpdateRequest update = om.readValue("{\"checklistOk\":true}", AssignmentUpdateRequest.class);

        // Then
        assertThat(create).isEqualTo(new AssignmentCreateRequest(1L, 3L, null, 12L));
        assertThat(update).isEqualTo(new AssignmentUpdateRequest(null, true, null, null));
        assertThat(update.busId()).isNull();
    }

    @Test
    void assignmentMapper_ShouldComputeChecklistValidityOnTripDate() {
        // Given: SOAT vencido el día antes del viaje; revisión sin fecha (manda el booleano)
        Bus bus = Bus.builder().id(3L).plate("XYZ987").soatExpiresAt(tripDate.minusDays(1)).build();
        Trip trip = Trip.builder().id(1L).bus(bus).tripDate(tripDate).departureTime(tripDate.atTime(8, 0)).build();
        Assignment assignment = Assignment.builder().id(5L).trip(trip).soatValid(true).revisionValid(true).build();

        // When
        AssignmentResponse response = assignmentMapper.toResponse(assignment);

        // Then
        assertThat(response.soatExpiresAt()).isEqualTo(tripDate.minusDays(1));
        assertThat(response.technicalReviewExpiresAt()).isNull();
        assertThat(response.soatValidOnTripDate()).isFalse();
        assertThat(response.reviewValidOnTripDate()).isTrue();
        assertThat(assignment.expiredDocuments()).containsExactly("SOAT vencido el " + tripDate.minusDays(1));
    }

    @Test
    void overbookingPolicyRequest_ShouldNotSerializeRangeCheck() throws Exception {
        // When
        String json = om.writeValueAsString(new OverbookingPolicyCreateRequest(6, 10, new BigDecimal("0.05")));

        // Then
        assertThat(json).doesNotContain("hourRange");
        assertThat(new OverbookingPolicyCreateRequest(10, 6, BigDecimal.ONE).isHourRange()).isFalse();
        assertThat(new OverbookingPolicyCreateRequest(null, 6, BigDecimal.ONE).isHourRange()).isTrue();
    }

    @Test
    void notificationAndConflictMappers_ShouldMapReadAndResolution() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder().id(7L).build();
        Notification notification = Notification.builder().id(1L).user(user).readAt(now).build();
        SyncBatch batch = SyncBatch.builder().id(2L).deviceId("tablet-1").type(SyncBatch.SyncType.BOARDINGS).build();
        SyncConflict conflict = SyncConflict.builder().id(3L).batch(batch).resolvedAt(now).resolvedBy(user).build();

        // When/Then
        assertThat(notificationMapper.toResponse(notification).readAt()).isEqualTo(now);
        assertThat(syncMapper.toConflictResponse(conflict).resolvedAt()).isEqualTo(now);
        assertThat(syncMapper.toConflictResponse(conflict).resolvedById()).isEqualTo(7L);
    }
}
