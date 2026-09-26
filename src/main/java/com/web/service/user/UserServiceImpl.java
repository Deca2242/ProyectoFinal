package com.web.service.user;

import com.web.dto.auth.User.UserResponse;
import com.web.dto.auth.User.UserUpdateRequest;
import com.web.dto.auth.User.mapper.UserMapper;
import com.web.entity.User;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


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
}
