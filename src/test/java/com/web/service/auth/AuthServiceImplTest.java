package com.web.service.auth;

import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.LoginResponse;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.auth.User.UserResponse;
import com.web.dto.auth.User.mapper.UserMapper;
import com.web.entity.User;
import com.web.exception.EmailAlreadyExistsException;
import com.web.exception.InvalidCredentialsException;
import com.web.repository.UserRepository;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private AuthServiceImpl authService;

    private User user;
    private UserResponse userResponse;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(1L)
                .name("John Doe")
                .email("john@example.com")
                .role(User.Role.PASSENGER)
                .status(User.Status.ACTIVE)
                .passwordHash("$2a$10$encoded")
                .build();

        userResponse = new UserResponse(
                1L, "John Doe", "john@example.com", null,
                User.Role.PASSENGER, User.Status.ACTIVE, null
        );

        // Cada prueba arranca sin usuario autenticado
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        // Evita que la autenticación de una prueba se filtre a las demás
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldRegister_WithValidRequest_ReturnUserResponse() {
        // Given
        RegisterRequest request = new RegisterRequest(
                "John Doe", "john@example.com", "123456789", "password123", null
        );

        when(userRepository.existsByEmail("john@example.com")).thenReturn(false);
        when(userMapper.toEntity(request)).thenReturn(user);
        when(passwordEncoder.encode("password123")).thenReturn("$2a$10$encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
        when(userMapper.toResponse(any(User.class))).thenReturn(userResponse);

        // When
        UserResponse result = authService.register(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.email()).isEqualTo("john@example.com");
        verify(userRepository).existsByEmail("john@example.com");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void shouldRegister_WithExistingEmail_ThrowException() {
        // Given
        RegisterRequest request = new RegisterRequest(
                "John Doe", "john@example.com", "123456789", "password123", null
        );

        when(userRepository.existsByEmail("john@example.com")).thenReturn(true);

        // When/Then
        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void shouldLogin_WithValidCredentials_ReturnLoginResponse() {
        // Given
        LoginRequest request = new LoginRequest("john@example.com", "password123");

        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "$2a$10$encoded")).thenReturn(true);
        when(jwtTokenProvider.generateToken("john@example.com", "PASSENGER")).thenReturn("token123");
        when(userMapper.toResponse(user)).thenReturn(userResponse);

        // When
        LoginResponse result = authService.login(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.token()).isEqualTo("token123");
        assertThat(result.tokenType()).isEqualTo("Bearer");
        verify(userRepository).findByEmail("john@example.com");
        verify(passwordEncoder).matches("password123", "$2a$10$encoded");
    }

    @Test
    void shouldLogin_WithInvalidCredentials_ThrowException() {
        // Given
        LoginRequest request = new LoginRequest("john@example.com", "wrongpassword");

        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpassword", "$2a$10$encoded")).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Credenciales inválidas");
    }

    @Test
    void shouldValidateToken_WithValidToken_ReturnTrue() {
        // Given
        when(jwtTokenProvider.validateToken("validToken")).thenReturn(true);

        // When
        boolean result = authService.validateToken("validToken");

        // Then
        assertThat(result).isTrue();
        verify(jwtTokenProvider).validateToken("validToken");
    }

    // ------------------------------------------------------------------
    // register: asignación de rol según el usuario autenticado
    // ------------------------------------------------------------------

    @Test
    void shouldRegister_WithoutRoleAndWithoutAuthentication_AssignPassengerRole() {
        // Given
        RegisterRequest request = registerRequest(null);

        // When
        User saved = registerAndCaptureSavedUser(request);

        // Then
        assertThat(saved.getRole()).isEqualTo(User.Role.PASSENGER);
    }

    @Test
    void shouldRegister_WithoutRoleAuthenticatedAsAdmin_AssignPassengerRole() {
        // Given: aunque sea un ADMIN, si no pide rol se crea un pasajero
        authenticateAs("ROLE_ADMIN");
        RegisterRequest request = registerRequest(null);

        // When
        User saved = registerAndCaptureSavedUser(request);

        // Then
        assertThat(saved.getRole()).isEqualTo(User.Role.PASSENGER);
    }

    @Test
    void shouldRegister_RequestingAdminWithoutAuthentication_AssignPassengerRole() {
        // Given: regresión de escalada de privilegios en el registro público
        RegisterRequest request = registerRequest(User.Role.ADMIN);

        // When
        User saved = registerAndCaptureSavedUser(request);

        // Then
        assertThat(saved.getRole()).isEqualTo(User.Role.PASSENGER);
    }

    @Test
    void shouldRegister_RequestingClerkAuthenticatedAsPassenger_AssignPassengerRole() {
        // Given
        authenticateAs("ROLE_PASSENGER");
        RegisterRequest request = registerRequest(User.Role.CLERK);

        // When
        User saved = registerAndCaptureSavedUser(request);

        // Then
        assertThat(saved.getRole()).isEqualTo(User.Role.PASSENGER);
    }

    @Test
    void shouldRegister_RequestingDriverAuthenticatedAsAdmin_KeepDriverRole() {
        // Given
        authenticateAs("ROLE_ADMIN");
        RegisterRequest request = registerRequest(User.Role.DRIVER);

        // When
        User saved = registerAndCaptureSavedUser(request);

        // Then
        assertThat(saved.getRole()).isEqualTo(User.Role.DRIVER);
    }

    @Test
    void shouldRegister_RequestingAdminAuthenticatedAsAdminWithSeveralAuthorities_KeepAdminRole() {
        // Given: ROLE_ADMIN no es la primera autoridad de la lista
        authenticateAs("ROLE_DISPATCHER", "ROLE_ADMIN");
        RegisterRequest request = registerRequest(User.Role.ADMIN);

        // When
        User saved = registerAndCaptureSavedUser(request);

        // Then
        assertThat(saved.getRole()).isEqualTo(User.Role.ADMIN);
    }

    @Test
    void shouldRegister_WithNotAuthenticatedAdminToken_AssignPassengerRole() {
        // Given: un token con ROLE_ADMIN pero marcado como no autenticado no otorga privilegios
        Authentication token = new TestingAuthenticationToken("admin@example.com", null, "ROLE_ADMIN");
        token.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(token);
        RegisterRequest request = registerRequest(User.Role.DISPATCHER);

        // When
        User saved = registerAndCaptureSavedUser(request);

        // Then
        assertThat(saved.getRole()).isEqualTo(User.Role.PASSENGER);
    }

    @Test
    void shouldRegister_WithValidRequest_EncryptPasswordWithEncoder() {
        // Given
        RegisterRequest request = registerRequest(null);

        // When
        User saved = registerAndCaptureSavedUser(request);

        // Then: se guarda el hash, nunca la contraseña en texto plano
        verify(passwordEncoder).encode("password123");
        assertThat(saved.getPasswordHash()).isEqualTo("$2a$10$hashed");
        assertThat(saved.getPasswordHash()).isNotEqualTo("password123");
    }

    @Test
    void shouldRegister_WithExistingEmail_NotSaveNorEncodePassword() {
        // Given
        RegisterRequest request = registerRequest(User.Role.DRIVER);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(true);

        // When/Then
        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(userMapper, passwordEncoder);
    }

    // ------------------------------------------------------------------
    // login
    // ------------------------------------------------------------------

    @Test
    void shouldLogin_WithNonExistentEmail_ThrowInvalidCredentials() {
        // Given
        LoginRequest request = new LoginRequest("ghost@example.com", "password123");
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Credenciales inválidas");
        verifyNoInteractions(passwordEncoder, jwtTokenProvider);
    }

    @Test
    void shouldLogin_WithWrongPassword_NotGenerateToken() {
        // Given
        LoginRequest request = new LoginRequest("john@example.com", "wrongpassword");
        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpassword", "$2a$10$encoded")).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class);
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    void shouldLogin_WithInactiveUser_ThrowInvalidCredentials() {
        // Given: contraseña correcta pero usuario desactivado
        user.setStatus(User.Status.INACTIVE);
        LoginRequest request = new LoginRequest("john@example.com", "password123");
        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "$2a$10$encoded")).thenReturn(true);

        // When/Then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Usuario inactivo");
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    void shouldLogin_WithAdminUser_GenerateTokenWithAdminRole() {
        // Given
        user.setRole(User.Role.ADMIN);
        UserResponse adminResponse = new UserResponse(
                1L, "John Doe", "john@example.com", null,
                User.Role.ADMIN, User.Status.ACTIVE, null
        );
        LoginRequest request = new LoginRequest("john@example.com", "password123");
        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "$2a$10$encoded")).thenReturn(true);
        when(jwtTokenProvider.generateToken("john@example.com", "ADMIN")).thenReturn("adminToken");
        when(userMapper.toResponse(user)).thenReturn(adminResponse);

        // When
        LoginResponse result = authService.login(request);

        // Then
        assertThat(result.token()).isEqualTo("adminToken");
        assertThat(result.tokenType()).isEqualTo("Bearer");
        assertThat(result.user()).isEqualTo(adminResponse);
    }

    // ------------------------------------------------------------------
    // validateToken
    // ------------------------------------------------------------------

    @Test
    void shouldValidateToken_WithInvalidToken_ReturnFalse() {
        // Given
        when(jwtTokenProvider.validateToken("invalidToken")).thenReturn(false);

        // When
        boolean result = authService.validateToken("invalidToken");

        // Then
        assertThat(result).isFalse();
        verify(jwtTokenProvider).validateToken("invalidToken");
    }

    // ------------------------------------------------------------------
    // Utilidades de prueba
    // ------------------------------------------------------------------

    private static RegisterRequest registerRequest(User.Role role) {
        return new RegisterRequest("Jane Doe", "new@example.com", "3001234567", "password123", role);
    }

    // Autentica en el SecurityContextHolder a un usuario con las autoridades indicadas
    private static void authenticateAs(String... authorities) {
        List<SimpleGrantedAuthority> grantedAuthorities = Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("current@example.com", null, grantedAuthorities));
    }

    // Ejecuta el registro simulando el mapper (copia el rol pedido) y devuelve el usuario persistido
    private User registerAndCaptureSavedUser(RegisterRequest request) {
        User mapped = User.builder()
                .name(request.name())
                .email(request.email())
                .phone(request.phone())
                .role(request.role())
                .status(User.Status.ACTIVE)
                .build();
        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(userMapper.toEntity(request)).thenReturn(mapped);
        when(passwordEncoder.encode(request.password())).thenReturn("$2a$10$hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userMapper.toResponse(any(User.class))).thenReturn(userResponse);

        UserResponse response = authService.register(request);

        assertThat(response).isSameAs(userResponse);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void shouldRegisterAndLogin_WithMixedCaseEmail_NormalizeToLowercase() {
        // Given: el email no distingue mayúsculas
        RegisterRequest request = new RegisterRequest("Juan", "  Juan@Example.COM ", "300", "secreto1", User.Role.PASSENGER);
        when(userRepository.existsByEmail("juan@example.com")).thenReturn(true);

        // When/Then: se compara ya normalizado
        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(com.web.exception.EmailAlreadyExistsException.class);

        when(userRepository.findByEmail("juan@example.com")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authService.login(new LoginRequest("JUAN@example.com", "x")))
                .isInstanceOf(com.web.exception.InvalidCredentialsException.class);
        verify(userRepository).findByEmail("juan@example.com");
    }
}

