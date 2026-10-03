package com.web.service.parcel;

import com.web.dto.parcel.ParcelCreateRequest;
import com.web.dto.parcel.ParcelResponse;
import com.web.entity.Parcel;

import java.time.LocalDate;
import java.util.List;

public interface ParcelService {

    ParcelResponse createParcel(ParcelCreateRequest request);

    // Filtros opcionales: from/to sobre la fecha del viaje (inclusivas) y estado
    List<ParcelResponse> searchParcels(LocalDate from, LocalDate to, Parcel.ParcelStatus status);

    ParcelResponse trackParcel(String code);

    // IN_TRANSIT y FAILED; DELIVERED delega en la entrega con OTP y exige OTP y foto
    ParcelResponse updateStatus(String code, Parcel.ParcelStatus status, String otp, String photoUrl);

    ParcelResponse deliverWithOtp(String code, String otp, String photoUrl);

    // FAILED → IN_TRANSIT (DISPATCHER/ADMIN): reinicia los intentos de OTP y opcionalmente cambia de viaje
    ParcelResponse reopenParcel(String code, Long tripId);

    // Encomiendas de un viaje (el conductor solo las de sus viajes asignados); status opcional
    List<ParcelResponse> getTripParcels(Long tripId, Parcel.ParcelStatus status);

    List<ParcelResponse> getParcelsInTransit(Long tripId);

    List<ParcelResponse> getParcelsByDateRange(LocalDate startDate, LocalDate endDate);
}
