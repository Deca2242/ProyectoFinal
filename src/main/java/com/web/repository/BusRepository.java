package com.web.repository;

import com.web.entity.Bus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BusRepository extends JpaRepository<Bus, Long> {

    // Buscar bus por placa
    Optional<Bus> findByPlate(String plate);

    // Buscar buses por estado
    List<Bus> findByStatus(Bus.BusStatus status);

    // Obtener bus con sus asientos
    @Query("""
                SELECT b FROM Bus b
                LEFT JOIN FETCH b.seats
                WHERE b.id = :busId
            """)
    Optional<Bus> findByIdWithSeats(@Param("busId") Long busId);

    // Buscar buses con capacidad mínima
    @Query("""
                SELECT b FROM Bus b
                WHERE b.status = 'ACTIVE'
                AND b.capacity >= :minCapacity
                ORDER BY b.capacity ASC
            """)
    List<Bus> findByMinimumCapacity(@Param("minCapacity") Integer minCapacity);
}