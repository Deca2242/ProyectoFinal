package com.web.service.catalog;

import com.web.dto.catalog.FareRule.FareRuleCreateRequest;
import com.web.dto.catalog.FareRule.FareRuleResponse;
import com.web.dto.catalog.FareRule.FareRuleUpdateRequest;
import com.web.dto.catalog.FareRule.mapper.FareRuleMapper;
import com.web.entity.FareRule;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.FareRuleRepository;
import com.web.repository.RouteRepository;
import com.web.repository.StopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// CRUD de tarifas por tramo: paradas de la ruta y en orden, tramo único y descuentos válidos
@ExtendWith(MockitoExtension.class)
class FareRuleServiceImplTest {

    @Mock
    private FareRuleRepository fareRuleRepository;
    @Mock
    private RouteRepository routeRepository;
    @Mock
    private StopRepository stopRepository;
    @Mock
    private FareRuleMapper fareRuleMapper;

    @InjectMocks
    private FareRuleServiceImpl fareRuleService;

    private Route route;
    private Stop stopA;
    private Stop stopB;
    private Stop stopC;
    private FareRuleResponse response;

    @BeforeEach
    void setUp() {
        route = Route.builder().id(1L).name("Santa Marta - Barranquilla").build();
        stopA = Stop.builder().id(10L).route(route).name("Santa Marta").order(1).build();
        stopB = Stop.builder().id(11L).route(route).name("Ciénaga").order(2).build();
        stopC = Stop.builder().id(12L).route(route).name("Barranquilla").order(3).build();
        response = new FareRuleResponse(1L, 1L, 10L, "Santa Marta", 1, 12L, "Barranquilla", 3,
                new BigDecimal("40000"), Map.of(), false);
    }

    private FareRuleCreateRequest createRequest(Map<String, Integer> discounts, Boolean dynamic) {
        return new FareRuleCreateRequest(10L, 12L, new BigDecimal("40000"), discounts, dynamic);
    }

    private FareRule existingRule() {
        return FareRule.builder().id(1L).route(route).fromStop(stopA).toStop(stopC)
                .basePrice(new BigDecimal("40000")).dynamicPricingEnabled(false).build();
    }

    private void givenRouteAndStops() {
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(stopRepository.findById(10L)).thenReturn(Optional.of(stopA));
        when(stopRepository.findById(12L)).thenReturn(Optional.of(stopC));
    }

    // ---------- Consulta ----------

    @Test
    void shouldGetFareRulesByRoute_ReturnRulesOrderedBySegment() {
        // Given
        FareRule bc = FareRule.builder().id(2L).route(route).fromStop(stopB).toStop(stopC).build();
        FareRule ac = FareRule.builder().id(3L).route(route).fromStop(stopA).toStop(stopC).build();
        FareRule ab = FareRule.builder().id(4L).route(route).fromStop(stopA).toStop(stopB).build();
        when(routeRepository.existsById(1L)).thenReturn(true);
        when(fareRuleRepository.findByRouteId(1L)).thenReturn(List.of(bc, ac, ab));
        when(fareRuleMapper.toResponseList(List.of(ab, ac, bc))).thenReturn(List.of(response));

        // When
        List<FareRuleResponse> result = fareRuleService.getFareRulesByRoute(1L);

        // Then: ordenadas por orden de origen y luego de destino
        assertThat(result).containsExactly(response);
    }

