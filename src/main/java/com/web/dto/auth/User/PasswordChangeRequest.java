package com.web.dto.auth.User;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

public record PasswordChangeRequest(
    @NotBlank @Size(max = 72) String currentPassword,
    @NotBlank @Size(min = 8, max = 72) String newPassword
) implements Serializable {

    // La nueva contraseña debe ser distinta de la actual (400 si coinciden)
    @JsonIgnore
    @AssertTrue(message = "La nueva contraseña debe ser distinta de la actual")
    public boolean isNewPasswordDifferent() {
        return currentPassword == null || newPassword == null || !currentPassword.equals(newPassword);
    }
}
