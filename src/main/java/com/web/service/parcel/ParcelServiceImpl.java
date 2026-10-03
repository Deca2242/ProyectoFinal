package com.web.service.parcel;

import com.web.dto.parcel.ParcelCreateRequest;
import com.web.dto.parcel.ParcelResponse;
import com.web.dto.parcel.mapper.ParcelMapper;
import com.web.entity.Incident;
import com.web.entity.Parcel;
import com.web.entity.Stop;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
import com.web.exception.InvalidSegmentException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.IncidentRepository;
import com.web.repository.ParcelRepository;
import com.web.repository.StopRepository;
import com.web.repository.TripRepository;
import com.web.util.OtpGenerator;
import com.web.util.QrCodeGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;



@Service
@RequiredArgsConstructor
public class ParcelServiceImpl implements ParcelService {

    private final ParcelRepository parcelRepository;
    private final TripRepository tripRepository;
    private final StopRepository stopRepository;
    private final IncidentRepository incidentRepository;
    private final ParcelMapper parcelMapper;
    private final QrCodeGenerator qrCodeGenerator;
    private final OtpGenerator otpGenerator;


    @Override
    public List<ParcelResponse> getAllParcels() {
        return parcelRepository.findAll().stream()
                .map(parcelMapper::toResponse)
                .toList();
    }

