package org.example.auth.domain.account;

import java.util.Objects;
import org.example.shared.api.Role;

/** Dữ liệu account thuần, không phải ORM entity. Validation input nằm ở lát cắt Service sau. */
public record AccountRecord(String id, String fullName, String email, String phone,
                            Role role, AccountStatus status) {
    public AccountRecord {
        if (id == null || id.isBlank() || fullName == null || fullName.isBlank()
                || email == null || email.isBlank() || phone == null || phone.isBlank())
            throw new IllegalArgumentException("Account thiếu dữ liệu bắt buộc");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(status, "status");
    }
}
