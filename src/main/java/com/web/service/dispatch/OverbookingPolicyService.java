package com.web.service.dispatch;

import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyCreateRequest;
import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyResponse;

import java.util.List;

public interface OverbookingPolicyService {

    List<OverbookingPolicyResponse> getPoliciesByRoute(Long routeId);

    OverbookingPolicyResponse createPolicy(Long routeId, OverbookingPolicyCreateRequest request);

    // Edita franja y porcentaje validando el solape con las demás políticas de la ruta
    OverbookingPolicyResponse updatePolicy(Long id, OverbookingPolicyCreateRequest request);

    void deletePolicy(Long id);
}
