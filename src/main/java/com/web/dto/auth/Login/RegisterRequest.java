package com.web.dto.auth.Login;

import com.web.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.io.Serializable;

public record RegisterRequest(
        @NotBlank String name,
        @NotBlank @Email String email,
        @NotBlank String phone,
        @NotBlank String password,
        User.Role role // Solo un ADMIN autenticado puede registrar roles distintos de PASSENGER
) implements Serializable {
}
