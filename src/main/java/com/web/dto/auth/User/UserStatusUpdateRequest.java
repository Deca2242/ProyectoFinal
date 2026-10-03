package com.web.dto.auth.User;

import com.web.entity.User;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

public record UserStatusUpdateRequest(
    @NotNull User.Status status
) implements Serializable {}
