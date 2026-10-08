package org.example.auth.domain.account;

/** Không có role/status đầu vào: createCustomer phải tạo CUSTOMER/ACTIVE. */
public record NewCustomer(String fullName, String email, String phone, String passwordHash) {
    public NewCustomer {
        if (fullName == null || fullName.isBlank() || email == null || email.isBlank()
                || phone == null || phone.isBlank() || passwordHash == null || passwordHash.isBlank())
            throw new IllegalArgumentException("NewCustomer thiếu dữ liệu bắt buộc");
    }
    @Override public String toString() { return "NewCustomer[REDACTED]"; }
}
