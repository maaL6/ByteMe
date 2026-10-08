package org.example.auth.service.port;

import java.util.Optional;
import org.example.auth.domain.account.*;
import org.example.auth.domain.exception.*;

/** Nhóm data cung cấp implementation ORM/data mapper; không lọc chỉ ACTIVE ở lookup. */
public interface AccountRepository {
    boolean existsByEmailOrPhone(String normalizedEmail, String normalizedPhone);
    /** Lưu nguyên tử CUSTOMER/ACTIVE; unique race -> DuplicateAccountException. */
    AccountRecord createCustomer(NewCustomer account);
    Optional<AccountCredential> findCredentialByEmail(String normalizedEmail);
}
