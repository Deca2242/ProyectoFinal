package com.web.service.catalog;

import com.web.dto.catalog.FareRule.FareRuleCreateRequest;
import com.web.dto.catalog.FareRule.FareRuleResponse;
import com.web.dto.catalog.FareRule.FareRuleUpdateRequest;

import java.util.List;

public interface FareRuleService {

    List<FareRuleResponse> getFareRulesByRoute(Long routeId);

    FareRuleResponse createFareRule(Long routeId, FareRuleCreateRequest request);

    FareRuleResponse updateFareRule(Long id, FareRuleUpdateRequest request);

    void deleteFareRule(Long id);
}
