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

    // Entity → Response para el personal (conductor, taquilla). La entidad solo guarda el hash del OTP:
    // nunca se copia a la respuesta
    @Mapping(target = "tripId", source = "trip.id")
    @Mapping(target = "routeName", source = "trip.route.name")
    @Mapping(target = "tripDate", source = "trip.tripDate")
    @Mapping(target = "fromStopId", source = "fromStop.id")
    @Mapping(target = "fromStopName", source = "fromStop.name")
    @Mapping(target = "toStopId", source = "toStop.id")
    @Mapping(target = "toStopName", source = "toStop.name")
    @Mapping(target = "deliveryOtp", ignore = true)
    ParcelResponse toResponse(Parcel parcel);

    // Entity → Response para el rastreo público: sin OTP ni datos personales de remitente/destinatario
    @Named("toPublicResponse")
    @InheritConfiguration(name = "toResponse")
    @Mapping(target = "senderName", ignore = true)
    @Mapping(target = "senderPhone", ignore = true)
    @Mapping(target = "receiverName", ignore = true)
    @Mapping(target = "receiverPhone", ignore = true)
    ParcelResponse toPublicResponse(Parcel parcel);

    List<ParcelResponse> toResponseList(List<Parcel> parcels);

    // Response completa con el OTP en claro: solo para la taquilla que registra la encomienda,
    // que se lo entrega al destinatario (el OTP en claro no se guarda en ningún sitio)
    default ParcelResponse toResponseWithOtp(Parcel parcel, String deliveryOtp) {
        ParcelResponse r = toResponse(parcel);
        if (r == null) {
            return null;
        }
        return new ParcelResponse(r.id(), r.code(), r.tripId(), r.routeName(), r.tripDate(),
                r.senderName(), r.senderPhone(), r.receiverName(), r.receiverPhone(),
                r.fromStopId(), r.fromStopName(), r.toStopId(), r.toStopName(),
                r.price(), r.weightKg(), r.status(), deliveryOtp, r.proofPhotoUrl(),
                r.createdAt(), r.deliveredAt(), r.description(), r.otpAttempts());
    }

    // Request → Entity (sin relaciones)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true) // Se genera en servicio
    @Mapping(target = "trip", ignore = true)
    @Mapping(target = "fromStop", ignore = true)
    @Mapping(target = "toStop", ignore = true)
    @Mapping(target = "status", constant = "CREATED")
    @Mapping(target = "deliveryOtp", ignore = true) // El servicio guarda el hash
    @Mapping(target = "otpAttempts", ignore = true)
    @Mapping(target = "proofPhotoUrl", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "deliveredAt", ignore = true)
    Parcel toEntity(ParcelCreateRequest request);
}
