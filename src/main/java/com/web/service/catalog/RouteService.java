package com.web.service.catalog;

import com.web.dto.catalog.Route.RouteCreateRequest;
import com.web.dto.catalog.Route.RouteDetailResponse;
import com.web.dto.catalog.Route.RouteResponse;
import com.web.dto.catalog.Route.RouteUpdateRequest;
import com.web.dto.catalog.Stop.StopCreateRequest;
import com.web.dto.catalog.Stop.StopResponse;
import com.web.dto.catalog.Stop.StopUpdateRequest;

import java.util.List;

public interface RouteService {
    
    RouteResponse createRoute(RouteCreateRequest request);
    
    // includeInactive solo tiene efecto para ADMIN; el resto solo ve rutas activas
    List<RouteResponse> getAllRoutes(boolean includeInactive);
    
    RouteDetailResponse getRouteById(Long id, boolean includeInactive);
    
    RouteResponse updateRoute(Long id, RouteUpdateRequest request);
    
    void deleteRoute(Long id);
    
    RouteDetailResponse addStop(Long routeId, StopCreateRequest request);
    
    StopResponse updateStop(Long routeId, Long stopId, StopUpdateRequest request);

    void removeStop(Long routeId, Long stopId);
    
    List<RouteResponse> findRoutesConnecting(String origin, String destination);
}

