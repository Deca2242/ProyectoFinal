package com.web.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

// Cierre de caja de un usuario (CLERK/DRIVER) para un día: solo uno por usuario y fecha
@Entity
@Table(name = "cash_closes", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "close_date"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashClose {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "close_date", nullable = false)
    private LocalDate closeDate;

    // Efectivo esperado = pagos en efectivo + exceso de equipaje - reembolsos en efectivo
    @Column(name = "expected_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal expectedAmount;

    @Column(name = "actual_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal actualAmount;

    // Positiva = sobrante, negativa = faltante
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal difference;

    // Total cobrado por método de pago (CASH, TRANSFER, QR, CARD)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "totals_by_method", columnDefinition = "jsonb")
    private Map<String, BigDecimal> totalsByMethod;

    @Column(name = "tickets_count", nullable = false)
    private Integer ticketsCount;

    @Column(name = "refunds_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundsTotal;

    @Column(name = "baggage_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal baggageTotal;

    @Column(length = 500)
    private String notes;

    @Column(name = "closed_at", nullable = false)
    private LocalDateTime closedAt;
}
