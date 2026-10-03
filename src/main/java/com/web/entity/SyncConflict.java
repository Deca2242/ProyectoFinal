package com.web.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// Venta o abordaje offline rechazado al sincronizar
@Entity
@Table(name = "sync_conflicts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncConflict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private SyncBatch batch;

    // Id de la venta offline, o el QR en un abordaje
    @Column(name = "offline_client_id", length = 255)
    private String offlineClientId;

    @Column(length = 50)
    private String code;

    @Column(columnDefinition = "text")
    private String reason;

    // Operación original enviada por el dispositivo (JSON)
    @Column(columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    // Revisión del conflicto (NULL = abierto)
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;
}
