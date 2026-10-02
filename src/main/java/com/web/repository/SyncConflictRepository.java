package com.web.repository;

import com.web.entity.SyncConflict;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SyncConflictRepository extends JpaRepository<SyncConflict, Long> {

    // Conflictos de un usuario (opcionalmente de un dispositivo), los más recientes primero
    @EntityGraph(attributePaths = "batch")
    List<SyncConflict> findByBatchUserIdOrderByCreatedAtDescIdDesc(Long userId);

    @EntityGraph(attributePaths = "batch")
    List<SyncConflict> findByBatchUserIdAndBatchDeviceIdOrderByCreatedAtDescIdDesc(Long userId, String deviceId);

    // Vista de ADMIN: todos los conflictos
    @EntityGraph(attributePaths = "batch")
    List<SyncConflict> findAllByOrderByCreatedAtDescIdDesc();

    @EntityGraph(attributePaths = "batch")
    List<SyncConflict> findByBatchDeviceIdOrderByCreatedAtDescIdDesc(String deviceId);
}