    //Crear una encomienda
    @Override
    @Transactional
    public ParcelResponse createParcel(ParcelCreateRequest request) {
        Trip trip = tripRepository.findById(request.tripId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", request.tripId()));

        // Solo se aceptan encomiendas para viajes que aún no han salido
        if (trip.getStatus() != Trip.TripStatus.SCHEDULED && trip.getStatus() != Trip.TripStatus.BOARDING) {
            throw new BusinessException("El viaje no admite encomiendas (estado: " + trip.getStatus() + ")",
                    HttpStatus.BAD_REQUEST, "TRIP_NOT_AVAILABLE");
        }

        // Validar paradas de origen y destino
        Stop fromStop = stopRepository.findById(request.fromStopId())
                .orElseThrow(() -> new ResourceNotFoundException("Parada origen", request.fromStopId()));

        Stop toStop = stopRepository.findById(request.toStopId())
                .orElseThrow(() -> new ResourceNotFoundException("Parada destino", request.toStopId()));

        // Las paradas deben pertenecer a la ruta del viaje y respetar el sentido del recorrido
        Long routeId = trip.getRoute().getId();
        if (!fromStop.getRoute().getId().equals(routeId) || !toStop.getRoute().getId().equals(routeId)) {
            throw new InvalidSegmentException("Las paradas no pertenecen a la ruta del viaje");
        }
        if (fromStop.getOrder() >= toStop.getOrder()) {
            throw new InvalidSegmentException("La parada de origen debe ser anterior a la de destino");
        }

        String code = qrCodeGenerator.generateParcelCode();
        String deliveryOtp = otpGenerator.generate6DigitOtp();

        Parcel parcel = parcelMapper.toEntity(request);
        // Establecer las relaciones manualmente
        parcel.setTrip(trip);
        parcel.setFromStop(fromStop);
        parcel.setToStop(toStop);
        parcel.setCode(code);
        parcel.setDeliveryOtp(deliveryOtp);

        Parcel savedParcel = parcelRepository.save(parcel);



        // La taquilla recibe el OTP para entregárselo al destinatario; ninguna otra respuesta lo incluye
        return parcelMapper.toResponseWithOtp(savedParcel);
    }


    // Rastrea una encomienda por su código de rastreo
    @Override
    @Transactional(readOnly = true)
    public ParcelResponse trackParcel(String code) {
        Parcel parcel = parcelRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Encomienda con código: " + code));
        // El rastreo es público: no se devuelve el OTP de entrega
        return parcelMapper.toPublicResponse(parcel);
    }

    // Actualiza el estado de una encomienda
    // DELIVERED solo se alcanza con deliverWithOtp; aquí se validan el resto de transiciones
    @Override
    @Transactional
    public ParcelResponse updateStatus(Long parcelId, Parcel.ParcelStatus status) {
        Parcel parcel = parcelRepository.findById(parcelId)
                .orElseThrow(() -> new ResourceNotFoundException("Encomienda", parcelId));

        if (status == Parcel.ParcelStatus.DELIVERED) {
            throw new BusinessException("La entrega debe registrarse con OTP y foto de prueba",
                    HttpStatus.BAD_REQUEST, "DELIVERY_REQUIRES_OTP");
        }
        if (!isValidStatusTransition(parcel.getStatus(), status)) {
            throw new InvalidStateTransitionException(parcel.getStatus().name(), status.name());
        }

        parcel.setStatus(status);
        Parcel updatedParcel = parcelRepository.save(parcel);

        return parcelMapper.toResponse(updatedParcel);
    }

    // Entrega una encomienda validando el OTP y guardando foto de prueba.
    // noRollbackFor: con OTP inválido se guardan el estado FAILED y el incidente antes de lanzar el error;
    // sin él, la excepción deshacía ambos cambios
    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public ParcelResponse deliverWithOtp(Long parcelId, String otp, String photoUrl) {
        Parcel parcel = parcelRepository.findById(parcelId)
                .orElseThrow(() -> new ResourceNotFoundException("Encomienda", parcelId));

        if (parcel.getStatus() != Parcel.ParcelStatus.IN_TRANSIT) {
            throw new BusinessException("Solo se pueden entregar encomiendas en tránsito", HttpStatus.BAD_REQUEST,
                    "INVALID_PARCEL_STATUS");
        }

        boolean otpValid = otpGenerator.validateOtp(otp, parcel.getDeliveryOtp());

        if (!otpValid) {
            parcel.setStatus(Parcel.ParcelStatus.FAILED);
            parcelRepository.save(parcel);

            Incident incident = Incident.builder()
                    .entityType(Incident.EntityType.PARCEL)
                    .entityId(parcelId)
                    .incidentType(Incident.IncidentType.DELIVERY_FAIL)
                    // No se registra el OTP esperado: quien lea el incidente no debe poder reutilizarlo
                    .description("Entrega fallida: OTP inválido para la encomienda " + parcel.getCode())
                    .createdAt(LocalDateTime.now())
                    .build();
            incidentRepository.save(incident);



            throw new BusinessException("OTP inválido. Se ha creado un incidente.", HttpStatus.BAD_REQUEST,
                    "INVALID_OTP");
        }

        parcel.setStatus(Parcel.ParcelStatus.DELIVERED);
        parcel.setProofPhotoUrl(photoUrl);
        parcel.setDeliveredAt(LocalDateTime.now());
        Parcel deliveredParcel = parcelRepository.save(parcel);



        return parcelMapper.toResponse(deliveredParcel);
    }

    // Obtiene todas las encomiendas en tránsito de un viaje específico
    @Override
    @Transactional(readOnly = true)
    public List<ParcelResponse> getParcelsInTransit(Long tripId) {
        List<Parcel> parcels = parcelRepository.findByTripIdAndStatus(tripId, Parcel.ParcelStatus.IN_TRANSIT);
        return parcelMapper.toResponseList(parcels);
    }

    // Obtiene encomiendas por rango de fechas
    @Override
    @Transactional(readOnly = true)
    public List<ParcelResponse> getParcelsByDateRange(LocalDate startDate, LocalDate endDate) {
        List<Parcel> parcels = parcelRepository.findByDateRange(startDate, endDate);
        return parcelMapper.toResponseList(parcels);
    }

    // Máquina de estados del documento: CREATED → IN_TRANSIT (abordo del bus) → DELIVERED (OTP + foto)
    // o FAILED (OTP incorrecto / destinatario ausente). DELIVERED y FAILED son finales: sin reintentos
    // no se puede probar el OTP por fuerza bruta
    private boolean isValidStatusTransition(Parcel.ParcelStatus current, Parcel.ParcelStatus target) {
        return switch (current) {
            case CREATED -> target == Parcel.ParcelStatus.IN_TRANSIT;
            case IN_TRANSIT -> target == Parcel.ParcelStatus.FAILED;
            case FAILED, DELIVERED -> false;
        };
    }
}
