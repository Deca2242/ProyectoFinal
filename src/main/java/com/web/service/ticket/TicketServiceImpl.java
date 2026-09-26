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

    // Compra un ticket validando disponibilidad, calculando precio con descuentos y generando QR
    @Override
    @Transactional
    public TicketResponse purchaseTicket(TicketCreateRequest request) {
        LocalDateTime now = LocalDateTime.now();

        // Validar que el viaje existe y está en estado SCHEDULED
        Trip trip = tripRepository.findById(request.tripId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", request.tripId()));

        if (trip.getStatus() != Trip.TripStatus.SCHEDULED) {
            throw new InvalidSegmentException("El viaje no está disponible para compra (estado: " + trip.getStatus() + ")");
        }

        User passenger = userRepository.findById(request.passengerId())
                .orElseThrow(() -> new ResourceNotFoundException("Pasajero", request.passengerId()));

        Stop fromStop = stopRepository.findById(request.fromStopId())
                .orElseThrow(() -> new ResourceNotFoundException("Parada de origen", request.fromStopId()));
        Stop toStop = stopRepository.findById(request.toStopId())
                .orElseThrow(() -> new ResourceNotFoundException("Parada de destino", request.toStopId()));

        validateSegment(trip, fromStop, toStop);

        Integer capacity = trip.getBus().getCapacity();
        if (request.seatNumber() < 1 || request.seatNumber() > capacity) {
            throw new SeatNotAvailableException(
                    "El asiento " + request.seatNumber() + " no existe en este bus (capacidad: " + capacity + ")");
        }

        // Verificar si hay una reserva temporal activa en este asiento
        Optional<SeatHold> activeHold = seatHoldRepository.findActiveHold(
                request.tripId(),
                request.seatNumber(),
                now
        );

        if (activeHold.isPresent() && !activeHold.get().getUser().getId().equals(passenger.getId())) {
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

        // Validar que no se exceda el límite de overbooking configurado
        validateOverbooking(trip);

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

        // Liberar la reserva temporal si el usuario tenía un hold activo
        if (activeHold.isPresent() && activeHold.get().getUser().getId().equals(passenger.getId())) {
            seatHoldService.releaseHold(activeHold.get().getId());

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

    // Marca tickets como NO_SHOW automáticamente cada 5 minutos si el viaje ya partió
    @Scheduled(cron = "0 */5 * * * *") // Cada 5 minutos
    @Transactional
    public void processNoShows() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime fiveMinutesFromNow = now.plusMinutes(5);
        
        List<Ticket> allTickets = ticketRepository.findAll();
        int noShowCount = 0;
        BigDecimal totalFees = BigDecimal.ZERO;
        
        for (Ticket ticket : allTickets) {
            if (ticket.getStatus() == Ticket.TicketStatus.SOLD &&
                ticket.getTrip().getDepartureTime().isBefore(fiveMinutesFromNow) &&
                ticket.getTrip().getDepartureTime().isAfter(now)) {
                
                // Marcar como NO_SHOW
                ticket.setStatus(Ticket.TicketStatus.NO_SHOW);
                ticketRepository.save(ticket);
                noShowCount++;
                
                // El asiento queda disponible automáticamente porque el ticket ya no está SOLD
                // (la validación isSeatAvailableForSegment solo cuenta tickets SOLD)
                
                // Cobrar fee configurable (se registra pero no se procesa automáticamente)
                BigDecimal noShowFee = configService.getNoShowFee();
                totalFees = totalFees.add(noShowFee);
            }
        }
        
        // Log para auditoría (el fee se cobrará en el proceso de cierre de caja o facturación)
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

    // Calcula el precio final aplicando tarifas dinámicas, multiplicadores y descuentos
    private BigDecimal calculateFinalPrice(Trip trip, Stop fromStop, Stop toStop, TicketCreateRequest request, String passengerType) {
        // Precio base: primero busca en FareRule, si no existe usa ConfigService
        BigDecimal basePrice = fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(
                trip.getRoute().getId(),
                fromStop.getId(),
                toStop.getId()
        ).map(fareRule -> fareRule.getBasePrice())
         .orElse(configService.getTicketBasePrice());

        // La demanda se mide sobre la ocupación de ESTE viaje, no sobre todos los viajes del día
        Long soldTickets = ticketRepository.countSoldSeats(trip.getId());
        int capacity = trip.getBus().getCapacity();
        double occupancyRate = (double) soldTickets / capacity;

        BigDecimal dynamicMultiplier = BigDecimal.ONE;
        if (occupancyRate > 0.8) {
            dynamicMultiplier = configService.getTicketPriceMultiplierHighDemand();
        } else if (occupancyRate > 0.6) {
            dynamicMultiplier = configService.getTicketPriceMultiplierMediumDemand();
        }

        LocalDateTime departureTime = trip.getDepartureTime();
        int hour = departureTime.getHour();
        if ((hour >= 6 && hour <= 9) || (hour >= 17 && hour <= 20)) {
            dynamicMultiplier = dynamicMultiplier.multiply(
                configService.getTicketPriceMultiplierPeakHours()
            );
        }

        BigDecimal priceWithMultipliers = basePrice.multiply(dynamicMultiplier);
        
        // Aplicar descuento según tipo de pasajero
        BigDecimal discountPercentage = getDiscountPercentage(passengerType);
        BigDecimal discountAmount = priceWithMultipliers
                .multiply(discountPercentage)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        
        BigDecimal finalPrice = priceWithMultipliers.subtract(discountAmount)
                .setScale(2, RoundingMode.HALF_UP);

        return finalPrice;
    }
    
    // Obtiene el porcentaje de descuento según el tipo de pasajero (STUDENT, SENIOR, etc.)
    private BigDecimal getDiscountPercentage(String passengerType) {
        if (passengerType == null || passengerType.isEmpty()) {
            return BigDecimal.ZERO; // Sin descuento para ADULT o tipo no especificado
        }
        
        // Obtener descuentos de ConfigService
        var config = configService.getConfig();
        Integer discount = config.discountPercentages().get(passengerType.toUpperCase());
        
        return discount != null ? BigDecimal.valueOf(discount) : BigDecimal.ZERO;
    }
    
    // Valida que no se exceda el límite de overbooking configurado para el viaje
    private void validateOverbooking(Trip trip) {
        // Contar asientos vendidos para este viaje específico
        Long soldSeats = ticketRepository.countSoldSeats(trip.getId());
        
        int capacity = trip.getBus().getCapacity();
        double occupancyRate = (double) soldSeats / capacity;
        double maxOverbookingRate = configService.getOverbookingMaxPercentage();
        
        // Calcular capacidad máxima permitida con overbooking
        double maxAllowedOccupancy = 1.0 + maxOverbookingRate;
        
        if (occupancyRate >= maxAllowedOccupancy) {
            throw new OverbookingNotAllowedException(
                    String.format("El viaje ha alcanzado el límite de overbooking permitido (%.1f%%). Ocupación actual: %.1f%%",
                            maxOverbookingRate * 100, occupancyRate * 100));
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

