package com.web.repository;

import com.web.entity.CashClose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CashCloseRepository extends JpaRepository<CashClose, Long> {

    // Ya existe el cierre del usuario para ese día (solo se permite uno)
    boolean existsByUserIdAndCloseDate(Long userId, LocalDate closeDate);

    // Cierres propios (CLERK/DRIVER), por fecha o todos
    List<CashClose> findByUserIdAndCloseDate(Long userId, LocalDate closeDate);

    List<CashClose> findByUserIdOrderByCloseDateDesc(Long userId);

    // Cierres de todos los usuarios (ADMIN), por fecha o todos
    List<CashClose> findByCloseDateOrderByClosedAtAsc(LocalDate closeDate);

    List<CashClose> findAllByOrderByCloseDateDescClosedAtDesc();
}
