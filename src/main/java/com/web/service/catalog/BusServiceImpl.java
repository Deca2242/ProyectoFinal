package com.web.service.catalog;

import com.web.dto.catalog.Bus.BusCreateRequest;
import com.web.dto.catalog.Bus.BusResponse;
import com.web.dto.catalog.Bus.BusUpdateRequest;
import com.web.dto.catalog.Bus.mapper.BusMapper;
import com.web.dto.catalog.Seat.SeatResponse;
import com.web.dto.catalog.Seat.SeatUpdateRequest;
import com.web.dto.catalog.Seat.mapper.SeatMapper;
import com.web.entity.Bus;
import com.web.entity.Seat;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.BusRepository;
import com.web.repository.SeatRepository;
import com.web.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;



@Service
@RequiredArgsConstructor
public class BusServiceImpl implements BusService {

    private final BusRepository busRepository;
    private final TripRepository tripRepository;
    private final SeatRepository seatRepository;
    private final BusMapper busMapper;
    private final SeatMapper seatMapper;

    // Registra un nuevo bus validando que la placa sea única y genera sus sillas 1..capacidad (STANDARD)
    @Override
    @Transactional
    public BusResponse createBus(BusCreateRequest request) {
        String plate = normalizePlate(request.plate());
        if (busRepository.findByPlate(plate).isPresent()) {
            throw new BusinessException("Ya existe un bus con la placa: " + plate, HttpStatus.CONFLICT, "PLATE_EXISTS");
        }

        Bus bus = busMapper.toEntity(request);
        bus.setPlate(plate);
        Bus savedBus = busRepository.save(bus);

        syncSeats(savedBus);

        return busMapper.toResponse(savedBus);
    }

    //Obtener todos los buses
    @Override
    @Transactional(readOnly = true)
    public List<BusResponse> getAllBuses() {
        List<Bus> buses = busRepository.findAll();
        return busMapper.toResponseList(buses);
    }

    //Obtener bus por ID
    @Override
    @Transactional(readOnly = true)
    public BusResponse getBusById(Long id) {
        Bus bus = busRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bus", id));
        return busMapper.toResponse(bus);
    }

    //Obtener bus por placa (se normaliza igual que al crear: mayúsculas y sin espacios)
    @Override
    @Transactional(readOnly = true)
    public BusResponse getBusByPlate(String plate) {
        String normalized = normalizePlate(plate);
        Bus bus = busRepository.findByPlate(normalized)
                .orElseThrow(() -> new ResourceNotFoundException("Bus", normalized));
        return busMapper.toResponse(bus);
    }

    //Actualizar bus
    @Override
    @Transactional
    public BusResponse updateBus(Long id, BusUpdateRequest request) {
        Bus bus = busRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bus", id));
        LocalDateTime now = LocalDateTime.now();

        // No se puede reducir la capacidad por debajo de una silla ya vendida en un viaje futuro
        if (request.capacity() != null && request.capacity() < bus.getCapacity()) {
            Integer maxSoldSeat = tripRepository.findMaxSoldSeatNumberInFutureTrips(id, now);
            if (maxSoldSeat != null && request.capacity() < maxSoldSeat) {
                throw new BusinessException("Hay tiquetes vendidos hasta la silla " + maxSoldSeat
                        + " en viajes futuros: la capacidad no puede ser menor", HttpStatus.CONFLICT, "CAPACITY_BELOW_SOLD_SEATS");
            }
        }

        // Un bus con viajes pendientes no puede pasar a mantenimiento ni retirarse
        if (request.status() != null && request.status() != Bus.BusStatus.ACTIVE
                && tripRepository.existsPendingTripsByBus(id, now)) {
            throw new BusinessException("El bus tiene viajes programados: reasígnelos antes de cambiar su estado",
                    HttpStatus.CONFLICT, "BUS_HAS_TRIPS");
        }

        boolean capacityChanged = request.capacity() != null && !request.capacity().equals(bus.getCapacity());

        busMapper.updateEntityFromRequest(request, bus);

        Bus updatedBus = busRepository.save(bus);

        if (capacityChanged) {
            syncSeats(updatedBus);
        }

        return busMapper.toResponse(updatedBus);
    }

