package com.web.dto.mapper;

import com.web.dto.catalog.FareRule.FareRuleCreateRequest;
import com.web.dto.catalog.FareRule.FareRuleResponse;
import com.web.dto.catalog.FareRule.FareRuleUpdateRequest;
import com.web.dto.catalog.FareRule.mapper.FareRuleMapper;
import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyCreateRequest;
import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyResponse;
import com.web.dto.dispatch.OverbookingPolicy.mapper.OverbookingPolicyMapper;
import com.web.dto.incident.IncidentCreateRequest;
import com.web.dto.incident.IncidentResponse;
import com.web.dto.incident.mapper.IncidentMapper;
import com.web.entity.FareRule;
import com.web.entity.Incident;
import com.web.entity.OverbookingPolicy;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.entity.User;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Mappers de tarifas, políticas de overbooking e incidentes
class AdminFeaturesMapperTest {

    private final FareRuleMapper fareRuleMapper = Mappers.getMapper(FareRuleMapper.class);
    private final OverbookingPolicyMapper policyMapper = Mappers.getMapper(OverbookingPolicyMapper.class);
    private final IncidentMapper incidentMapper = Mappers.getMapper(IncidentMapper.class);

    private final Route route = Route.builder().id(1L).name("Ruta").build();
    private final User user = User.builder().id(7L).name("Despachador").passwordHash("secreto").build();

    @Test
    void fareRuleMapper_ShouldMapSegmentData() {
        // Given
        Stop from = Stop.builder().id(10L).route(route).name("Santa Marta").order(1).build();
        Stop to = Stop.builder().id(12L).route(route).name("Barranquilla").order(3).build();
        FareRule rule = FareRule.builder().id(5L).route(route).fromStop(from).toStop(to)
                .basePrice(new BigDecimal("40000")).discounts(Map.of("STUDENT", 10))
                .dynamicPricingEnabled(true).build();

        // When
        FareRuleResponse response = fareRuleMapper.toResponse(rule);

        // Then
        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.routeId()).isEqualTo(1L);
        assertThat(response.fromStopId()).isEqualTo(10L);
        assertThat(response.fromStopName()).isEqualTo("Santa Marta");
        assertThat(response.fromStopOrder()).isEqualTo(1);
        assertThat(response.toStopId()).isEqualTo(12L);
        assertThat(response.toStopOrder()).isEqualTo(3);
        assertThat(response.discounts()).containsEntry("STUDENT", 10);
        assertThat(response.dynamicPricingEnabled()).isTrue();
        assertThat(fareRuleMapper.toResponseList(List.of(rule))).hasSize(1);
        assertThat(fareRuleMapper.toResponse(null)).isNull();
    }

    @Test
    void fareRuleMapper_ShouldCreateEntityOnlyWithPrice() {
        // Given
        FareRuleCreateRequest request = new FareRuleCreateRequest(10L, 12L, new BigDecimal("40000"),
                Map.of("STUDENT", 10), true);

        // When
        FareRule entity = fareRuleMapper.toEntity(request);

        // Then: ruta, paradas, descuentos y flag se asignan en el servicio tras validarlos
        assertThat(entity.getBasePrice()).isEqualByComparingTo("40000");
        assertThat(entity.getRoute()).isNull();
        assertThat(entity.getDiscounts()).isNull();
        assertThat(entity.getDynamicPricingEnabled()).isFalse();
    }

    @Test
    void fareRuleMapper_ShouldUpdateOnlyNonNullPrice() {
        // Given
        FareRule rule = FareRule.builder().basePrice(new BigDecimal("40000")).build();

        // When
        fareRuleMapper.updateEntityFromRequest(new FareRuleUpdateRequest(null, null, null, null, null), rule);
        BigDecimal unchanged = rule.getBasePrice();
        fareRuleMapper.updateEntityFromRequest(new FareRuleUpdateRequest(null, null, new BigDecimal("45000"), null, null), rule);

        // Then
        assertThat(unchanged).isEqualByComparingTo("40000");
        assertThat(rule.getBasePrice()).isEqualByComparingTo("45000");
    }

    @Test
    void overbookingPolicyMapper_ShouldMapBothWays() {
        // Given
        OverbookingPolicy policy = OverbookingPolicy.builder().id(3L).route(route).startHour(6).endHour(10)
                .maxPercentage(new BigDecimal("0.1000")).createdBy(user).createdAt(LocalDateTime.now()).build();

        // When
        OverbookingPolicyResponse response = policyMapper.toResponse(policy);
        OverbookingPolicy entity = policyMapper.toEntity(new OverbookingPolicyCreateRequest(0, 24, new BigDecimal("0.05")));

        // Then
        assertThat(response.routeId()).isEqualTo(1L);
        assertThat(response.createdById()).isEqualTo(7L);
        assertThat(response.createdByName()).isEqualTo("Despachador");
        assertThat(entity.getStartHour()).isZero();
        assertThat(entity.getEndHour()).isEqualTo(24);
        assertThat(entity.getRoute()).isNull();
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(policyMapper.toResponseList(List.of(policy))).hasSize(1);
    }

    @Test
    void incidentMapper_ShouldMapTypeAndReporter() {
        // Given
        Incident incident = Incident.builder().id(1L).incidentType(Incident.IncidentType.SECURITY)
                .entityType(Incident.EntityType.TICKET).entityId(4L).description("Riña").reportedBy(user).build();

        // When
        IncidentResponse response = incidentMapper.toResponse(incident);
        Incident entity = incidentMapper.toEntity(new IncidentCreateRequest(Incident.IncidentType.VEHICLE,
                Incident.EntityType.TRIP, 9L, "Motor"));

        // Then
        assertThat(response.type()).isEqualTo(Incident.IncidentType.SECURITY);
        assertThat(response.reportedById()).isEqualTo(7L);
        assertThat(response.reportedByName()).isEqualTo("Despachador");
        assertThat(entity.getIncidentType()).isEqualTo(Incident.IncidentType.VEHICLE);
        assertThat(entity.getEntityId()).isEqualTo(9L);
        assertThat(entity.getReportedBy()).isNull();
        assertThat(incidentMapper.toResponseList(List.of(incident))).hasSize(1);
    }
}
