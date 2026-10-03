package com.web.service.baggage;

import com.web.dto.baggage.BaggageCreateRequest;
import com.web.dto.baggage.BaggageResponse;
import com.web.dto.baggage.mapper.BaggageMapper;
import com.web.entity.Baggage;
import com.web.entity.Ticket;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.BaggageRepository;
import com.web.repository.TicketRepository;
import com.web.service.admin.ConfigService;
import com.web.util.QrCodeGenerator;
import com.web.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class BaggageServiceImpl implements BaggageService {

    private final BaggageRepository baggageRepository;
    private final TicketRepository ticketRepository;
    private final ConfigService configService;
    private final QrCodeGenerator qrCodeGenerator;
    private final BaggageMapper baggageMapper;

    @Override
    @Transactional
    public Baggage registerForTicket(Ticket ticket, BaggageCreateRequest request) {
        if (request == null) {
            return null;
        }
        // Exceso de equipaje por encima del máximo absoluto: no se admite (400)
        double maxWeight = configService.getBaggageWeightMax();
        if (request.weightKg().compareTo(BigDecimal.valueOf(maxWeight)) > 0) {
            throw new BusinessException("El peso del equipaje (" + request.weightKg().stripTrailingZeros().toPlainString()
                    + " kg) excede el máximo permitido de " + maxWeight + " kg",
                    HttpStatus.BAD_REQUEST, "BAGGAGE_WEIGHT_EXCEEDED");
        }

        Baggage baggage = Baggage.builder()
                .ticket(ticket)
                .weightKg(request.weightKg())
                .excessFee(calculateExcessFee(request.weightKg()))
                .tagCode(qrCodeGenerator.generateBaggageTag())
                .compartment(normalizeCompartment(request.compartment()))
                .build();

        baggage = baggageRepository.save(baggage);
        ticket.setBaggage(baggage);  // Actualizar relación bidireccional
        return baggage;
    }

    @Override
    @Transactional
    public BaggageResponse addBaggage(Long ticketId, BaggageCreateRequest request) {
        Ticket ticket = findTicket(ticketId);

        if (ticket.getStatus() != Ticket.TicketStatus.SOLD) {
            throw new BusinessException("Solo se registra equipaje en tickets vendidos (estado: " + ticket.getStatus() + ")",
                    HttpStatus.CONFLICT, "TICKET_NOT_SOLD");
        }
        if (ticket.getBaggage() != null || baggageRepository.findByTicketId(ticketId).isPresent()) {
            throw new BusinessException("El ticket " + ticketId + " ya tiene equipaje registrado",
                    HttpStatus.CONFLICT, "BAGGAGE_ALREADY_REGISTERED");
        }

        return baggageMapper.toResponse(registerForTicket(ticket, request));
    }

    @Override
    @Transactional(readOnly = true)
    public BaggageResponse getBaggage(Long ticketId) {
        Ticket ticket = findTicket(ticketId);
        requireSelfIfPassenger(ticket.getPassenger());

        Baggage baggage = baggageRepository.findByTicketId(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Equipaje del ticket", ticketId));
        return baggageMapper.toResponse(baggage);
    }

    @Override
    @Transactional
    public void removeBaggage(Long ticketId) {
        Ticket ticket = findTicket(ticketId);
        Baggage baggage = baggageRepository.findByTicketId(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Equipaje del ticket", ticketId));

        // Una vez abordado, el equipaje ya va en el maletero
        if (ticket.getStatus() != Ticket.TicketStatus.SOLD || ticket.getBoardedAt() != null) {
            throw new BusinessException("El equipaje solo se puede retirar antes de abordar",
                    HttpStatus.CONFLICT, "PASSENGER_ALREADY_BOARDED");
        }

        ticket.setBaggage(null);
        baggageRepository.delete(baggage);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal calculateExcessFee(BigDecimal weightKg) {
        BigDecimal limit = BigDecimal.valueOf(configService.getBaggageWeightLimit());
        if (weightKg.compareTo(limit) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal excess = weightKg.subtract(limit);
        return configService.getExcessFeePerKg().multiply(excess).setScale(2, RoundingMode.HALF_UP);
    }

    private Ticket findTicket(Long ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));
    }

    // "a" → "A"; vacío o null → maletero principal
    private static String normalizeCompartment(String compartment) {
        if (compartment == null || compartment.isBlank()) {
            return Baggage.DEFAULT_COMPARTMENT;
        }
        return compartment.trim().toUpperCase(Locale.ROOT);
    }

    // Un pasajero solo consulta el equipaje de sus propios tickets
    private void requireSelfIfPassenger(User passenger) {
        if (!SecurityUtils.hasRole("PASSENGER")) {
            return;
        }
        String username = SecurityUtils.currentUsername().orElse("");
        if (passenger == null || !username.equalsIgnoreCase(passenger.getEmail())) {
            throw new BusinessException("Un pasajero solo puede consultar sus propios tickets",
                    HttpStatus.FORBIDDEN, "NOT_TICKET_OWNER");
        }
    }
}
