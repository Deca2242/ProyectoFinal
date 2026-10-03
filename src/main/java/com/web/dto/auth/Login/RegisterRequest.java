package com.web.dto.auth.Login;

import com.web.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

// Longitudes máximas iguales a las columnas de "users" (V1): un valor más largo es un 400, no un error de BD.
// BCrypt solo admite contraseñas de hasta 72 bytes
public record RegisterRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 150) String email,
        @NotBlank @Size(max = 20) String phone,
        @NotBlank @Size(min = 8, max = 72) String password,
        User.Role role // Solo un ADMIN autenticado puede registrar roles distintos de PASSENGER (si no, 403)
) implements Serializable {
}
