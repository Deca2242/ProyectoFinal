package com.web.repository;

import com.web.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {

    // Autenticación - Login/Registro
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    // Usuario con un teléfono dado (notificaciones de encomiendas a remitente/destinatario)
    Optional<User> findFirstByPhone(String phone);

    // Buscar usuarios por rol
    List<User> findByRole(User.Role role);

    // Buscar usuarios por estado
    List<User> findByStatus(User.Status status);

    // Buscar usuarios por rol y estado
    List<User> findByRoleAndStatus(User.Role role, User.Status status);

    // Buscar conductores activos disponibles para asignación
    @Query("""
        SELECT u FROM User u
        WHERE u.role = 'DRIVER'
        AND u.status = 'ACTIVE'
    """)
    List<User> findAvailableDrivers();

    // Buscar despachadores activos
    @Query("""
        SELECT u FROM User u
        WHERE u.role = 'DISPATCHER'
        AND u.status = 'ACTIVE'
    """)
    List<User> findAvailableDispatchers();

    // Buscar empleados que manejan efectivo para cierre de caja (CLERK y DRIVER)
    @Query("""
        SELECT u FROM User u
        WHERE u.role IN ('CLERK', 'DRIVER')
        AND u.status = 'ACTIVE'
    """)
    List<User> findCashHandlers();
}
