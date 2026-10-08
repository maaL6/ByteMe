package org.example.auth.service;
import java.util.UUID;
import org.example.auth.domain.account.*;
import org.example.auth.domain.exception.*;
import org.example.auth.service.port.*;
import org.example.shared.api.AuthenticatedUser;
public final class LoginService {
    private final AccountRepository repository;
    private final PasswordHasher passwords;
    private final TokenIssuer tokens;
    private final String dummyHash;
    public LoginService(AccountRepository repository, PasswordHasher passwords, TokenIssuer tokens) {
        this.repository=repository;this.passwords=passwords;this.tokens=tokens;
        this.dummyHash=passwords.hash(UUID.randomUUID().toString());
    }
    public LoginResult login(LoginCommand command) {
        String email=AuthValidation.email(command.email()); AuthValidation.password(command.password(),"password",1);
        var found=repository.findCredentialByEmail(email);
        boolean matched=passwords.matches(command.password(),found.map(AccountCredential::passwordHash).orElse(dummyHash));
        if(found.isEmpty() || !matched) throw new AuthFailure("INVALID_CREDENTIALS","Email hoặc mật khẩu không chính xác.");
        AccountRecord user=found.get().account();
        if(user.status()!=AccountStatus.ACTIVE) throw new AuthFailure("ACCOUNT_NOT_ACTIVE","Tài khoản không hoạt động.");
        try { AuthValidation.text(user.fullName(),"fullName",150); AuthValidation.email(user.email()); AuthValidation.phone(user.phone()); }
        catch(AuthFailure e) { throw new AccountPersistenceException(e); }
        return new LoginResult(tokens.issue(new AuthenticatedUser(user.id(),user.role())),user);
    }
}
