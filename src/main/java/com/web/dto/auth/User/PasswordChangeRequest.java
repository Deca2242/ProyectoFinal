package com.web.dto.auth.User;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

public record PasswordChangeRequest(
    @NotBlank String currentPassword,
    @NotBlank @Size(min = 8) String newPassword
) implements Serializable {}
