package com.web.dto.parcel.mapper;

import com.web.dto.parcel.ParcelCreateRequest;
import com.web.dto.parcel.ParcelResponse;
import com.web.entity.Parcel;
import org.mapstruct.InheritConfiguration;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ParcelMapper {
    
    // Entity → Response completa, con el OTP de entrega: solo para quien registra la encomienda (taquilla)
    @Named("toResponseWithOtp")
    @Mapping(target = "tripId", source = "trip.id")
    @Mapping(target = "routeName", source = "trip.route.name")
    @Mapping(target = "tripDate", source = "trip.tripDate")
    @Mapping(target = "fromStopId", source = "fromStop.id")
    @Mapping(target = "fromStopName", source = "fromStop.name")
    @Mapping(target = "toStopId", source = "toStop.id")
    @Mapping(target = "toStopName", source = "toStop.name")
    ParcelResponse toResponseWithOtp(Parcel parcel);

    // Entity → Response para el personal (conductor, taquilla): sin el OTP, que solo conoce el destinatario
    @InheritConfiguration(name = "toResponseWithOtp")
    @Mapping(target = "deliveryOtp", ignore = true)
    ParcelResponse toResponse(Parcel parcel);

    // Entity → Response para el rastreo público: sin OTP ni datos personales de remitente/destinatario
    @Named("toPublicResponse")
    @InheritConfiguration(name = "toResponseWithOtp")
    @Mapping(target = "deliveryOtp", ignore = true)
    @Mapping(target = "senderName", ignore = true)
    @Mapping(target = "senderPhone", ignore = true)
    @Mapping(target = "receiverName", ignore = true)
    @Mapping(target = "receiverPhone", ignore = true)
    ParcelResponse toPublicResponse(Parcel parcel);
    
    List<ParcelResponse> toResponseList(List<Parcel> parcels);
    
    // Request → Entity (sin relaciones)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true) // Se genera en servicio
    @Mapping(target = "trip", ignore = true)
    @Mapping(target = "fromStop", ignore = true)
    @Mapping(target = "toStop", ignore = true)
    @Mapping(target = "status", constant = "CREATED")
    @Mapping(target = "deliveryOtp", ignore = true) // Se genera al IN_TRANSIT
    @Mapping(target = "proofPhotoUrl", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "deliveredAt", ignore = true)
    Parcel toEntity(ParcelCreateRequest request);
}

