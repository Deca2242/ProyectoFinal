package com.web.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "baggage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Baggage {

    public static final String DEFAULT_COMPARTMENT = "MAIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", unique = true, nullable = false)
    private Ticket ticket;

    @Column(name = "weight_kg", nullable = false, precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "excess_fee", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal excessFee = BigDecimal.ZERO;

    @Column(name = "tag_code", unique = true, length = 50)
    private String tagCode;

    // Maletero del bus en el que se carga (p. ej. "A", "B"); por defecto el principal
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String compartment = DEFAULT_COMPARTMENT;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

