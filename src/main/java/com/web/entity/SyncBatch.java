package com.web.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// Lote de operaciones offline recibido de un dispositivo (auditoría de la sincronización)
@Entity
@Table(name = "sync_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncType type;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(nullable = false)
    private Integer total;

    @Column(nullable = false)
    private Integer synced;

    @Column(nullable = false)
    private Integer duplicates;

    @Column(nullable = false)
    private Integer conflicts;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SyncConflict> conflictItems = new ArrayList<>();

    public void addConflict(SyncConflict conflict) {
        conflict.setBatch(this);
        conflictItems.add(conflict);
    }

    public enum SyncType {
        TICKETS,
        BOARDINGS
    }
}
