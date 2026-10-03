package com.web.service.user;

import com.web.dto.auth.User.PasswordChangeRequest;
import com.web.dto.auth.User.UserResponse;
import com.web.dto.auth.User.UserUpdateRequest;
import com.web.dto.auth.User.mapper.UserMapper;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.UserRepository;
import com.web.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;


@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    // Obtiene lista de todos los usuarios
    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> getAll() {
        List<User> users = userRepository.findAll();
        return userMapper.toResponseList(users);
    }

    // Obtiene un usuario por su ID
    @Override
    @Transactional(readOnly = true)
    public UserResponse getById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", id));
        return userMapper.toResponse(user);
    }

    // Obtiene un usuario por su email
    @Override
    @Transactional(readOnly = true)
    public UserResponse getByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario con email: " + email));
        return userMapper.toResponse(user);
    }

    // Actualiza la información de un usuario
    @Override
    @Transactional
    public UserResponse updateUser(Long id, UserUpdateRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", id));

        userMapper.updateEntityFromRequest(request, user);

        // El mapper no toca el hash: la nueva contraseña (opcional) se cifra aquí
        if (request.password() != null && !request.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            user.setPasswordChangedAt(LocalDateTime.now());
        }

        User updatedUser = userRepository.save(user);



        return userMapper.toResponse(updatedUser);
    }

    // Desactiva un usuario cambiando su estado a INACTIVE (soft delete)
    @Override
    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", id));

        ensureNotLastActiveAdmin(user);
        user.setStatus(User.Status.INACTIVE);
        userRepository.save(user);


    }

    // Obtiene todos los usuarios activos de un rol específico
    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> getUsersByRole(User.Role role) {
        List<User> users = userRepository.findByRoleAndStatus(role, User.Status.ACTIVE);
        return userMapper.toResponseList(users);
    }

    // Lista de usuarios con filtros opcionales (la respuesta nunca incluye el hash de la contraseña)
    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> getUsers(User.Role role, User.Status status) {
        List<User> users;
        if (role != null && status != null) {
            users = userRepository.findByRoleAndStatus(role, status);
        } else if (role != null) {
            users = userRepository.findByRole(role);
        } else if (status != null) {
            users = userRepository.findByStatus(status);
        } else {
            users = userRepository.findAll();
        }
        return userMapper.toResponseList(users.stream().sorted(Comparator.comparing(User::getId)).toList());
    }

    // Igual que getUsers(role, status) y, si viene "q", filtra por nombre, email o teléfono (sin distinguir mayúsculas)
    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> getUsers(User.Role role, User.Status status, String q) {
        if (q == null || q.isBlank()) {
            return getUsers(role, status);
        }
        String pattern = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return userMapper.toResponseList(userRepository.search(role, status, pattern));
    }

    // Activa o desactiva un usuario; el filtro JWT rechaza los tokens de usuarios inactivos
    @Override
    @Transactional
    public UserResponse updateStatus(Long id, User.Status status) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", id));

        if (status == User.Status.INACTIVE && isCurrentUser(user)) {
            throw new BusinessException("Un administrador no puede desactivarse a sí mismo",
                    HttpStatus.BAD_REQUEST, "CANNOT_DEACTIVATE_SELF");
        }
        if (status == User.Status.INACTIVE) {
            ensureNotLastActiveAdmin(user);
        }

        user.setStatus(status);
        return userMapper.toResponse(userRepository.save(user));
    }

    // Cambia el rol de un usuario; el nuevo rol aplica desde la siguiente petición (se lee de la BD)
    @Override
    @Transactional
    public UserResponse updateRole(Long id, User.Role role) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", id));

        // Evita que el administrador pierda su propio acceso de ADMIN
        if (isCurrentUser(user) && role != user.getRole()) {
            throw new BusinessException("Un administrador no puede cambiar su propio rol",
                    HttpStatus.BAD_REQUEST, "CANNOT_CHANGE_OWN_ROLE");
        }
        if (role != User.Role.ADMIN) {
            ensureNotLastActiveAdmin(user);
        }

        user.setRole(role);
        return userMapper.toResponse(userRepository.save(user));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser() {
        return userMapper.toResponse(currentUser());
    }

    // Cambio de contraseña del usuario autenticado verificando la actual
    @Override
    @Transactional
    public void changePassword(PasswordChangeRequest request) {
        User user = currentUser();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException("La contraseña actual no es correcta",
                    HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // Los tokens emitidos antes de este momento dejan de ser válidos (incluido el de esta petición)
        user.setPasswordChangedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    // Siempre debe quedar al menos un ADMIN activo: no se puede desactivar ni degradar al último,
    // aunque lo intente otro administrador (los ADMIN activos se bloquean para que dos cambios simultáneos no los dejen en cero)
    private void ensureNotLastActiveAdmin(User user) {
        if (user.getRole() == User.Role.ADMIN && user.getStatus() == User.Status.ACTIVE
                && userRepository.lockByRoleAndStatus(User.Role.ADMIN, User.Status.ACTIVE).size() <= 1) {
            throw new BusinessException("No se puede desactivar ni cambiar el rol del último administrador activo",
                    HttpStatus.CONFLICT, "LAST_ADMIN");
        }
    }

    private User currentUser() {
        String email = SecurityUtils.currentUsername()
                .orElseThrow(() -> new BusinessException("Se requiere un usuario autenticado",
                        HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario con email: " + email));
    }

    private boolean isCurrentUser(User user) {
        return SecurityUtils.currentUsername()
                .map(email -> email.equalsIgnoreCase(user.getEmail()))
                .orElse(false);
    }
}
