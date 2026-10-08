package org.example.auth.persistence;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.example.auth.domain.account.*;
import org.example.auth.domain.exception.*;
import org.example.auth.service.port.AccountRepository;
import org.example.shared.api.Role;
@Repository
public class MySqlAccountRepository implements AccountRepository {
    private final UserJpaRepository jpa;
    public MySqlAccountRepository(UserJpaRepository jpa) { this.jpa=jpa; }
    @Override @Transactional(readOnly=true)
    public boolean existsByEmailOrPhone(String email,String phone) {
        try { return jpa.existsByEmailOrPhone(email,phone); }
        catch(RuntimeException e) { throw new AccountPersistenceException(e); }
    }
    @Override @Transactional
    public AccountRecord createCustomer(NewCustomer input) {
        try {
            var row=new UserEntity();row.fullName=input.fullName();row.email=input.email();row.phone=input.phone();
            row.passwordHash=input.passwordHash();row.role="CUSTOMER";row.status="ACTIVE";
            return map(jpa.saveAndFlush(row));
        } catch(RuntimeException e) {
            for(Throwable cause=e;cause!=null;cause=cause.getCause()) {
                if(cause instanceof SQLException sql && sql.getErrorCode()==1062
                    && (sql.getMessage().contains("uq_users_email") || sql.getMessage().contains("uq_users_phone")))
                    throw new DuplicateAccountException();
            }
            throw new AccountPersistenceException(e);
        }
    }
    @Override @Transactional(readOnly=true)
    public Optional<AccountCredential> findCredentialByEmail(String email) {
        try { return jpa.findByEmail(email).map(row->new AccountCredential(map(row),row.passwordHash)); }
        catch(RuntimeException e) { throw new AccountPersistenceException(e); }
    }
    private static AccountRecord map(UserEntity row) {
        return new AccountRecord(row.id.toString(),row.fullName,row.email,row.phone,
                                 Role.valueOf(row.role),AccountStatus.valueOf(row.status));
    }
}
