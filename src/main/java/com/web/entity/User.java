package com.web.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 150, unique = true)
    private String email;

    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    // Último cambio de contraseña: los tokens emitidos antes dejan de ser válidos
    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    // Relaciones
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    private List<SeatHold> seatHolds;

    @OneToMany(mappedBy = "passenger", cascade = CascadeType.ALL)
    private List<Ticket> tickets;

    @OneToMany(mappedBy = "driver")
    private List<Assignment> driverAssignments;

    @OneToMany(mappedBy = "dispatcher")
    private List<Assignment> dispatcherAssignments;

    @OneToMany(mappedBy = "reportedBy")
    private List<Incident> reportedIncidents;

    public enum Role {
        PASSENGER,
        CLERK,
        DRIVER,
        DISPATCHER,
        ADMIN
    }

    public enum Status {
        ACTIVE,
        INACTIVE
    }


}
