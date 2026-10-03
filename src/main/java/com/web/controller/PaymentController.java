package com.web.controller;

import com.web.dto.payment.CashCloseRequest;
import com.web.dto.payment.CashCloseResponse;
import com.web.dto.payment.PaymentConfirmRequest;
import com.web.dto.payment.PaymentResponse;
import com.web.exception.BusinessException;
import com.web.repository.UserRepository;
import com.web.service.payment.PaymentService;
import com.web.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final UserRepository userRepository;

    // Confirma el pago de un ticket pendiente (QR/transferencia/contraentrega) y devuelve el comprobante
    @PostMapping("/payments/confirm")
    @PreAuthorize("hasAnyRole('CLERK', 'DRIVER')")
    public ResponseEntity<PaymentResponse> confirmPayment(@Valid @RequestBody PaymentConfirmRequest request) {
        PaymentResponse response = paymentService.confirmPayment(request, currentUserId());
        return ResponseEntity.ok(response);
    }

    // Comprobante digital del pago de un ticket
    @GetMapping("/tickets/{id}/receipt")
    @PreAuthorize("hasAnyRole('PASSENGER', 'CLERK', 'ADMIN', 'DISPATCHER')")
    public ResponseEntity<PaymentResponse> getReceipt(@PathVariable Long id) {
        return ResponseEntity.ok(paymentService.getReceipt(id, currentUserId()));
    }

    // Cierra la caja del día calculando efectivo esperado vs reportado
    @PostMapping("/cash/close")
    @PreAuthorize("hasAnyRole('CLERK', 'DRIVER')")
    public ResponseEntity<CashCloseResponse> closeCash(@Valid @RequestBody CashCloseRequest request) {
        CashCloseResponse response = paymentService.closeCash(request, currentUserId());
        return ResponseEntity.ok(response);
    }

    // Cierres de caja guardados: los propios (CLERK/DRIVER) o todos (ADMIN)
    @GetMapping("/cash/closes")
    @PreAuthorize("hasAnyRole('CLERK', 'DRIVER', 'ADMIN')")
    public ResponseEntity<List<CashCloseResponse>> getCashCloses(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(paymentService.getCashCloses(date, currentUserId()));
    }

    // Usuario del token; si ya no existe la petición no está autenticada (401)
    private Long currentUserId() {
        return SecurityUtils.currentUsername()
                .flatMap(userRepository::findByEmail)
                .orElseThrow(() -> new BusinessException("Usuario autenticado no encontrado",
                        HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND"))
                .getId();
    }
}
