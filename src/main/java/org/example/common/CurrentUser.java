package org.example.common;

import org.example.shared.api.AuthenticatedUser;

public final class CurrentUser {
    private CurrentUser() {}

    public static long id(AuthenticatedUser user) {
        try {
            long id = Long.parseLong(user.userId());
            if (id > 0) return id;
        } catch (NumberFormatException ignored) { }
        throw BusinessException.forbidden("FORBIDDEN", "JWT phải chứa ID tài khoản hợp lệ trong database.");
    }
}
