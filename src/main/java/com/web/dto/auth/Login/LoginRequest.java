package com.web.dto.auth.Login;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.io.Serializable;

public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password) implements Serializable {
}
