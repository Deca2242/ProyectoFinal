package com.web.service.user;

import com.web.dto.auth.User.UserResponse;
import com.web.dto.auth.User.UserUpdateRequest;
import com.web.dto.auth.User.mapper.UserMapper;
import com.web.entity.User;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserServiceImpl userService;

    private User user;
    private UserResponse userResponse;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(1L)
                .name("John Doe")
                .email("john@example.com")
                .phone("3001234567")
                .role(User.Role.PASSENGER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$oldHash")
                .build();

        userResponse = new UserResponse(
                1L, "John Doe", "john@example.com", "3001234567",
                User.Role.PASSENGER, User.Status.ACTIVE, null
        );
    }

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------

    @Test
    void shouldGetAll_ReturnUserList() {
        // Given
        List<User> users = List.of(user);
        List<UserResponse> responses = List.of(userResponse);
        when(userRepository.findAll()).thenReturn(users);
        when(userMapper.toResponseList(users)).thenReturn(responses);

        // When
        List<UserResponse> result = userService.getAll();

        // Then
        assertThat(result).containsExactly(userResponse);
        verify(userRepository).findAll();
    }

    @Test
    void shouldGetAll_WithoutUsers_ReturnEmptyList() {
        // Given
        when(userRepository.findAll()).thenReturn(List.of());
        when(userMapper.toResponseList(List.of())).thenReturn(List.of());

        // When
        List<UserResponse> result = userService.getAll();

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void shouldGetById_WithValidId_ReturnUserResponse() {
        // Given
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userMapper.toResponse(user)).thenReturn(userResponse);

        // When
        UserResponse result = userService.getById(1L);

        // Then
        assertThat(result).isEqualTo(userResponse);
        verify(userRepository).findById(1L);
    }

    @Test
    void shouldGetById_WithNonExistentId_ThrowResourceNotFoundException() {
        // Given
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> userService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Usuario")
                .hasMessageContaining("99");
        verifyNoInteractions(userMapper);
    }

    @Test
    void shouldGetByEmail_WithValidEmail_ReturnUserResponse() {
        // Given
        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(user));
        when(userMapper.toResponse(user)).thenReturn(userResponse);

        // When
        UserResponse result = userService.getByEmail("john@example.com");

        // Then
        assertThat(result).isEqualTo(userResponse);
        verify(userRepository).findByEmail("john@example.com");
    }

    @Test
    void shouldGetByEmail_WithNonExistentEmail_ThrowResourceNotFoundException() {
        // Given
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> userService.getByEmail("ghost@example.com"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("ghost@example.com");
        verifyNoInteractions(userMapper);
    }

    // ------------------------------------------------------------------
    // updateUser
    // ------------------------------------------------------------------

    @Test
    void shouldUpdateUser_WithoutPassword_KeepPasswordHash() {
        // Given
        UserUpdateRequest request = new UserUpdateRequest("John Updated", "3009999999", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponse(user)).thenReturn(userResponse);

        // When
        UserResponse result = userService.updateUser(1L, request);

        // Then
        assertThat(result).isEqualTo(userResponse);
        verify(userMapper).updateEntityFromRequest(request, user);
        verify(userRepository).save(user);
        assertThat(user.getPasswordHash()).isEqualTo("$2a$10$oldHash");
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void shouldUpdateUser_WithPassword_EncryptNewPassword() {
        // Given
        UserUpdateRequest request = new UserUpdateRequest(null, null, "newPassword123");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newPassword123")).thenReturn("$2a$10$newHash");
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponse(user)).thenReturn(userResponse);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);

        // When
        userService.updateUser(1L, request);

        // Then: se guarda el hash cifrado, nunca la contraseña en texto plano
        verify(passwordEncoder).encode("newPassword123");
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("$2a$10$newHash");
        assertThat(captor.getValue().getPasswordHash()).isNotEqualTo("newPassword123");
        // Los tokens emitidos con la contraseña anterior dejan de valer
        assertThat(captor.getValue().getPasswordChangedAt()).isNotNull();
    }

    @Test
    void shouldUpdateUser_WithBlankPassword_KeepPasswordHash() {
        // Given: una contraseña en blanco se interpreta como "no cambiar"
        UserUpdateRequest request = new UserUpdateRequest("John Updated", null, "   ");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponse(user)).thenReturn(userResponse);

        // When
        userService.updateUser(1L, request);

        // Then
        assertThat(user.getPasswordHash()).isEqualTo("$2a$10$oldHash");
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void shouldUpdateUser_WithEmptyPassword_KeepPasswordHash() {
        // Given
        UserUpdateRequest request = new UserUpdateRequest(null, null, "");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponse(user)).thenReturn(userResponse);

        // When
        userService.updateUser(1L, request);

        // Then
        assertThat(user.getPasswordHash()).isEqualTo("$2a$10$oldHash");
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void shouldUpdateUser_WithNonExistentId_ThrowResourceNotFoundException() {
        // Given
        UserUpdateRequest request = new UserUpdateRequest("John Updated", null, "newPassword123");
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> userService.updateUser(99L, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(userMapper, passwordEncoder);
    }

    // ------------------------------------------------------------------
    // deleteUser
    // ------------------------------------------------------------------

    @Test
    void shouldDeleteUser_WithValidId_SoftDeleteAsInactive() {
        // Given
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        // When
        userService.deleteUser(1L);

        // Then: borrado lógico, el registro no se elimina físicamente
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(User.Status.INACTIVE);
        verify(userRepository, never()).delete(any(User.class));
        verify(userRepository, never()).deleteById(any());
    }

    @Test
    void shouldDeleteUser_WithNonExistentId_ThrowResourceNotFoundException() {
        // Given
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> userService.deleteUser(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verify(userRepository, never()).save(any(User.class));
    }

    // ------------------------------------------------------------------
    // getUsersByRole
    // ------------------------------------------------------------------

    @Test
    void shouldGetUsersByRole_WithRole_QueryOnlyActiveUsers() {
        // Given
        User driver = User.builder()
                .id(2L)
                .name("Driver")
                .email("driver@example.com")
                .role(User.Role.DRIVER)
                .status(User.Status.ACTIVE)
                .build();
        UserResponse driverResponse = new UserResponse(
                2L, "Driver", "driver@example.com", null,
                User.Role.DRIVER, User.Status.ACTIVE, null
        );
        List<User> drivers = List.of(driver);
        when(userRepository.findByRoleAndStatus(User.Role.DRIVER, User.Status.ACTIVE)).thenReturn(drivers);
        when(userMapper.toResponseList(drivers)).thenReturn(List.of(driverResponse));

        // When
        List<UserResponse> result = userService.getUsersByRole(User.Role.DRIVER);

        // Then
        assertThat(result).containsExactly(driverResponse);
        verify(userRepository).findByRoleAndStatus(User.Role.DRIVER, User.Status.ACTIVE);
        verify(userRepository, never()).findAll();
    }
}
