package com.web.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "passenger_id", nullable = false)
    private User passenger;

    @Column(name = "seat_number", nullable = false)
    private Integer seatNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_stop_id", nullable = false)
    private Stop fromStop;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_stop_id", nullable = false)
    private Stop toStop;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TicketStatus status = TicketStatus.SOLD;

    @Column(name = "qr_code", unique = true, length = 255)
    private String qrCode;

    @Column(name = "purchased_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime purchasedAt = LocalDateTime.now();

    // Momento en que el pasajero abordó (validación del QR); null si aún no aborda
    @Column(name = "boarded_at")
    private LocalDateTime boardedAt;

    // Reembolso entregado al cancelar y momento de la cancelación (cierre de caja)
    @Column(name = "refund_amount", precision = 10, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    // Fee cobrado cuando el pasajero no aborda (regla de no-show)
    @Column(name = "no_show_fee", precision = 10, scale = 2)
    private BigDecimal noShowFee;

    // Usuario que registró la venta (cierre de caja por cajero)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sold_by_id")
    private User soldBy;

    // Canal de venta: taquilla o app (métricas de ventas por canal)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SalesChannel channel = SalesChannel.APP;

    // Relación one-to-one con Baggage
    @OneToOne(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    private Baggage baggage;

    public enum PaymentMethod {
        CASH,
        TRANSFER,
        QR,
        CARD
    }

    public enum SalesChannel {
        APP,
        BOX_OFFICE
    }

    public enum TicketStatus {
        SOLD,
        CANCELLED,
        NO_SHOW
    }
}

