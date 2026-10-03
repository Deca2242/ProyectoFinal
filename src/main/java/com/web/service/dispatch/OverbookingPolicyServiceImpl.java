package com.web.service.dispatch;

import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyCreateRequest;
import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyResponse;
import com.web.dto.dispatch.OverbookingPolicy.mapper.OverbookingPolicyMapper;
import com.web.entity.OverbookingPolicy;
import com.web.entity.Route;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.OverbookingPolicyRepository;
import com.web.repository.RouteRepository;
import com.web.repository.UserRepository;
import com.web.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// % de overbooking por ruta y franja horaria de salida (historia del despachador)
@Service
@RequiredArgsConstructor
public class OverbookingPolicyServiceImpl implements OverbookingPolicyService {

    private final OverbookingPolicyRepository policyRepository;
    private final RouteRepository routeRepository;
    private final UserRepository userRepository;
    private final OverbookingPolicyMapper policyMapper;

    @Override
    @Transactional(readOnly = true)
    public List<OverbookingPolicyResponse> getPoliciesByRoute(Long routeId) {
        if (!routeRepository.existsById(routeId)) {
            throw new ResourceNotFoundException("Ruta", routeId);
        }
        return policyMapper.toResponseList(policyRepository.findByRouteIdOrderByStartHourAsc(routeId));
    }

    @Override
    @Transactional
    public OverbookingPolicyResponse createPolicy(Long routeId, OverbookingPolicyCreateRequest request) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Ruta", routeId));

        validateRange(routeId, null, request);

        OverbookingPolicy policy = policyMapper.toEntity(request);
        policy.setRoute(route);
        policy.setCreatedBy(SecurityUtils.currentUsername().flatMap(userRepository::findByEmail).orElse(null));

        return policyMapper.toResponse(policyRepository.save(policy));
    }

    // Reemplaza la franja y el porcentaje de una política (la ruta no cambia)
    @Override
    @Transactional
    public OverbookingPolicyResponse updatePolicy(Long id, OverbookingPolicyCreateRequest request) {
        OverbookingPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Política de overbooking", id));

        validateRange(policy.getRoute().getId(), id, request);

        policyMapper.updateEntityFromRequest(request, policy);
        return policyMapper.toResponse(policyRepository.save(policy));
    }

    @Override
    @Transactional
    public void deletePolicy(Long id) {
        OverbookingPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Política de overbooking", id));
        policyRepository.delete(policy);
    }

    // La franja debe ser válida (400) y no solaparse con otra política de la ruta (409); al editar, excluye la propia
    private void validateRange(Long routeId, Long excludedPolicyId, OverbookingPolicyCreateRequest request) {
        if (request.startHour() >= request.endHour()) {
            throw new BusinessException("La hora de inicio debe ser menor que la hora de fin",
                    HttpStatus.BAD_REQUEST, "INVALID_HOUR_RANGE");
        }

        // Las franjas de una ruta no se pueden solapar: la hora de salida debe tener una sola política
        boolean overlaps = policyRepository.findByRouteIdOrderByStartHourAsc(routeId).stream()
                .filter(p -> !p.getId().equals(excludedPolicyId))
                .anyMatch(p -> p.getStartHour() < request.endHour() && p.getEndHour() > request.startHour());
        if (overlaps) {
            throw new BusinessException("La franja " + request.startHour() + "-" + request.endHour()
                    + " se solapa con otra política de la ruta", HttpStatus.CONFLICT, "OVERBOOKING_POLICY_OVERLAP");
        }
    }
}
