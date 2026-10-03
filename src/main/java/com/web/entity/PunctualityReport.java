package com.web.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

// Puntualidad diaria por ruta, persistida por el job nocturno (un registro por fecha y ruta)
@Entity
@Table(name = "punctuality_reports",
        uniqueConstraints = @UniqueConstraint(name = "uk_punctuality_reports_date_route",
                columnNames = {"report_date", "route_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PunctualityReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    // Viajes de la ruta que salieron ese día
    @Column(nullable = false)
    private Integer trips;

    @Column(name = "on_time_departure_pct")
    private Double onTimeDeparturePct;

    @Column(name = "on_time_arrival_pct")
    private Double onTimeArrivalPct;

    @Column(name = "avg_departure_delay_min")
    private Double avgDepartureDelayMin;

    @Column(name = "avg_arrival_delay_min")
    private Double avgArrivalDelayMin;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
