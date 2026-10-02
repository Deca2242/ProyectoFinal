package com.web.service.catalog;

import com.web.dto.catalog.Route.RouteCreateRequest;
import com.web.dto.catalog.Route.RouteDetailResponse;
import com.web.dto.catalog.Route.RouteResponse;
import com.web.dto.catalog.Route.RouteUpdateRequest;
import com.web.dto.catalog.Route.mapper.RouteMapper;
import com.web.dto.catalog.Stop.StopCreateRequest;
import com.web.dto.catalog.Stop.mapper.StopMapper;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.RouteRepository;
import com.web.repository.StopRepository;
import com.web.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;



@Service
@RequiredArgsConstructor
public class RouteServiceImpl implements RouteService {

    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final TripRepository tripRepository;
    private final RouteMapper routeMapper;
    private final StopMapper stopMapper;

    // Crea una nueva ruta validando que el código sea único
    @Override
    @Transactional
    public RouteResponse createRoute(RouteCreateRequest request) {
        if (routeRepository.existsByCode(request.code())) {
            throw new BusinessException("Ya existe una ruta con el código: " + request.code(), HttpStatus.CONFLICT, "ROUTE_CODE_EXISTS");
        }

        Route route = routeMapper.toEntity(request);
        Route savedRoute = routeRepository.save(route);



        return routeMapper.toResponse(savedRoute);
    }

    //Obtener todas las rutas
    @Override
    @Transactional(readOnly = true)
    public List<RouteResponse> getAllRoutes() {
        List<Route> routes = routeRepository.findAll();
        return routeMapper.toResponseList(routes);
    }

    //Obtener ruta por ID
    @Override
    @Transactional(readOnly = true)
    public RouteDetailResponse getRouteById(Long id) {
        Route route = routeRepository.findByIdWithStops(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ruta", id));
        return routeMapper.toDetailResponse(route);
    }

    //Actualizar ruta
    @Override
    @Transactional
    public RouteResponse updateRoute(Long id, RouteUpdateRequest request) {
        Route route = routeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ruta", id));


        routeMapper.updateEntityFromRequest(request, route);

        Route updatedRoute = routeRepository.save(route);


        return routeMapper.toResponse(updatedRoute);
    }

    //Eliminar ruta
    @Override
    @Transactional
    public void deleteRoute(Long id) {
        Route route = routeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ruta", id));

        // Se incluyen los viajes de hoy (antes solo se contaban los posteriores a hoy)
        if (tripRepository.countByRouteIdAndTripDateGreaterThanEqual(id, LocalDate.now()) > 0) {
            throw new BusinessException("No se puede eliminar la ruta porque tiene viajes programados", HttpStatus.BAD_REQUEST, "ROUTE_HAS_TRIPS");
        }

        // Borrado lógico: los viajes y tickets históricos siguen referenciando la ruta,
        // un DELETE físico violaría las llaves foráneas
        route.setIsActive(false);
        routeRepository.save(route);
    }

    //Añadir parada
    @Override
    @Transactional
    public RouteDetailResponse addStop(Long routeId, StopCreateRequest request) {
        Route route = routeRepository.findByIdWithStops(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Ruta", routeId));

        if (stopRepository.existsByRouteIdAndOrder(routeId, request.order())) {
            throw new BusinessException("Ya existe una parada con el orden " + request.order() + " en esta ruta", HttpStatus.CONFLICT, "STOP_ORDER_EXISTS");
        }

        Stop stop = stopMapper.toEntity(request);
        stop.setRoute(route);

        Stop savedStop = stopRepository.save(stop);

        // La ruta ya está en el contexto de persistencia con su lista de paradas cargada:
        // volver a consultarla devolvería la misma instancia sin la parada nueva
        if (route.getStops() == null) {
            route.setStops(new ArrayList<>());
        }
        route.getStops().add(savedStop);
        route.getStops().sort(Comparator.comparing(Stop::getOrder));

        return routeMapper.toDetailResponse(route);
    }

    //Remover parada
    @Override
    @Transactional
    public void removeStop(Long routeId, Long stopId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Ruta", routeId));

        Stop stop = stopRepository.findById(stopId)
                .orElseThrow(() -> new ResourceNotFoundException("Parada", stopId));

        if (!stop.getRoute().getId().equals(routeId)) {
            throw new BusinessException("La parada no pertenece a esta ruta", HttpStatus.BAD_REQUEST, "STOP_ROUTE_MISMATCH");
        }

        // Borrarla dejaría huérfanos tickets, encomiendas o tarifas (y cambiaría el orden de los tramos vendidos)
        if (stopRepository.isReferenced(stopId)) {
            throw new BusinessException("La parada tiene tickets, encomiendas o tarifas asociadas y no se puede eliminar",
                    HttpStatus.CONFLICT, "STOP_IN_USE");
        }

        stopRepository.delete(stop);

    }

    // buscar rutas que conecten dos ciudades específicas
    @Override
    @Transactional(readOnly = true)
    public List<RouteResponse> findRoutesConnecting(String origin, String destination) {
        List<Route> routes = routeRepository.findRoutesConnecting(origin, destination);
        return routeMapper.toResponseList(routes);
    }
}

