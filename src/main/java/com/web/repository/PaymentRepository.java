package com.web.repository;

import com.web.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // Comprobante de un ticket (hay como máximo uno)
    Optional<Payment> findByTicketId(Long ticketId);

    // Pagos que recibió un usuario en el rango (cierre de caja), en cualquier estado del ticket:
    // el dinero se recibió al cobrar; los reembolsos se restan aparte
    @Query("""
                SELECT p FROM Payment p
                JOIN FETCH p.ticket t
                LEFT JOIN FETCH t.baggage
                WHERE p.confirmedBy.id = :userId
                AND p.paidAt >= :start
                AND p.paidAt < :end
                ORDER BY p.paidAt
            """)
    List<Payment> findReceivedByUserBetween(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // Pagos en efectivo de un usuario cuyos tickets se cancelaron en el rango (el reembolso sale de su caja)
    @Query("""
                SELECT p FROM Payment p
                JOIN FETCH p.ticket t
                WHERE p.confirmedBy.id = :userId
                AND p.method = 'CASH'
                AND t.status = 'CANCELLED'
                AND t.cancelledAt >= :start
                AND t.cancelledAt < :end
            """)
    List<Payment> findCashRefundedByUserBetween(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
