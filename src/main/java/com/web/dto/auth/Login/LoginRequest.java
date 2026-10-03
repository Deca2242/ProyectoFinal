package com.web.dto.auth.Login;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

public record LoginRequest(
        @NotBlank @Email @Size(max = 150) String email,
        @NotBlank @Size(max = 72) String password) implements Serializable {
}