    //"Eliminar" bus: borrado lógico (RETIRED); los viajes históricos siguen referenciándolo
    @Override
    @Transactional
    public void deleteBus(Long id) {
        Bus bus = busRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bus", id));

        if (tripRepository.existsPendingTripsByBus(id, LocalDateTime.now())) {
            throw new BusinessException("El bus tiene viajes programados: reasígnelos antes de retirarlo",
                    HttpStatus.CONFLICT, "BUS_HAS_TRIPS");
        }

        bus.setStatus(Bus.BusStatus.RETIRED);
        busRepository.save(bus);
    }

    // Buses activos libres: con salida y llegada se excluyen los que tienen un viaje que se solapa con la franja;
    // con solo la fecha, los que tienen un viaje activo (no cancelado ni llegado) ese día
    @Override
    @Transactional(readOnly = true)
    public List<BusResponse> getAvailableBuses(LocalDate date, LocalDateTime departureTime, LocalDateTime arrivalEta) {
        Set<Long> busyBusIds;
        if (departureTime != null || arrivalEta != null) {
            if (departureTime == null || arrivalEta == null || !arrivalEta.isAfter(departureTime)) {
                throw new BusinessException("Debe indicar salida y llegada, con la llegada posterior a la salida",
                        HttpStatus.BAD_REQUEST, "INVALID_DATES");
            }
            busyBusIds = new HashSet<>(tripRepository.findBusIdsWithOverlappingTrips(departureTime, arrivalEta));
        } else if (date != null) {
            busyBusIds = new HashSet<>(tripRepository.findBusIdsWithTripsOnDate(date));
        } else {
            busyBusIds = Set.of();
        }
        List<Bus> buses = busRepository.findAll().stream()
                .filter(b -> b.getStatus() == Bus.BusStatus.ACTIVE)
                .filter(b -> !busyBusIds.contains(b.getId()))
                .collect(Collectors.toList());
        return busMapper.toResponseList(buses);
    }

    // Sillas físicas del bus ordenadas por número
    @Override
    @Transactional(readOnly = true)
    public List<SeatResponse> getSeats(Long busId) {
        if (!busRepository.existsById(busId)) {
            throw new ResourceNotFoundException("Bus", busId);
        }
        return seatMapper.toResponseList(seatRepository.findByBusIdOrderBySeatNumberAsc(busId));
    }

    // Cambia el tipo de una silla (STANDARD / PREFERENTIAL)
    @Override
    @Transactional
    public SeatResponse updateSeat(Long busId, Integer seatNumber, SeatUpdateRequest request) {
        if (!busRepository.existsById(busId)) {
            throw new ResourceNotFoundException("Bus", busId);
        }
        Seat seat = seatRepository.findByBusIdAndSeatNumber(busId, seatNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Silla " + seatNumber + " del bus " + busId + " no encontrada"));
        seat.setSeatType(request.seatType());
        return seatMapper.toResponse(seatRepository.save(seat));
    }

    // Deja las sillas del bus en 1..capacidad: crea las que faltan (STANDARD) y borra las que sobran
    private void syncSeats(Bus bus) {
        int capacity = bus.getCapacity();
        seatRepository.deleteByBusIdAndSeatNumberGreaterThan(bus.getId(), capacity);

        Set<Integer> existing = seatRepository.findByBusIdOrderBySeatNumberAsc(bus.getId()).stream()
                .map(Seat::getSeatNumber)
                .collect(Collectors.toSet());
        List<Seat> missing = new ArrayList<>();
        for (int number = 1; number <= capacity; number++) {
            if (!existing.contains(number)) {
                missing.add(Seat.builder().bus(bus).seatNumber(number).seatType(Seat.SeatType.STANDARD).build());
            }
        }
        if (!missing.isEmpty()) {
            seatRepository.saveAll(missing);
        }
    }

    // Placa en mayúsculas y sin espacios ("abc 123" y "ABC123" son el mismo bus)
    private static String normalizePlate(String plate) {
        return plate == null ? null : plate.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}
