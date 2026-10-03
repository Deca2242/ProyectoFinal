package com.web.service.catalog;

import com.web.dto.catalog.Bus.BusCreateRequest;
import com.web.dto.catalog.Bus.BusResponse;
import com.web.dto.catalog.Bus.BusUpdateRequest;
import com.web.dto.catalog.Seat.SeatResponse;
import com.web.dto.catalog.Seat.SeatUpdateRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface BusService {

    BusResponse createBus(BusCreateRequest request);

    List<BusResponse> getAllBuses();

    BusResponse getBusById(Long id);

    BusResponse getBusByPlate(String plate);

    BusResponse updateBus(Long id, BusUpdateRequest request);

    void deleteBus(Long id);

    // Con salida y llegada se filtra por solapamiento horario; con solo la fecha, por día
    List<BusResponse> getAvailableBuses(LocalDate date, LocalDateTime departureTime, LocalDateTime arrivalEta);

    List<SeatResponse> getSeats(Long busId);

    SeatResponse updateSeat(Long busId, Integer seatNumber, SeatUpdateRequest request);
}
