package com.web.service.ticket;

import com.web.dto.baggage.BaggageCreateRequest;
import com.web.dto.ticket.TicketCancelResponse;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.entity.*;
import com.web.exception.BusinessException;
import com.web.exception.InvalidSegmentException;
import com.web.exception.OverbookingNotAllowedException;
import com.web.exception.ResourceNotFoundException;
import com.web.exception.SeatNotAvailableException;
import com.web.repository.*;
import com.web.service.admin.ConfigService;
import com.web.util.QrCodeGenerator;
import com.web.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;


@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private final TicketRepository ticketRepository;
    private final TripRepository tripRepository;
    private final StopRepository stopRepository;
    private final UserRepository userRepository;
    private final FareRuleRepository fareRuleRepository;
    private final BaggageRepository baggageRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final TicketMapper ticketMapper;
    private final SeatHoldService seatHoldService;
    private final QrCodeGenerator qrCodeGenerator;
    private final ConfigService configService;
    private final AssignmentRepository assignmentRepository;

    // Compra un ticket validando disponibilidad, calculando precio con descuentos y generando QR
    @Override
    @Transactional
    public TicketResponse purchaseTicket(TicketCreateRequest request) {
        LocalDateTime now = LocalDateTime.now();

        // Bloquear el viaje hasta el fin de la transacción: dos compras simultáneas del mismo viaje
        // se atienden una tras otra, así la segunda ve el asiento ya vendido
        tripRepository.lockById(request.tripId());

        // Validar que el viaje existe y admite ventas (programado o en abordaje, y aún sin salir)
        Trip trip = tripRepository.findById(request.tripId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", request.tripId()));

        if (trip.getStatus() != Trip.TripStatus.SCHEDULED && trip.getStatus() != Trip.TripStatus.BOARDING) {
            throw new InvalidSegmentException("El viaje no está disponible para compra (estado: " + trip.getStatus() + ")");
        }

        if (!trip.getDepartureTime().isAfter(now)) {
            throw new BusinessException("El viaje ya salió", HttpStatus.BAD_REQUEST, "TRIP_ALREADY_DEPARTED");
        }

        User passenger = userRepository.findById(request.passengerId())
                .orElseThrow(() -> new ResourceNotFoundException("Pasajero", request.passengerId()));

        Stop fromStop = stopRepository.findById(request.fromStopId())
                .orElseThrow(() -> new ResourceNotFoundException("Parada de origen", request.fromStopId()));
        Stop toStop = stopRepository.findById(request.toStopId())
                .orElseThrow(() -> new ResourceNotFoundException("Parada de destino", request.toStopId()));

        validateSegment(trip, fromStop, toStop);

        // Verificar holds activos de este asiento que se solapen con el tramo
        List<SeatHold> overlappingHolds = seatHoldRepository.findOverlappingActiveHolds(
                request.tripId(),
                request.seatNumber(),
                fromStop.getOrder(),
                toStop.getOrder(),
                now
        );

        boolean heldByOtherUser = overlappingHolds.stream()
                .anyMatch(h -> !h.getUser().getId().equals(passenger.getId()));
        if (heldByOtherUser) {
            throw new SeatNotAvailableException(
                    "El asiento " + request.seatNumber() + " tiene un hold activo de otro usuario");
        }

        // Verificar disponibilidad del asiento para el tramo específico (puede estar ocupado en otros tramos)
        // La consulta compara el ORDEN de las paradas en la ruta, no sus IDs
        Boolean isSeatAvailable = ticketRepository.isSeatAvailableForSegment(
                request.tripId(),
                request.seatNumber(),
                fromStop.getOrder(),
                toStop.getOrder()
        );

        if (!isSeatAvailable) {
            throw new SeatNotAvailableException(
                    "El asiento " + request.seatNumber() + " no está disponible para el tramo seleccionado");
        }

        // El número de asiento debe existir; las sillas por encima de la capacidad (overbooking)
        // solo se venden si un DISPATCHER las aprobó para este viaje
        validateSeatNumber(trip, request.seatNumber());

        // Calcular precio final aplicando tarifas dinámicas y descuentos por tipo de pasajero
        BigDecimal finalPrice = calculateFinalPrice(trip, fromStop, toStop, request, request.passengerType());

        Ticket ticket = ticketMapper.toEntity(request);
        // Establecer las relaciones manualmente
        ticket.setTrip(trip);
        ticket.setPassenger(passenger);
        ticket.setFromStop(fromStop);
        ticket.setToStop(toStop);
        ticket.setPrice(finalPrice);
        ticket.setQrCode(qrCodeGenerator.generateTicketQr());
        ticket.setChannel(SecurityUtils.hasRole("CLERK") ? Ticket.SalesChannel.BOX_OFFICE : Ticket.SalesChannel.APP);
        ticket = ticketRepository.save(ticket);

        // Registrar equipaje si se solicitó, calculando cargo por exceso si supera el límite
        if (request.baggage() != null) {
            BaggageCreateRequest baggageReq = request.baggage();
            
            Baggage baggage = Baggage.builder()
                    .ticket(ticket)
                    .weightKg(baggageReq.weightKg())
                    .tagCode(qrCodeGenerator.generateBaggageTag())
                    .build();

            Double baggageWeightLimit = configService.getBaggageWeightLimit();
            double weightKgDouble = baggageReq.weightKg().doubleValue();
            if (weightKgDouble > baggageWeightLimit) {
                double excess = weightKgDouble - baggageWeightLimit;
                BigDecimal excessFeePerKg = configService.getExcessFeePerKg();
                BigDecimal excessFee = excessFeePerKg.multiply(BigDecimal.valueOf(excess));
                baggage.setExcessFee(excessFee.setScale(2, RoundingMode.HALF_UP));

            } else {
                baggage.setExcessFee(BigDecimal.ZERO);
            }

            baggage = baggageRepository.save(baggage);
            ticket.setBaggage(baggage);  // Actualizar relación bidireccional

        }

        // Marcar como vendidos los holds del pasajero sobre este asiento y tramo
        for (SeatHold hold : overlappingHolds) {
            seatHoldService.releaseHold(hold.getId());
        }

        return ticketMapper.toResponse(ticket);
    }

    // Cancela un ticket y calcula el reembolso según horas de antelación
    @Override
    @Transactional
    public TicketCancelResponse cancelTicket(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));

        if (ticket.getStatus() != Ticket.TicketStatus.SOLD) {
            throw new InvalidSegmentException("El ticket ya está cancelado o es no-show");
        }

        if (ticket.getBoardedAt() != null) {
            throw new BusinessException("No se puede cancelar un ticket de un pasajero que ya abordó",
                    HttpStatus.BAD_REQUEST, "TICKET_ALREADY_BOARDED");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime departureTime = ticket.getTrip().getDepartureTime();

        Trip.TripStatus tripStatus = ticket.getTrip().getStatus();
        if (!departureTime.isAfter(now)
                || tripStatus == Trip.TripStatus.DEPARTED
                || tripStatus == Trip.TripStatus.ARRIVED) {
            throw new BusinessException("No se puede cancelar un ticket de un viaje que ya salió",
                    HttpStatus.BAD_REQUEST, "TRIP_ALREADY_DEPARTED");
        }

        Duration timeUntilDeparture = Duration.between(now, departureTime);
        long hoursUntilDeparture = timeUntilDeparture.toHours();

        // Calcular reembolso según políticas de cancelación configuradas
        BigDecimal refundPercentage = calculateRefundPercentage(hoursUntilDeparture);
        BigDecimal refundAmount = ticket.getPrice()
                .multiply(refundPercentage)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        ticket.setStatus(Ticket.TicketStatus.CANCELLED);
        ticket.setRefundAmount(refundAmount);
        ticket.setCancelledAt(now);
        ticketRepository.save(ticket);



        return new TicketCancelResponse(
                ticketId,
                ticket.getStatus(),
                refundAmount,
                refundPercentage.intValue(),
                "Ticket cancelado exitosamente"
        );
    }

    // Obtiene un ticket por su ID
    @Override
    @Transactional(readOnly = true)
    public TicketResponse getTicketById(Long id) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", id));
        return ticketMapper.toResponse(ticket);
    }

    // Obtiene todos los tickets de un pasajero
    @Override
    @Transactional(readOnly = true)
    public List<TicketResponse> getUserTickets(Long userId) {
        List<Ticket> tickets = ticketRepository.findByPassengerId(userId);
        return ticketMapper.toResponseList(tickets);
    }

    // Busca un ticket por su código QR (para validación en abordaje)
    @Override
    @Transactional(readOnly = true)
    public TicketResponse getTicketByQrCode(String qrCode) {
        Ticket ticket = ticketRepository.findByQrCode(qrCode)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", qrCode));
        return ticketMapper.toResponse(ticket);
    }

    // Registra el abordaje de un pasajero al validar su QR
    @Override
    @Transactional
    public TicketResponse boardTicket(String qrCode) {
        Ticket ticket = ticketRepository.findByQrCode(qrCode)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", qrCode));

        Trip trip = ticket.getTrip();

        // Un conductor solo valida QR de los viajes que tiene asignados
        requireAssignedDriverIfDriver(trip.getId());

        // BOARDING en la parada de origen; DEPARTED para quienes suben en paradas intermedias
        Trip.TripStatus tripStatus = trip.getStatus();
        if (tripStatus != Trip.TripStatus.BOARDING && tripStatus != Trip.TripStatus.DEPARTED) {
            throw new BusinessException("El abordaje no está abierto para este viaje (estado: " + tripStatus + ")",
                    HttpStatus.BAD_REQUEST, "BOARDING_NOT_OPEN");
        }

        if (ticket.getStatus() == Ticket.TicketStatus.NO_SHOW && tripStatus == Trip.TripStatus.BOARDING) {
            // Llegó tarde pero el bus no ha salido: se restituye si la silla no se revendió en su tramo
            boolean seatStillFree = ticketRepository.isSeatAvailableForSegment(
                    trip.getId(), ticket.getSeatNumber(),
                    ticket.getFromStop().getOrder(), ticket.getToStop().getOrder());
            if (!seatStillFree) {
                throw new SeatNotAvailableException(
                        "La silla " + ticket.getSeatNumber() + " ya fue revendida tras el no-show");
            }
            ticket.setStatus(Ticket.TicketStatus.SOLD);
            ticket.setNoShowFee(null);
        }

        if (ticket.getStatus() != Ticket.TicketStatus.SOLD) {
            throw new BusinessException("El ticket no es válido para abordar (estado: " + ticket.getStatus() + ")",
                    HttpStatus.BAD_REQUEST, "TICKET_NOT_VALID");
        }

        if (ticket.getBoardedAt() != null) {
            throw new BusinessException("El pasajero ya abordó con este ticket",
                    HttpStatus.CONFLICT, "TICKET_ALREADY_BOARDED");
        }

        ticket.setBoardedAt(LocalDateTime.now());
        ticket = ticketRepository.save(ticket);

        return ticketMapper.toResponse(ticket);
    }

    // Marca como NO_SHOW, cada 5 minutos, los tickets de pasajeros que no han abordado cuando faltan
    // 5 minutos o menos para la salida de un viaje en abordaje (solo quienes suben en la parada de origen)
    @Scheduled(cron = "0 */5 * * * *")
    @Transactional
    public void processNoShows() {
        LocalDateTime now = LocalDateTime.now();

        List<Ticket> noShows = ticketRepository.findUnboardedTicketsDepartingBetween(now, now.plusMinutes(5));

        // El asiento queda disponible porque la disponibilidad solo cuenta tickets SOLD.
        // Se registra el fee configurable de no-show en cada ticket
        BigDecimal noShowFee = noShows.isEmpty() ? BigDecimal.ZERO : configService.getNoShowFee();
        for (Ticket ticket : noShows) {
            ticket.setStatus(Ticket.TicketStatus.NO_SHOW);
            ticket.setNoShowFee(noShowFee);
            ticketRepository.save(ticket);
        }
    }

    // Valida que las paradas pertenezcan a la ruta y que el orden sea correcto
    private void validateSegment(Trip trip, Stop fromStop, Stop toStop) {
        if (!fromStop.getRoute().getId().equals(trip.getRoute().getId()) ||
            !toStop.getRoute().getId().equals(trip.getRoute().getId())) {
            throw new InvalidSegmentException("Las paradas no pertenecen a la ruta del viaje");
        }

        if (fromStop.getOrder() >= toStop.getOrder()) {
            throw new InvalidSegmentException("La parada de origen debe ser anterior a la de destino");
        }
    }

    // Calcula el precio final: tarifa del tramo (FareRule) o precio base, multiplicadores dinámicos
    // (solo si la regla los tiene activos o no hay regla) y descuento por tipo de pasajero
    private BigDecimal calculateFinalPrice(Trip trip, Stop fromStop, Stop toStop, TicketCreateRequest request, String passengerType) {
        Optional<FareRule> fareRule = fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(
                trip.getRoute().getId(),
                fromStop.getId(),
                toStop.getId()
        );

        BigDecimal basePrice = fareRule.map(FareRule::getBasePrice)
                .orElseGet(configService::getTicketBasePrice);
        boolean dynamicPricing = fareRule.map(rule -> Boolean.TRUE.equals(rule.getDynamicPricingEnabled()))
                .orElse(true);

        BigDecimal dynamicMultiplier = BigDecimal.ONE;
        if (dynamicPricing) {
            // La demanda se mide sobre la ocupación del TRAMO solicitado, no de todo el viaje
            Long soldSeats = ticketRepository.countSoldSeatsForSegment(
                    trip.getId(), fromStop.getOrder(), toStop.getOrder());
            int capacity = trip.getBus().getCapacity();
            double occupancyRate = capacity > 0 ? (double) soldSeats / capacity : 0.0;

            if (occupancyRate > 0.8) {
                dynamicMultiplier = configService.getTicketPriceMultiplierHighDemand();
            } else if (occupancyRate > 0.6) {
                dynamicMultiplier = configService.getTicketPriceMultiplierMediumDemand();
            }

            int hour = trip.getDepartureTime().getHour();
            if ((hour >= 6 && hour <= 9) || (hour >= 17 && hour <= 20)) {
                dynamicMultiplier = dynamicMultiplier.multiply(
                    configService.getTicketPriceMultiplierPeakHours()
                );
            }
        }

        BigDecimal priceWithMultipliers = basePrice.multiply(dynamicMultiplier);

        // Aplicar descuento según tipo de pasajero (la regla de tarifa puede definir los suyos)
        BigDecimal discountPercentage = getDiscountPercentage(passengerType, fareRule.orElse(null));
        BigDecimal discountAmount = priceWithMultipliers
                .multiply(discountPercentage)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        BigDecimal finalPrice = priceWithMultipliers.subtract(discountAmount).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        return finalPrice;
    }

    // Porcentaje de descuento del tipo de pasajero (ADULT, STUDENT, SENIOR, CHILD).
    // Un tipo desconocido se rechaza en vez de venderse sin descuento
    private BigDecimal getDiscountPercentage(String passengerType, FareRule fareRule) {
        if (passengerType == null || passengerType.isBlank() || "ADULT".equalsIgnoreCase(passengerType.trim())) {
            return BigDecimal.ZERO;
        }
        String type = passengerType.trim().toUpperCase(Locale.ROOT);

        Map<String, Integer> configDiscounts = configService.getConfig().discountPercentages();
        Integer discount = configDiscounts.get(type);

        if (fareRule != null && fareRule.getDiscounts() != null) {
            Object ruleDiscount = fareRule.getDiscounts().entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(type))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            if (ruleDiscount instanceof Number number) {
                discount = number.intValue();
            } else if (ruleDiscount != null) {
                try {
                    discount = new BigDecimal(ruleDiscount.toString()).intValue();
                } catch (NumberFormatException ignored) {
                    // Valor inválido en la regla: se usa el descuento de la configuración
                }
            }
        }

        if (discount == null) {
            throw new BusinessException("Tipo de pasajero no válido: " + passengerType
                    + " (válidos: ADULT, " + String.join(", ", configDiscounts.keySet()) + ")",
                    HttpStatus.BAD_REQUEST, "INVALID_PASSENGER_TYPE");
        }

        return BigDecimal.valueOf(Math.max(0, Math.min(100, discount)));
    }

    // Valida el número de silla: 1..capacidad son sillas físicas; por encima de la capacidad solo
    // se venden las sillas de overbooking aprobadas por un DISPATCHER para este viaje
    private void validateSeatNumber(Trip trip, Integer seatNumber) {
        int capacity = trip.getBus().getCapacity();
        if (seatNumber < 1) {
            throw new SeatNotAvailableException(
                    "El asiento " + seatNumber + " no existe en este bus (capacidad: " + capacity + ")");
        }
        if (seatNumber > capacity) {
            int approved = trip.getOverbookingApprovedSeats() == null ? 0 : trip.getOverbookingApprovedSeats();
            if (seatNumber > capacity + approved) {
                throw new OverbookingNotAllowedException(
                        "La silla " + seatNumber + " supera la capacidad del bus (" + capacity
                                + ") y requiere aprobación de overbooking del DISPATCHER (aprobadas: " + approved + ")");
            }
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

    // Calcula el porcentaje de reembolso según las horas restantes hasta la salida
    private BigDecimal calculateRefundPercentage(long hoursUntilDeparture) {
        if (hoursUntilDeparture >= 48) {
            return configService.getRefundPercentage48Hours();
        } else if (hoursUntilDeparture >= 24) {
            return configService.getRefundPercentage24Hours();
        } else if (hoursUntilDeparture >= 12) {
            return configService.getRefundPercentage12Hours();
        } else if (hoursUntilDeparture >= 6) {
            return configService.getRefundPercentage6Hours();
        } else {
            return configService.getRefundPercentageLess6Hours();
        }
    }
}

