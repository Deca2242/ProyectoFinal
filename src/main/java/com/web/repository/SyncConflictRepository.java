package com.web.repository;

import com.web.entity.SyncConflict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SyncConflictRepository extends JpaRepository<SyncConflict, Long> {

    // Conflictos abiertos (resolved = false) o ya revisados (resolved = true), los más recientes primero.
    // userId / deviceId NULL = sin filtro (el ADMIN ve los de todos los usuarios)
    @Query("""
                SELECT c FROM SyncConflict c
                JOIN FETCH c.batch b
                WHERE (:userId IS NULL OR b.user.id = :userId)
                AND (:deviceId IS NULL OR b.deviceId = :deviceId)
                AND ((:resolved = true AND c.resolvedAt IS NOT NULL) OR (:resolved = false AND c.resolvedAt IS NULL))
                ORDER BY c.createdAt DESC, c.id DESC
            """)
    List<SyncConflict> search(@Param("userId") Long userId,
                              @Param("deviceId") String deviceId,
                              @Param("resolved") boolean resolved);
}