    @Test
    void shouldGetFareRulesByRoute_WithNonExistentRoute_ThrowResourceNotFound() {
        // Given
        when(routeRepository.existsById(99L)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> fareRuleService.getFareRulesByRoute(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(fareRuleRepository);
    }

    // ---------- Creación ----------

    @Test
    void shouldCreateFareRule_WithValidData_SaveNormalizedRule() {
        // Given
        givenRouteAndStops();
        FareRuleCreateRequest request = createRequest(Map.of("student", 10, " SENIOR ", 15), true);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 10L, 12L)).thenReturn(Optional.empty());
        when(fareRuleMapper.toEntity(request)).thenReturn(FareRule.builder().basePrice(new BigDecimal("40000")).build());
        when(fareRuleRepository.save(any(FareRule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fareRuleMapper.toResponse(any(FareRule.class))).thenReturn(response);

        // When
        FareRuleResponse result = fareRuleService.createFareRule(1L, request);

        // Then
        assertThat(result).isSameAs(response);
        ArgumentCaptor<FareRule> captor = ArgumentCaptor.forClass(FareRule.class);
        verify(fareRuleRepository).save(captor.capture());
        FareRule saved = captor.getValue();
        assertThat(saved.getRoute()).isSameAs(route);
        assertThat(saved.getFromStop()).isSameAs(stopA);
        assertThat(saved.getToStop()).isSameAs(stopC);
        assertThat(saved.getDiscounts()).containsOnly(entry("STUDENT", 10), entry("SENIOR", 15));
        assertThat(saved.getDynamicPricingEnabled()).isTrue();
    }

    @Test
    void shouldCreateFareRule_WithoutDiscountsOrDynamicFlag_SaveDefaults() {
        // Given
        givenRouteAndStops();
        FareRuleCreateRequest request = createRequest(null, null);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 10L, 12L)).thenReturn(Optional.empty());
        when(fareRuleMapper.toEntity(request)).thenReturn(FareRule.builder().build());
        when(fareRuleRepository.save(any(FareRule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fareRuleMapper.toResponse(any(FareRule.class))).thenReturn(response);

        // When
        fareRuleService.createFareRule(1L, request);

        // Then
        verify(fareRuleRepository).save(argThat(f -> f.getDiscounts() == null
                && Boolean.FALSE.equals(f.getDynamicPricingEnabled())));
    }

    @Test
    void shouldCreateFareRule_WithNonExistentRoute_ThrowResourceNotFound() {
        // Given
        when(routeRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> fareRuleService.createFareRule(99L, createRequest(null, false)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ruta");
        verifyNoInteractions(stopRepository, fareRuleRepository);
    }

    @Test
    void shouldCreateFareRule_WithNonExistentStop_ThrowResourceNotFound() {
        // Given
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(stopRepository.findById(10L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> fareRuleService.createFareRule(1L, createRequest(null, false)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Parada origen");
    }

    @Test
    void shouldCreateFareRule_WithStopOfAnotherRoute_ThrowInvalidStops() {
        // Given
        Stop foreign = Stop.builder().id(12L).route(Route.builder().id(2L).build()).order(5).build();
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(stopRepository.findById(10L)).thenReturn(Optional.of(stopA));
        when(stopRepository.findById(12L)).thenReturn(Optional.of(foreign));

        // When/Then
        assertThatThrownBy(() -> fareRuleService.createFareRule(1L, createRequest(null, false)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_STOPS");
                });
        verify(fareRuleRepository, never()).save(any());
    }

    @Test
    void shouldCreateFareRule_WithStopsInReverseOrder_ThrowInvalidSegment() {
        // Given: origen Barranquilla (3) y destino Santa Marta (1)
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(stopRepository.findById(12L)).thenReturn(Optional.of(stopC));
        when(stopRepository.findById(10L)).thenReturn(Optional.of(stopA));
        FareRuleCreateRequest request = new FareRuleCreateRequest(12L, 10L, BigDecimal.TEN, null, false);

        // When/Then
        assertThatThrownBy(() -> fareRuleService.createFareRule(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_SEGMENT");
    }

    @Test
    void shouldCreateFareRule_WithSameStop_ThrowInvalidSegment() {
        // Given
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(stopRepository.findById(10L)).thenReturn(Optional.of(stopA));
        FareRuleCreateRequest request = new FareRuleCreateRequest(10L, 10L, BigDecimal.TEN, null, false);

        // When/Then
        assertThatThrownBy(() -> fareRuleService.createFareRule(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_SEGMENT");
    }

    @Test
    void shouldCreateFareRule_WithDuplicatedSegment_ThrowConflict() {
        // Given
        givenRouteAndStops();
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 10L, 12L))
                .thenReturn(Optional.of(existingRule()));

        // When/Then
        assertThatThrownBy(() -> fareRuleService.createFareRule(1L, createRequest(null, false)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("FARE_RULE_EXISTS");
                });
        verify(fareRuleRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADULT", "VIP", ""})
    void shouldCreateFareRule_WithInvalidDiscountType_ThrowBadRequest(String type) {
        // Given
        givenRouteAndStops();
        FareRuleCreateRequest request = createRequest(Map.of(type, 10), false);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 10L, 12L)).thenReturn(Optional.empty());
        when(fareRuleMapper.toEntity(request)).thenReturn(FareRule.builder().build());

        // When/Then
        assertThatThrownBy(() -> fareRuleService.createFareRule(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_DISCOUNT_TYPE");
        verify(fareRuleRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101, 150})
    void shouldCreateFareRule_WithDiscountOutOfRange_ThrowBadRequest(int value) {
        // Given
        givenRouteAndStops();
        FareRuleCreateRequest request = createRequest(Map.of("CHILD", value), false);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 10L, 12L)).thenReturn(Optional.empty());
        when(fareRuleMapper.toEntity(request)).thenReturn(FareRule.builder().build());

        // When/Then
        assertThatThrownBy(() -> fareRuleService.createFareRule(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CHILD")
                .extracting("code").isEqualTo("INVALID_DISCOUNT_VALUE");
    }

    @Test
    void shouldCreateFareRule_WithNullDiscountValue_ThrowBadRequest() {
        // Given
        givenRouteAndStops();
        Map<String, Integer> discounts = new HashMap<>();
        discounts.put("STUDENT", null);
        FareRuleCreateRequest request = createRequest(discounts, false);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 10L, 12L)).thenReturn(Optional.empty());
        when(fareRuleMapper.toEntity(request)).thenReturn(FareRule.builder().build());

        // When/Then
        assertThatThrownBy(() -> fareRuleService.createFareRule(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_DISCOUNT_VALUE");
    }

    @Test
    void shouldCreateFareRule_WithBoundaryDiscounts_Accept() {
        // Given: 0 y 100 son válidos
        givenRouteAndStops();
        FareRuleCreateRequest request = createRequest(Map.of("CHILD", 100, "SENIOR", 0), false);
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 10L, 12L)).thenReturn(Optional.empty());
        when(fareRuleMapper.toEntity(request)).thenReturn(FareRule.builder().build());
        when(fareRuleRepository.save(any(FareRule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fareRuleMapper.toResponse(any(FareRule.class))).thenReturn(response);

        // When
        fareRuleService.createFareRule(1L, request);

        // Then
        verify(fareRuleRepository).save(argThat(f -> f.getDiscounts().get("CHILD").equals(100)
                && f.getDiscounts().get("SENIOR").equals(0)));
    }

    // ---------- Actualización ----------

    @Test
    void shouldUpdateFareRule_WithPriceDiscountsAndFlag_ApplyChanges() {
        // Given
        FareRule rule = existingRule();
        FareRuleUpdateRequest request = new FareRuleUpdateRequest(null, null, new BigDecimal("45000"),
                Map.of("senior", 20), true);
        when(fareRuleRepository.findById(1L)).thenReturn(Optional.of(rule));
        when(fareRuleRepository.save(rule)).thenReturn(rule);
        when(fareRuleMapper.toResponse(rule)).thenReturn(response);

        // When
        fareRuleService.updateFareRule(1L, request);

        // Then
        verify(fareRuleMapper).updateEntityFromRequest(request, rule);
        assertThat(rule.getDiscounts()).containsOnly(entry("SENIOR", 20));
        assertThat(rule.getDynamicPricingEnabled()).isTrue();
        verifyNoInteractions(stopRepository);
    }

    @Test
    void shouldUpdateFareRule_WithNullFields_KeepDiscountsAndFlag() {
        // Given
        FareRule rule = existingRule();
        rule.setDiscounts(Map.of("STUDENT", 5));
        rule.setDynamicPricingEnabled(true);
        FareRuleUpdateRequest request = new FareRuleUpdateRequest(null, null, new BigDecimal("45000"), null, null);
        when(fareRuleRepository.findById(1L)).thenReturn(Optional.of(rule));
        when(fareRuleRepository.save(rule)).thenReturn(rule);
        when(fareRuleMapper.toResponse(rule)).thenReturn(response);

        // When
        fareRuleService.updateFareRule(1L, request);

        // Then
        assertThat(rule.getDiscounts()).containsOnly(entry("STUDENT", 5));
        assertThat(rule.getDynamicPricingEnabled()).isTrue();
    }

    @Test
    void shouldUpdateFareRule_WithNewSegment_ValidateAndChangeStops() {
        // Given: de A→C a B→C
        FareRule rule = existingRule();
        FareRuleUpdateRequest request = new FareRuleUpdateRequest(11L, null, null, null, null);
        when(fareRuleRepository.findById(1L)).thenReturn(Optional.of(rule));
        when(stopRepository.findById(11L)).thenReturn(Optional.of(stopB));
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 11L, 12L)).thenReturn(Optional.empty());
        when(fareRuleRepository.save(rule)).thenReturn(rule);
        when(fareRuleMapper.toResponse(rule)).thenReturn(response);

        // When
        fareRuleService.updateFareRule(1L, request);

        // Then
        assertThat(rule.getFromStop()).isSameAs(stopB);
        assertThat(rule.getToStop()).isSameAs(stopC);
    }

    @Test
    void shouldUpdateFareRule_WithSameSegmentAsItself_NotConflict() {
        // Given: el tramo resultante es el de la propia regla
        FareRule rule = existingRule();
        FareRuleUpdateRequest request = new FareRuleUpdateRequest(10L, 12L, null, null, null);
        when(fareRuleRepository.findById(1L)).thenReturn(Optional.of(rule));
        when(stopRepository.findById(10L)).thenReturn(Optional.of(stopA));
        when(stopRepository.findById(12L)).thenReturn(Optional.of(stopC));
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 10L, 12L)).thenReturn(Optional.of(rule));
        when(fareRuleRepository.save(rule)).thenReturn(rule);
        when(fareRuleMapper.toResponse(rule)).thenReturn(response);

        // When
        FareRuleResponse result = fareRuleService.updateFareRule(1L, request);

        // Then
        assertThat(result).isSameAs(response);
    }

    @Test
    void shouldUpdateFareRule_WithSegmentOfAnotherRule_ThrowConflict() {
        // Given
        FareRule rule = existingRule();
        FareRule other = FareRule.builder().id(2L).route(route).fromStop(stopB).toStop(stopC).build();
        FareRuleUpdateRequest request = new FareRuleUpdateRequest(11L, null, null, null, null);
        when(fareRuleRepository.findById(1L)).thenReturn(Optional.of(rule));
        when(stopRepository.findById(11L)).thenReturn(Optional.of(stopB));
        when(fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(1L, 11L, 12L)).thenReturn(Optional.of(other));

        // When/Then
        assertThatThrownBy(() -> fareRuleService.updateFareRule(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("FARE_RULE_EXISTS");
        verify(fareRuleRepository, never()).save(any());
    }

    @Test
    void shouldUpdateFareRule_WithReversedSegment_ThrowInvalidSegment() {
        // Given: destino A (orden 1) con origen A
        FareRule rule = existingRule();
        FareRuleUpdateRequest request = new FareRuleUpdateRequest(null, 10L, null, null, null);
        when(fareRuleRepository.findById(1L)).thenReturn(Optional.of(rule));
        when(stopRepository.findById(10L)).thenReturn(Optional.of(stopA));

        // When/Then
        assertThatThrownBy(() -> fareRuleService.updateFareRule(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_SEGMENT");
    }

    @Test
    void shouldUpdateFareRule_WithInvalidDiscount_ThrowBadRequest() {
        // Given
        FareRule rule = existingRule();
        FareRuleUpdateRequest request = new FareRuleUpdateRequest(null, null, null, Map.of("VIP", 50), null);
        when(fareRuleRepository.findById(1L)).thenReturn(Optional.of(rule));

        // When/Then
        assertThatThrownBy(() -> fareRuleService.updateFareRule(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_DISCOUNT_TYPE");
        verify(fareRuleRepository, never()).save(any());
    }

    @Test
    void shouldUpdateFareRule_WithNonExistentRule_ThrowResourceNotFound() {
        // Given
        when(fareRuleRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> fareRuleService.updateFareRule(99L,
                new FareRuleUpdateRequest(null, null, BigDecimal.ONE, null, null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Tarifa");
    }

    // ---------- Eliminación ----------

    @Test
    void shouldDeleteFareRule_WithExistingRule_DeleteIt() {
        // Given
        FareRule rule = existingRule();
        when(fareRuleRepository.findById(1L)).thenReturn(Optional.of(rule));

        // When
        fareRuleService.deleteFareRule(1L);

        // Then
        verify(fareRuleRepository).delete(rule);
    }

    @Test
    void shouldDeleteFareRule_WithNonExistentRule_ThrowResourceNotFound() {
        // Given
        when(fareRuleRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> fareRuleService.deleteFareRule(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(fareRuleRepository, never()).delete(any());
    }
}
