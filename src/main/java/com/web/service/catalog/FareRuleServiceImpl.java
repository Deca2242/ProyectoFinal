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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Tarifas por tramo (FareRule) de una ruta: precio base, descuentos por tipo de pasajero y precio dinámico
@Service
@RequiredArgsConstructor
public class FareRuleServiceImpl implements FareRuleService {

    static final Set<String> DISCOUNT_TYPES = Set.of("STUDENT", "SENIOR", "CHILD");

    private final FareRuleRepository fareRuleRepository;
    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final FareRuleMapper fareRuleMapper;

    @Override
    @Transactional(readOnly = true)
    public List<FareRuleResponse> getFareRulesByRoute(Long routeId) {
        if (!routeRepository.existsById(routeId)) {
            throw new ResourceNotFoundException("Ruta", routeId);
        }
        List<FareRule> rules = fareRuleRepository.findByRouteId(routeId).stream()
                .sorted(Comparator.comparing((FareRule f) -> f.getFromStop().getOrder())
                        .thenComparing(f -> f.getToStop().getOrder()))
                .toList();
        return fareRuleMapper.toResponseList(rules);
    }

    @Override
    @Transactional
    public FareRuleResponse createFareRule(Long routeId, FareRuleCreateRequest request) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Ruta", routeId));

        Stop fromStop = findStop(request.fromStopId(), "Parada origen");
        Stop toStop = findStop(request.toStopId(), "Parada destino");
        validateSegment(route, fromStop, toStop);
        requireUniqueSegment(route.getId(), fromStop.getId(), toStop.getId(), null);

        FareRule fareRule = fareRuleMapper.toEntity(request);
        fareRule.setRoute(route);
        fareRule.setFromStop(fromStop);
        fareRule.setToStop(toStop);
        fareRule.setDiscounts(normalizeDiscounts(request.discounts()));
        fareRule.setDynamicPricingEnabled(Boolean.TRUE.equals(request.dynamicPricingEnabled()));

        return fareRuleMapper.toResponse(fareRuleRepository.save(fareRule));
    }

    @Override
    @Transactional
    public FareRuleResponse updateFareRule(Long id, FareRuleUpdateRequest request) {
        FareRule fareRule = fareRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tarifa", id));

        if (request.fromStopId() != null || request.toStopId() != null) {
            Stop fromStop = request.fromStopId() != null
                    ? findStop(request.fromStopId(), "Parada origen") : fareRule.getFromStop();
            Stop toStop = request.toStopId() != null
                    ? findStop(request.toStopId(), "Parada destino") : fareRule.getToStop();
            validateSegment(fareRule.getRoute(), fromStop, toStop);
            requireUniqueSegment(fareRule.getRoute().getId(), fromStop.getId(), toStop.getId(), fareRule.getId());
            fareRule.setFromStop(fromStop);
            fareRule.setToStop(toStop);
        }

        fareRuleMapper.updateEntityFromRequest(request, fareRule);
        if (request.discounts() != null) {
            fareRule.setDiscounts(normalizeDiscounts(request.discounts()));
        }
        if (request.dynamicPricingEnabled() != null) {
            fareRule.setDynamicPricingEnabled(request.dynamicPricingEnabled());
        }

        return fareRuleMapper.toResponse(fareRuleRepository.save(fareRule));
    }

    @Override
    @Transactional
    public void deleteFareRule(Long id) {
        FareRule fareRule = fareRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tarifa", id));
        fareRuleRepository.delete(fareRule);
    }

    private Stop findStop(Long stopId, String label) {
        return stopRepository.findById(stopId)
                .orElseThrow(() -> new ResourceNotFoundException(label, stopId));
    }

    // Las dos paradas deben ser de la ruta y en sentido de la ruta
    private void validateSegment(Route route, Stop fromStop, Stop toStop) {
        if (!route.getId().equals(fromStop.getRoute().getId()) || !route.getId().equals(toStop.getRoute().getId())) {
            throw new BusinessException("Las paradas no pertenecen a la ruta", HttpStatus.BAD_REQUEST, "INVALID_STOPS");
        }
        if (fromStop.getOrder() >= toStop.getOrder()) {
            throw new BusinessException("La parada de origen debe ser anterior a la de destino",
                    HttpStatus.BAD_REQUEST, "INVALID_SEGMENT");
        }
    }

    // Un tramo tiene una sola tarifa (la venta busca la regla por ruta, origen y destino)
    private void requireUniqueSegment(Long routeId, Long fromStopId, Long toStopId, Long currentId) {
        fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(routeId, fromStopId, toStopId)
                .filter(existing -> !existing.getId().equals(currentId))
                .ifPresent(existing -> {
                    throw new BusinessException("Ya existe una tarifa para ese tramo de la ruta",
                            HttpStatus.CONFLICT, "FARE_RULE_EXISTS");
                });
    }

    // Solo STUDENT, SENIOR y CHILD con porcentajes entre 0 y 100; las claves se guardan en mayúsculas
    private Map<String, Object> normalizeDiscounts(Map<String, Integer> discounts) {
        if (discounts == null) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : discounts.entrySet()) {
            String type = entry.getKey() == null ? "" : entry.getKey().trim().toUpperCase(Locale.ROOT);
            if (!DISCOUNT_TYPES.contains(type)) {
                throw new BusinessException("Tipo de descuento no válido: " + entry.getKey()
                        + " (válidos: STUDENT, SENIOR, CHILD)", HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT_TYPE");
            }
            Integer value = entry.getValue();
            if (value == null || value < 0 || value > 100) {
                throw new BusinessException("El descuento de " + type + " debe estar entre 0 y 100",
                        HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT_VALUE");
            }
            normalized.put(type, value);
        }
        return normalized;
    }
}
