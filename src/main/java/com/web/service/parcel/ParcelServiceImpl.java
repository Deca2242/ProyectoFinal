package com.web.service.parcel;

import com.web.dto.parcel.ParcelCreateRequest;
import com.web.dto.parcel.ParcelResponse;
import com.web.dto.parcel.mapper.ParcelMapper;
import com.web.entity.Incident;
import com.web.entity.Parcel;
import com.web.entity.Stop;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.InvalidSegmentException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.AssignmentRepository;
import com.web.repository.IncidentRepository;
import com.web.repository.ParcelRepository;
import com.web.repository.StopRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import com.web.service.admin.ConfigService;
import com.web.service.notification.NotificationService;
import com.web.util.OtpGenerator;
import com.web.util.QrCodeGenerator;
import com.web.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
    private final AssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final ParcelMapper parcelMapper;
    private final QrCodeGenerator qrCodeGenerator;
    private final OtpGenerator otpGenerator;
    private final ConfigService configService;
    private final NotificationService notificationService;


    // Listado con filtros opcionales, más recientes primero
    @Override
    @Transactional(readOnly = true)
    public List<ParcelResponse> searchParcels(LocalDate from, LocalDate to, Parcel.ParcelStatus status) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException("La fecha inicial no puede ser posterior a la final",
                    HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE");
        }

        Specification<Parcel> spec = (root, query, cb) -> cb.conjunction();
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("trip").get("tripDate"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("trip").get("tripDate"), to));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }

        List<Parcel> parcels = parcelRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));
        return parcelMapper.toResponseList(parcels);
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

        requireStopsOnTripRoute(trip, fromStop, toStop);

        String code = qrCodeGenerator.generateParcelCode();
        String deliveryOtp = otpGenerator.generate6DigitOtp();

        Parcel parcel = parcelMapper.toEntity(request);
        // Establecer las relaciones manualmente
        parcel.setTrip(trip);
        parcel.setFromStop(fromStop);
        parcel.setToStop(toStop);
        parcel.setCode(code);
        // Solo se guarda el hash: el OTP en claro no queda en la base de datos
        parcel.setDeliveryOtp(otpGenerator.hashOtp(code, deliveryOtp));
        parcel.setOtpAttempts(0);

        Parcel savedParcel = parcelRepository.save(parcel);

        // Aviso simulado: OTP al destinatario y código de rastreo al remitente; un fallo no afecta el registro
        notificationService.notifyParcelCreated(savedParcel, deliveryOtp);

        // La taquilla recibe el OTP para entregárselo al destinatario; ninguna otra respuesta lo incluye
        return parcelMapper.toResponseWithOtp(savedParcel, deliveryOtp);
    }


    // Rastrea una encomienda por su código de rastreo
    @Override
    @Transactional(readOnly = true)
    public ParcelResponse trackParcel(String code) {
        Parcel parcel = findByCode(code);
        // El rastreo es público: no se devuelve el OTP de entrega
        return parcelMapper.toPublicResponse(parcel);
    }

    // Actualiza el estado de una encomienda. DELIVERED delega en deliverWithOtp (mismo resultado que /deliver).
    // noRollbackFor: la entrega con OTP inválido guarda intentos/FAILED/incidente antes de lanzar el error
    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public ParcelResponse updateStatus(String code, Parcel.ParcelStatus status, String otp, String photoUrl) {
        if (status == Parcel.ParcelStatus.DELIVERED) {
            if (isBlank(otp) || isBlank(photoUrl)) {
                throw new BusinessException("La entrega debe registrarse con OTP y foto de prueba",
                        HttpStatus.BAD_REQUEST, "DELIVERY_REQUIRES_OTP");
            }
            return deliverWithOtp(code, otp, photoUrl);
        }

        Parcel parcel = findByCode(code);
        if (!isValidStatusTransition(parcel.getStatus(), status)) {
            throw new InvalidStateTransitionException(parcel.getStatus().name(), status.name());
        }

        if (status == Parcel.ParcelStatus.IN_TRANSIT) {
            // Se carga en el bus durante el abordaje o ya en ruta, y si es un conductor, en su propio viaje
            Trip trip = parcel.getTrip();
            if (trip.getStatus() != Trip.TripStatus.BOARDING && trip.getStatus() != Trip.TripStatus.DEPARTED) {
                throw new InvalidStateTransitionException("La encomienda solo pasa a IN_TRANSIT con el viaje en "
                        + "abordaje o en ruta (estado del viaje: " + trip.getStatus() + ")");
            }
            requireAssignedDriverIfDriver(trip.getId());
        }

        parcel.setStatus(status);
        Parcel updatedParcel = parcelRepository.save(parcel);

        // Entrega fallida reportada a mano (destinatario ausente, dirección errada...): queda el incidente
        if (status == Parcel.ParcelStatus.FAILED) {
            saveDeliveryFailIncident(parcel, "Entrega fallida reportada manualmente para la encomienda "
                    + parcel.getCode());
        }

        return parcelMapper.toResponse(updatedParcel);
    }

    // Entrega una encomienda validando el OTP y guardando foto de prueba.
    // OTP incorrecto: suma un intento; al llegar a "parcel.otp.max.attempts" pasa a FAILED con incidente.
    // noRollbackFor: el intento, el estado FAILED y el incidente se guardan aunque luego se lance el error
    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public ParcelResponse deliverWithOtp(String code, String otp, String photoUrl) {
        Parcel parcel = findByCode(code);

        if (parcel.getStatus() != Parcel.ParcelStatus.IN_TRANSIT) {
            throw new InvalidStateTransitionException(parcel.getStatus().name(), Parcel.ParcelStatus.DELIVERED.name());
        }
        if (isBlank(photoUrl)) {
            throw new BusinessException("La entrega requiere OTP y foto de prueba", HttpStatus.BAD_REQUEST,
                    "MISSING_DELIVERY_PROOF");
        }

        if (!otpGenerator.matchesOtp(parcel.getCode(), otp, parcel.getDeliveryOtp())) {
            int attempts = (parcel.getOtpAttempts() == null ? 0 : parcel.getOtpAttempts()) + 1;
            int maxAttempts = configService.getParcelOtpMaxAttempts();
            parcel.setOtpAttempts(attempts);

            if (attempts >= maxAttempts) {
                parcel.setStatus(Parcel.ParcelStatus.FAILED);
                parcelRepository.save(parcel);
                // No se registra el OTP: quien lea el incidente no debe poder reutilizarlo
                saveDeliveryFailIncident(parcel, "Entrega fallida: OTP inválido " + attempts
                        + " veces para la encomienda " + parcel.getCode());
                throw new BusinessException("OTP inválido. Se agotaron los " + maxAttempts
                        + " intentos: la encomienda quedó FAILED y se ha creado un incidente.",
                        HttpStatus.BAD_REQUEST, "INVALID_OTP");
            }

            parcelRepository.save(parcel);
            throw new BusinessException("OTP inválido. Intentos restantes: " + (maxAttempts - attempts),
                    HttpStatus.BAD_REQUEST, "INVALID_OTP");
        }

        parcel.setStatus(Parcel.ParcelStatus.DELIVERED);
        parcel.setProofPhotoUrl(photoUrl);
        parcel.setDeliveredAt(LocalDateTime.now());
        Parcel deliveredParcel = parcelRepository.save(parcel);

        return parcelMapper.toResponse(deliveredParcel);
    }

    // Reabre una encomienda FAILED. En el mismo viaje vuelve a IN_TRANSIT (p. ej. espera en el destino un
    // nuevo intento de entrega); si se reasigna a otro viaje que aún no aborda, vuelve a CREATED
    @Override
    @Transactional
    public ParcelResponse reopenParcel(String code, Long tripId) {
        Parcel parcel = findByCode(code);
        if (parcel.getStatus() != Parcel.ParcelStatus.FAILED) {
            throw new InvalidStateTransitionException(parcel.getStatus().name(), Parcel.ParcelStatus.IN_TRANSIT.name());
        }

        Trip target = parcel.getTrip();
        boolean reassigned = tripId != null && !tripId.equals(target.getId());
        if (reassigned) {
            target = tripRepository.findById(tripId)
                    .orElseThrow(() -> new ResourceNotFoundException("Viaje", tripId));
            requireStopsOnTripRoute(target, parcel.getFromStop(), parcel.getToStop());
            if (target.getStatus() == Trip.TripStatus.ARRIVED) {
                throw new InvalidStateTransitionException("No se puede reasignar la encomienda a un viaje que ya llegó");
            }
        }
        if (target.getStatus() == Trip.TripStatus.CANCELLED) {
            throw new InvalidStateTransitionException("El viaje " + target.getId()
                    + " está cancelado: indique otro viaje (tripId) para reasignar la encomienda");
        }

        parcel.setTrip(target);
        parcel.setOtpAttempts(0);
        parcel.setStatus(target.getStatus() == Trip.TripStatus.SCHEDULED
                ? Parcel.ParcelStatus.CREATED
                : Parcel.ParcelStatus.IN_TRANSIT);

        return parcelMapper.toResponse(parcelRepository.save(parcel));
    }

    // Encomiendas de un viaje, opcionalmente filtradas por estado
    @Override
    @Transactional(readOnly = true)
    public List<ParcelResponse> getTripParcels(Long tripId, Parcel.ParcelStatus status) {
        if (!tripRepository.existsById(tripId)) {
            throw new ResourceNotFoundException("Viaje", tripId);
        }
        requireAssignedDriverIfDriver(tripId);

        List<Parcel> parcels = status == null
                ? parcelRepository.findByTripId(tripId)
                : parcelRepository.findByTripIdAndStatus(tripId, status);
        return parcelMapper.toResponseList(parcels);
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

    private Parcel findByCode(String code) {
        return parcelRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Encomienda con código: " + code));
    }

    // Las paradas deben pertenecer a la ruta del viaje y respetar el sentido del recorrido
    private static void requireStopsOnTripRoute(Trip trip, Stop fromStop, Stop toStop) {
        Long routeId = trip.getRoute().getId();
        if (!fromStop.getRoute().getId().equals(routeId) || !toStop.getRoute().getId().equals(routeId)) {
            throw new InvalidSegmentException("Las paradas no pertenecen a la ruta del viaje");
        }
        if (fromStop.getOrder() >= toStop.getOrder()) {
            throw new InvalidSegmentException("La parada de origen debe ser anterior a la de destino");
        }
    }

    // Si quien llama es un DRIVER, debe ser el conductor asignado al viaje
    private void requireAssignedDriverIfDriver(Long tripId) {
        if (!SecurityUtils.hasRole("DRIVER")) {
            return;
        }
        String username = SecurityUtils.currentUsername().orElse("");
        boolean assigned = assignmentRepository.findByTripId(tripId)
                .map(a -> a.getDriver() != null && username.equalsIgnoreCase(a.getDriver().getEmail()))
                .orElse(false);
        if (!assigned) {
            throw new BusinessException("El conductor no está asignado a este viaje",
                    HttpStatus.FORBIDDEN, "DRIVER_NOT_ASSIGNED");
        }
    }

    // Incidente de entrega fallida a nombre de quien hace la operación (si hay usuario autenticado)
    private void saveDeliveryFailIncident(Parcel parcel, String description) {
        User reporter = SecurityUtils.currentUsername().flatMap(userRepository::findByEmail).orElse(null);
        incidentRepository.save(Incident.builder()
                .entityType(Incident.EntityType.PARCEL)
                .entityId(parcel.getId())
                .incidentType(Incident.IncidentType.DELIVERY_FAIL)
                .description(description)
                .reportedBy(reporter)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // Máquina de estados del documento: CREATED → IN_TRANSIT (abordo del bus) → DELIVERED (OTP + foto)
    // o FAILED (OTP agotado / destinatario ausente). DELIVERED es final; FAILED solo se reabre con
    // reopenParcel (DISPATCHER/ADMIN), así el conductor no puede reintentar el OTP indefinidamente
    private boolean isValidStatusTransition(Parcel.ParcelStatus current, Parcel.ParcelStatus target) {
        return switch (current) {
            case CREATED -> target == Parcel.ParcelStatus.IN_TRANSIT;
            case IN_TRANSIT -> target == Parcel.ParcelStatus.FAILED;
            case FAILED, DELIVERED -> false;
        };
    }
}
