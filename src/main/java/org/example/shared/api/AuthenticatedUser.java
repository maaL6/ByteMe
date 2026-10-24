package org.example.shared.api;

import java.util.Objects;

public record AuthenticatedUser(String userId, Role role) {
    public AuthenticatedUser {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId không được rỗng");
        Objects.requireNonNull(role, "role");
    }
}
