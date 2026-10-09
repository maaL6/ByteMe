package org.example.auth.domain.account;

import java.util.Objects;

/** Chỉ dùng nội bộ Auth; không trả qua Controller hoặc module khác. */
public record AccountCredential(AccountRecord account, String passwordHash) {
    public AccountCredential {
        Objects.requireNonNull(account, "account");
        if (passwordHash == null || passwordHash.isBlank()) throw new IllegalArgumentException("Thiếu passwordHash");
    }
    @Override public String toString() { return "AccountCredential[REDACTED]"; }
}
