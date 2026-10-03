package com.web.service.user;

import com.web.dto.auth.User.PasswordChangeRequest;
import com.web.dto.auth.User.UserResponse;
import com.web.dto.auth.User.mapper.UserMapper;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Gestión de usuarios por el ADMIN (filtros, estado, rol) y perfil / cambio de contraseña del autenticado
@ExtendWith(MockitoExtension.class)
class UserManagementServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserServiceImpl userService;

    private User admin;
    private User driver;
    private UserResponse driverResponse;

    @BeforeEach
    void setUp() {
        admin = User.builder().id(1L).name("Admin").email("admin@test.com").role(User.Role.ADMIN)
                .status(User.Status.ACTIVE).passwordHash("$hash-admin").build();
        driver = User.builder().id(2L).name("Driver").email("driver@test.com").role(User.Role.DRIVER)
                .status(User.Status.ACTIVE).passwordHash("$hash-driver").build();
        driverResponse = new UserResponse(2L, "Driver", "driver@test.com", null, User.Role.DRIVER,
                User.Status.ACTIVE, null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // ---------- Listado con filtros ----------

    @Test
    void shouldGetUsers_WithRoleAndStatus_UseCombinedFilter() {
        // Given
        when(userRepository.findByRoleAndStatus(User.Role.DRIVER, User.Status.ACTIVE)).thenReturn(List.of(driver));
        when(userMapper.toResponseList(List.of(driver))).thenReturn(List.of(driverResponse));

        // When
        List<UserResponse> result = userService.getUsers(User.Role.DRIVER, User.Status.ACTIVE);

        // Then
        assertThat(result).containsExactly(driverResponse);
    }

    @Test
    void shouldGetUsers_WithRoleOnly_FilterByRole() {
        // Given
        when(userRepository.findByRole(User.Role.DRIVER)).thenReturn(List.of(driver));
        when(userMapper.toResponseList(List.of(driver))).thenReturn(List.of(driverResponse));

        // When
        userService.getUsers(User.Role.DRIVER, null);

        // Then
        verify(userRepository).findByRole(User.Role.DRIVER);
        verify(userRepository, never()).findAll();
    }

    @Test
    void shouldGetUsers_WithStatusOnly_FilterByStatus() {
        // Given
        when(userRepository.findByStatus(User.Status.INACTIVE)).thenReturn(List.of());
        when(userMapper.toResponseList(List.of())).thenReturn(List.of());

        // When
        List<UserResponse> result = userService.getUsers(null, User.Status.INACTIVE);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void shouldGetUsers_WithoutFilters_ReturnAllSortedById() {
        // Given
        when(userRepository.findAll()).thenReturn(List.of(driver, admin));
        when(userMapper.toResponseList(List.of(admin, driver))).thenReturn(List.of(driverResponse));

        // When
        List<UserResponse> result = userService.getUsers(null, null);

        // Then
        assertThat(result).hasSize(1);
    }

    // ---------- Estado ----------

    @Test
    void shouldUpdateStatus_DeactivateOtherUser_SaveInactive() {
        // Given
        authenticate("admin@test.com", "ADMIN");
        when(userRepository.findById(2L)).thenReturn(Optional.of(driver));
        when(userRepository.save(driver)).thenReturn(driver);
        when(userMapper.toResponse(driver)).thenReturn(driverResponse);

        // When
        userService.updateStatus(2L, User.Status.INACTIVE);

        // Then
        assertThat(driver.getStatus()).isEqualTo(User.Status.INACTIVE);
        verify(userRepository).save(driver);
    }

    @Test
    void shouldUpdateStatus_DeactivateSelf_ThrowBadRequest() {
        // Given: el email del token puede venir con otra capitalización
        authenticate("ADMIN@test.com", "ADMIN");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        // When/Then
        assertThatThrownBy(() -> userService.updateStatus(1L, User.Status.INACTIVE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("CANNOT_DEACTIVATE_SELF");
                });
        assertThat(admin.getStatus()).isEqualTo(User.Status.ACTIVE);
        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldUpdateStatus_ActivateSelf_Allow() {
        // Given: reactivarse (caso idempotente) no está prohibido
        authenticate("admin@test.com", "ADMIN");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.save(admin)).thenReturn(admin);

        // When
        userService.updateStatus(1L, User.Status.ACTIVE);

        // Then
        verify(userRepository).save(admin);
    }

    @Test
    void shouldUpdateStatus_WithNonExistentUser_ThrowResourceNotFound() {
        // Given
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> userService.updateStatus(99L, User.Status.INACTIVE))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    // ---------- Rol ----------

    @Test
    void shouldUpdateRole_OfOtherUser_SaveNewRole() {
        // Given
        authenticate("admin@test.com", "ADMIN");
        when(userRepository.findById(2L)).thenReturn(Optional.of(driver));
        when(userRepository.save(driver)).thenReturn(driver);
        when(userMapper.toResponse(driver)).thenReturn(driverResponse);

        // When
        userService.updateRole(2L, User.Role.DISPATCHER);

        // Then
        assertThat(driver.getRole()).isEqualTo(User.Role.DISPATCHER);
    }

    @Test
    void shouldUpdateRole_OfSelf_ThrowBadRequest() {
        // Given
        authenticate("admin@test.com", "ADMIN");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        // When/Then
        assertThatThrownBy(() -> userService.updateRole(1L, User.Role.PASSENGER))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CANNOT_CHANGE_OWN_ROLE");
        assertThat(admin.getRole()).isEqualTo(User.Role.ADMIN);
    }

    @Test
    void shouldUpdateRole_WithNonExistentUser_ThrowResourceNotFound() {
        // Given
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> userService.updateRole(99L, User.Role.CLERK))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- Perfil y contraseña ----------

    @Test
    void shouldGetCurrentUser_ReturnAuthenticatedUser() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        when(userRepository.findByEmail("driver@test.com")).thenReturn(Optional.of(driver));
        when(userMapper.toResponse(driver)).thenReturn(driverResponse);

        // When
        UserResponse result = userService.getCurrentUser();

        // Then
        assertThat(result).isSameAs(driverResponse);
    }

    @Test
    void shouldGetCurrentUser_WithoutAuthentication_ThrowUnauthorized() {
        // When/Then
        assertThatThrownBy(() -> userService.getCurrentUser())
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldGetCurrentUser_WithDeletedUser_ThrowResourceNotFound() {
        // Given
        authenticate("ghost@test.com", "PASSENGER");
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> userService.getCurrentUser())
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void shouldChangePassword_WithCorrectCurrentPassword_SaveNewHash() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        when(userRepository.findByEmail("driver@test.com")).thenReturn(Optional.of(driver));
        when(passwordEncoder.matches("actual123", "$hash-driver")).thenReturn(true);
        when(passwordEncoder.encode("nueva12345")).thenReturn("$hash-nuevo");

        // When
        userService.changePassword(new PasswordChangeRequest("actual123", "nueva12345"));

        // Then
        assertThat(driver.getPasswordHash()).isEqualTo("$hash-nuevo");
        verify(userRepository).save(driver);
    }

    @Test
    void shouldChangePassword_SetPasswordChangedAtToInvalidatePreviousTokens() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        when(userRepository.findByEmail("driver@test.com")).thenReturn(Optional.of(driver));
        when(passwordEncoder.matches("actual123", "$hash-driver")).thenReturn(true);
        when(passwordEncoder.encode("nueva12345")).thenReturn("$hash-nuevo");
        LocalDateTime before = LocalDateTime.now();

        // When
        userService.changePassword(new PasswordChangeRequest("actual123", "nueva12345"));

        // Then
        assertThat(driver.getPasswordChangedAt()).isNotNull()
                .isAfterOrEqualTo(before)
                .isBeforeOrEqualTo(LocalDateTime.now());
    }

    // ---------- Último ADMIN activo ----------

    @Test
    void shouldUpdateStatus_DeactivateLastActiveAdmin_ThrowConflict() {
        // Given: otro admin (ya inactivo o inexistente) intenta desactivar al único ADMIN activo
        User otherAdmin = User.builder().id(3L).email("otro@test.com").role(User.Role.ADMIN)
                .status(User.Status.ACTIVE).build();
        authenticate("admin@test.com", "ADMIN");
        when(userRepository.findById(3L)).thenReturn(Optional.of(otherAdmin));
        when(userRepository.lockByRoleAndStatus(User.Role.ADMIN, User.Status.ACTIVE)).thenReturn(List.of(otherAdmin));

        // When/Then
        assertThatThrownBy(() -> userService.updateStatus(3L, User.Status.INACTIVE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("LAST_ADMIN");
                });
        assertThat(otherAdmin.getStatus()).isEqualTo(User.Status.ACTIVE);
        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldUpdateStatus_DeactivateAdminWhenOthersRemain_Allow() {
        // Given
        User otherAdmin = User.builder().id(3L).email("otro@test.com").role(User.Role.ADMIN)
                .status(User.Status.ACTIVE).build();
        authenticate("admin@test.com", "ADMIN");
        when(userRepository.findById(3L)).thenReturn(Optional.of(otherAdmin));
        when(userRepository.lockByRoleAndStatus(User.Role.ADMIN, User.Status.ACTIVE)).thenReturn(List.of(admin, otherAdmin));
        when(userRepository.save(otherAdmin)).thenReturn(otherAdmin);

        // When
        userService.updateStatus(3L, User.Status.INACTIVE);

        // Then
        assertThat(otherAdmin.getStatus()).isEqualTo(User.Status.INACTIVE);
    }

    @Test
    void shouldUpdateRole_DemoteLastActiveAdmin_ThrowConflict() {
        // Given
        User otherAdmin = User.builder().id(3L).email("otro@test.com").role(User.Role.ADMIN)
                .status(User.Status.ACTIVE).build();
        authenticate("admin@test.com", "ADMIN");
        when(userRepository.findById(3L)).thenReturn(Optional.of(otherAdmin));
        when(userRepository.lockByRoleAndStatus(User.Role.ADMIN, User.Status.ACTIVE)).thenReturn(List.of(otherAdmin));

        // When/Then
        assertThatThrownBy(() -> userService.updateRole(3L, User.Role.DISPATCHER))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("LAST_ADMIN");
        assertThat(otherAdmin.getRole()).isEqualTo(User.Role.ADMIN);
    }

    @Test
    void shouldUpdateRole_DemoteInactiveAdmin_NotCountActiveAdmins() {
        // Given: un ADMIN inactivo no es "el último activo"
        User inactiveAdmin = User.builder().id(3L).email("otro@test.com").role(User.Role.ADMIN)
                .status(User.Status.INACTIVE).build();
        authenticate("admin@test.com", "ADMIN");
        when(userRepository.findById(3L)).thenReturn(Optional.of(inactiveAdmin));
        when(userRepository.save(inactiveAdmin)).thenReturn(inactiveAdmin);

        // When
        userService.updateRole(3L, User.Role.CLERK);

        // Then
        assertThat(inactiveAdmin.getRole()).isEqualTo(User.Role.CLERK);
        verify(userRepository, never()).lockByRoleAndStatus(any(), any());
    }

    @Test
    void shouldDeleteUser_LastActiveAdmin_ThrowConflict() {
        // Given
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.lockByRoleAndStatus(User.Role.ADMIN, User.Status.ACTIVE)).thenReturn(List.of(admin));

        // When/Then
        assertThatThrownBy(() -> userService.deleteUser(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("LAST_ADMIN");
        verify(userRepository, never()).save(any());
    }

    // ---------- Búsqueda por texto ----------

    @Test
    void shouldGetUsers_WithSearchText_UseLowercasePatternWithFilters() {
        // Given
        when(userRepository.search(User.Role.DRIVER, null, "%driv%")).thenReturn(List.of(driver));
        when(userMapper.toResponseList(List.of(driver))).thenReturn(List.of(driverResponse));

        // When
        List<UserResponse> result = userService.getUsers(User.Role.DRIVER, null, "  DRIV ");

        // Then
        assertThat(result).containsExactly(driverResponse);
    }

    @Test
    void shouldGetUsers_WithBlankSearchText_UseRegularFilters() {
        // Given
        when(userRepository.findByRole(User.Role.DRIVER)).thenReturn(List.of(driver));
        when(userMapper.toResponseList(List.of(driver))).thenReturn(List.of(driverResponse));

        // When
        List<UserResponse> result = userService.getUsers(User.Role.DRIVER, null, " ");

        // Then
        assertThat(result).containsExactly(driverResponse);
        verify(userRepository, never()).search(any(), any(), any());
    }

    @Test
    void shouldChangePassword_WithWrongCurrentPassword_ThrowBadRequest() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        when(userRepository.findByEmail("driver@test.com")).thenReturn(Optional.of(driver));
        when(passwordEncoder.matches("incorrecta", "$hash-driver")).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> userService.changePassword(new PasswordChangeRequest("incorrecta", "nueva12345")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_CURRENT_PASSWORD");
                });
        assertThat(driver.getPasswordHash()).isEqualTo("$hash-driver");
        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).save(any());
    }
}
