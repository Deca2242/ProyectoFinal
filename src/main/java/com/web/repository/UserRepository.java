package com.web.repository;

import com.web.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {

    // Autenticación - Login/Registro
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    // Buscar usuarios por rol
    List<User> findByRole(User.Role role);

    // Buscar usuarios por estado
    List<User> findByStatus(User.Status status);

    // Buscar usuarios por rol y estado
    List<User> findByRoleAndStatus(User.Role role, User.Status status);

    // Usuarios de un rol y estado bloqueados para escritura (SELECT ... FOR UPDATE): dos administradores que se
    // desactivan a la vez se serializan y el segundo ve que solo queda uno (nunca pueden quedar cero ADMIN activos)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT u FROM User u
        WHERE u.role = :role
        AND u.status = :status
        ORDER BY u.id
    """)
    List<User> lockByRoleAndStatus(@Param("role") User.Role role, @Param("status") User.Status status);

    // Gestión de usuarios: filtros opcionales por rol y estado + texto en nombre, email o teléfono
    // (el patrón llega ya en minúsculas y con comodines)
    @Query("""
        SELECT u FROM User u
        WHERE (:role IS NULL OR u.role = :role)
        AND (:status IS NULL OR u.status = :status)
        AND (LOWER(u.name) LIKE :pattern OR LOWER(u.email) LIKE :pattern OR LOWER(u.phone) LIKE :pattern)
        ORDER BY u.id
    """)
    List<User> search(@Param("role") User.Role role,
                      @Param("status") User.Status status,
                      @Param("pattern") String pattern);

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
