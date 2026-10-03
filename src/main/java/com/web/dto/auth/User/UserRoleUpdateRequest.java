package com.web.dto.auth.User;

import com.web.entity.User;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

public record UserRoleUpdateRequest(
    @NotNull User.Role role
) implements Serializable {}
