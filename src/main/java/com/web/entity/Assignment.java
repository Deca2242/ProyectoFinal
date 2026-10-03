package com.web.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", unique = true, nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private User driver;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dispatcher_id")
    private User dispatcher;

    @Column(name = "checklist_ok", nullable = false)
    @Builder.Default
    private Boolean checklistOk = false;

    @Column(name = "soat_valid", nullable = false)
    @Builder.Default
    private Boolean soatValid = false;

    @Column(name = "revision_valid", nullable = false)
    @Builder.Default
    private Boolean revisionValid = false;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime assignedAt = LocalDateTime.now();

    // SOAT vigente el día del viaje: si el bus tiene fecha de vencimiento manda la fecha; si no, el booleano del checklist
    public boolean soatValidOnTripDate() {
        LocalDate expiresAt = trip != null && trip.getBus() != null ? trip.getBus().getSoatExpiresAt() : null;
        return expiresAt != null ? coversTripDate(expiresAt) : Boolean.TRUE.equals(soatValid);
    }

    // Revisión técnico-mecánica vigente el día del viaje (misma regla que el SOAT)
    public boolean reviewValidOnTripDate() {
        LocalDate expiresAt = trip != null && trip.getBus() != null ? trip.getBus().getTechnicalReviewExpiresAt() : null;
        return expiresAt != null ? coversTripDate(expiresAt) : Boolean.TRUE.equals(revisionValid);
    }

    // Documentos con fecha de vencimiento anterior al día del viaje (detalle del error CHECKLIST_EXPIRED)
    public List<String> expiredDocuments() {
        List<String> expired = new ArrayList<>();
        Bus bus = trip != null ? trip.getBus() : null;
        if (bus == null) {
            return expired;
        }
        if (bus.getSoatExpiresAt() != null && !coversTripDate(bus.getSoatExpiresAt())) {
            expired.add("SOAT vencido el " + bus.getSoatExpiresAt());
        }
        if (bus.getTechnicalReviewExpiresAt() != null && !coversTripDate(bus.getTechnicalReviewExpiresAt())) {
            expired.add("revisión técnico-mecánica vencida el " + bus.getTechnicalReviewExpiresAt());
        }
        return expired;
    }

    // Un documento que vence el mismo día del viaje sigue vigente ese día
    private boolean coversTripDate(LocalDate expiresAt) {
        return trip.getTripDate() == null || !expiresAt.isBefore(trip.getTripDate());
    }
}

