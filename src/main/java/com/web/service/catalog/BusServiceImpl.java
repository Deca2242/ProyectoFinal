package com.web.service.catalog;

import com.web.dto.catalog.Bus.BusCreateRequest;
import com.web.dto.catalog.Bus.BusResponse;
import com.web.dto.catalog.Bus.BusUpdateRequest;
import com.web.dto.catalog.Bus.mapper.BusMapper;
import com.web.entity.Bus;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.BusRepository;
import com.web.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;



@Service
@RequiredArgsConstructor
public class BusServiceImpl implements BusService {

    private final BusRepository busRepository;
    private final TripRepository tripRepository;
    private final BusMapper busMapper;

    // Registra un nuevo bus validando que la placa sea única
    @Override
    @Transactional
    public BusResponse createBus(BusCreateRequest request) {
        if (busRepository.findByPlate(request.plate()).isPresent()) {
            throw new BusinessException("Ya existe un bus con la placa: " + request.plate(), HttpStatus.CONFLICT, "PLATE_EXISTS");
        }

        Bus bus = busMapper.toEntity(request);
        Bus savedBus = busRepository.save(bus);

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

    //Obtener bus por placa
    @Override
    @Transactional(readOnly = true)
    public BusResponse getBusByPlate(String plate) {
        Bus bus = busRepository.findByPlate(plate)
                .orElseThrow(() -> new ResourceNotFoundException("Bus", plate));
        return busMapper.toResponse(bus);
    }

    //Actualizar bus
    @Override
    @Transactional
    public BusResponse updateBus(Long id, BusUpdateRequest request) {
        Bus bus = busRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bus", id));


        busMapper.updateEntityFromRequest(request, bus);

        Bus updatedBus = busRepository.save(bus);



        return busMapper.toResponse(updatedBus);
    }

    //"Eliminar" bus
    @Override
    @Transactional
    public void deleteBus(Long id) {
        Bus bus = busRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bus", id));

        bus.setStatus(Bus.BusStatus.MAINTENANCE);
        busRepository.save(bus);


    }

    // Obtener buses disponibles en una fecha: activos y sin otro viaje (no cancelado) ese día
    @Override
    @Transactional(readOnly = true)
    public List<BusResponse> getAvailableBuses(LocalDate date) {
        Set<Long> busyBusIds = date == null
                ? Set.of()
                : new HashSet<>(tripRepository.findBusIdsWithTripsOnDate(date));
        List<Bus> buses = busRepository.findAll().stream()
                .filter(b -> b.getStatus() == Bus.BusStatus.ACTIVE)
                .filter(b -> !busyBusIds.contains(b.getId()))
                .collect(Collectors.toList());
        return busMapper.toResponseList(buses);
    }
}

