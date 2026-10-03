package com.web.dto.trip.mapper;

import com.web.dto.catalog.Bus.mapper.BusMapper;
import com.web.dto.catalog.Route.mapper.RouteMapper;
import com.web.dto.dispatch.Assignment.mapper.AssignmentMapper;
import com.web.dto.trip.TripCreateRequest;
import com.web.dto.trip.TripDetailResponse;
import com.web.dto.trip.TripResponse;
import com.web.dto.trip.TripUpdateRequest;
import com.web.entity.Trip;
import com.web.repository.TicketRepository;
import org.mapstruct.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

// Clase abstracta para calcular la ocupación en el propio mapper: así todas las respuestas
// (búsqueda, despacho, reprogramación...) salen con sillas vendidas, disponibles y % de ocupación
@Mapper(componentModel = "spring", uses = {RouteMapper.class, BusMapper.class, AssignmentMapper.class})
public abstract class TripMapper {

    protected TicketRepository ticketRepository;

    @Autowired
    public void setTicketRepository(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    // Entity → Response simple (sin ocupación; la completa toResponse)
    @Named("baseResponse")
    @Mapping(target = "routeId", source = "route.id")
    @Mapping(target = "routeName", source = "route.name")
    @Mapping(target = "routeOrigin", source = "route.origin")
    @Mapping(target = "routeDestination", source = "route.destination")
    @Mapping(target = "busId", source = "bus.id")
    @Mapping(target = "busPlate", source = "bus.plate")
    @Mapping(target = "busCapacity", source = "bus.capacity")
    @Mapping(target = "soldSeats", ignore = true)
    @Mapping(target = "occupancyPercentage", ignore = true)
    @Mapping(target = "availableSeats", ignore = true)
    protected abstract TripResponse toBaseResponse(Trip trip);

    // Entity → Response simple con la ocupación calculada
    public TripResponse toResponse(Trip trip) {
        TripResponse base = toBaseResponse(trip);
        if (base == null) {
            return null;
        }
        Occupancy occupancy = occupancyOf(trip);
        return new TripResponse(base.id(), base.routeId(), base.routeName(), base.routeOrigin(),
                base.routeDestination(), base.busId(), base.busPlate(), base.busCapacity(),
                base.tripDate(), base.departureTime(), base.arrivalEta(), base.status(),
                occupancy.soldSeats(), occupancy.percentage(), occupancy.availableSeats(),
                base.platform(), base.departedAt(), base.arrivedAt());
    }

    public List<TripResponse> toResponseList(List<Trip> trips) {
        if (trips == null) {
            return null;
        }
        return trips.stream().map(this::toResponse).toList();
    }

    // Entity → Response detallado (sin ocupación; la completa toDetailResponse)
    @Named("baseDetailResponse")
    @Mapping(target = "route", source = "route")
    @Mapping(target = "bus", source = "bus")
    @Mapping(target = "assignment", source = "assignment")
    @Mapping(target = "soldSeats", ignore = true)
    @Mapping(target = "availableSeats", ignore = true)
    @Mapping(target = "occupancyPercentage", ignore = true)
    @Mapping(target = "availableSeatNumbers", ignore = true) // Se calcula en servicio
    protected abstract TripDetailResponse toBaseDetailResponse(Trip trip);

    // Entity → Response detallado con la ocupación calculada (los números de silla libres los añade el servicio)
    public TripDetailResponse toDetailResponse(Trip trip) {
        TripDetailResponse base = toBaseDetailResponse(trip);
        if (base == null) {
            return null;
        }
        Occupancy occupancy = occupancyOf(trip);
        return new TripDetailResponse(base.id(), base.route(), base.bus(), base.tripDate(),
                base.departureTime(), base.arrivalEta(), base.status(), base.assignment(),
                occupancy.soldSeats(), occupancy.availableSeats(), occupancy.percentage(),
                null, base.platform(), base.departedAt(), base.arrivedAt());
    }

    // Request → Entity
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "route", ignore = true)
    @Mapping(target = "bus", ignore = true)
    @Mapping(target = "status", constant = "SCHEDULED")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "seatHolds", ignore = true)
    @Mapping(target = "tickets", ignore = true)
    @Mapping(target = "parcels", ignore = true)
    @Mapping(target = "departedAt", ignore = true)
    @Mapping(target = "arrivedAt", ignore = true)
    @Mapping(target = "overbookingApprovedSeats", ignore = true)
    @Mapping(target = "platform", ignore = true)
    @Mapping(target = "arrivalNotified", ignore = true)
    @Mapping(target = "assignment", ignore = true)
    public abstract Trip toEntity(TripCreateRequest request);

    // Update parcial
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "route", ignore = true)
    @Mapping(target = "bus", ignore = true)
    @Mapping(target = "tripDate", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "seatHolds", ignore = true)
    @Mapping(target = "tickets", ignore = true)
    @Mapping(target = "parcels", ignore = true)
    @Mapping(target = "assignment", ignore = true)
    @Mapping(target = "departedAt", ignore = true)
    @Mapping(target = "arrivedAt", ignore = true)
    @Mapping(target = "overbookingApprovedSeats", ignore = true)
    @Mapping(target = "platform", ignore = true)
    @Mapping(target = "arrivalNotified", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    public abstract void updateEntityFromRequest(TripUpdateRequest request, @MappingTarget Trip trip);

    // Sillas vendidas (distintas), disponibles (capacidad + overbooking aprobado - vendidas) y % sobre la capacidad física
    private Occupancy occupancyOf(Trip trip) {
        Long count = trip.getId() != null && ticketRepository != null
                ? ticketRepository.countSoldSeats(trip.getId())
                : null;
        long sold = count == null ? 0L : count;
        if (trip.getBus() == null || trip.getBus().getCapacity() == null) {
            return new Occupancy((int) sold, null, null);
        }
        int capacity = trip.getBus().getCapacity();
        int approved = trip.getOverbookingApprovedSeats() == null ? 0 : trip.getOverbookingApprovedSeats();
        int available = Math.max(0, capacity + approved - (int) sold);
        double percentage = capacity > 0 ? (sold * 100.0) / capacity : 0.0;
        return new Occupancy((int) sold, available, percentage);
    }

    private record Occupancy(Integer soldSeats, Integer availableSeats, Double percentage) {
    }
}
