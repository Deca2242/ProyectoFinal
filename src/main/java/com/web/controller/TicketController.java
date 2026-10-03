package com.web.controller;

import com.web.dto.ticket.TicketCancelRequest;
import com.web.dto.ticket.TicketCancelResponse;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.reservations.SeatHoldRequest;
import com.web.dto.ticket.reservations.SeatHoldResponse;
import com.web.exception.BusinessException;
import com.web.repository.UserRepository;
import com.web.service.ticket.SeatHoldService;
import com.web.service.ticket.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;
    private final SeatHoldService seatHoldService;
    private final UserRepository userRepository;

    // HOLD del asiento
    @PostMapping("/trips/{tripId}/seats/{seatNumber}/hold")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SeatHoldResponse> holdSeat(
            @PathVariable Long tripId,
            @PathVariable Integer seatNumber,
            @RequestBody @Valid SeatHoldRequest request) {

        SeatHoldResponse response = seatHoldService.createHold(tripId, seatNumber, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Compra un ticket validando el tramo y disponibilidad
    @PostMapping("/trips/{tripId}/tickets")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TicketResponse> purchaseTicket(
            @PathVariable Long tripId,
            @RequestBody @Valid TicketCreateRequest request) {

        if (request.tripId() != null && !request.tripId().equals(tripId)) {
            throw new BusinessException(
                    "El tripId de la URL no coincide con el del body",
                    HttpStatus.BAD_REQUEST,
                    "TRIP_ID_MISMATCH");
        }

        TicketCreateRequest validatedRequest = new TicketCreateRequest(
                tripId,
                request.passengerId(),
                request.seatNumber(),
                request.fromStopId(),
                request.fromStopName(),
                request.fromStopOrder(),
                request.toStopId(),
                request.toStopName(),
                request.toStopOrder(),
                request.price(),
                request.paymentMethod(),
                request.baggage(),
                request.passengerType());

        TicketResponse response = ticketService.purchaseTicket(validatedRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Cancela un ticket aplicando política de reembolso
    @PostMapping("/tickets/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TicketCancelResponse> cancelTicket(
            @PathVariable Long id,
            @RequestBody(required = false) TicketCancelRequest request) {

        TicketCancelResponse response = ticketService.cancelTicket(id);
        return ResponseEntity.ok(response);
    }

    // Obtiene un ticket por su ID
    @GetMapping("/tickets/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TicketResponse> getTicketById(@PathVariable Long id) {
        TicketResponse response = ticketService.getTicketById(id);
        return ResponseEntity.ok(response);
    }

    // Obtiene todos los tickets del usuario autenticado
    @GetMapping("/tickets/my-tickets")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<TicketResponse>> getMyTickets() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        Long userId = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado: " + email))
                .getId();

        List<TicketResponse> response = ticketService.getUserTickets(userId);
        return ResponseEntity.ok(response);
    }

    // Obtiene un ticket por su código QR
    @GetMapping("/tickets/qr/{qrCode}")
    @PreAuthorize("hasAnyRole('DRIVER', 'DISPATCHER', 'CLERK')")
    public ResponseEntity<TicketResponse> getTicketByQrCode(@PathVariable String qrCode) {
        TicketResponse response = ticketService.getTicketByQrCode(qrCode);
        return ResponseEntity.ok(response);
    }

    // Registra el abordaje del pasajero validando su código QR
    @PostMapping("/tickets/qr/{qrCode}/board")
    @PreAuthorize("hasAnyRole('DRIVER', 'DISPATCHER')")
    public ResponseEntity<TicketResponse> boardTicket(@PathVariable String qrCode) {
        TicketResponse response = ticketService.boardTicket(qrCode);
        return ResponseEntity.ok(response);
    }
}
