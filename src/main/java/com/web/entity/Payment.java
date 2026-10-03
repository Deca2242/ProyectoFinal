package com.web.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Comprobante digital de un cobro: uno por ticket (venta en taquilla, confirmación de QR/transferencia o contraentrega)
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", unique = true, nullable = false)
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Ticket.PaymentMethod method;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    // Referencia de la transacción (obligatoria para QR, transferencia y tarjeta)
    @Column(name = "transaction_reference", length = 100)
    private String transactionReference;

    @Column(name = "proof_image_url", length = 500)
    private String proofImageUrl;

    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;

    // Quien recibió el dinero (taquillero o conductor); su cierre de caja incluye este pago
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_by_id")
    private User confirmedBy;
}
