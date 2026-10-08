package org.example.auth.domain.exception;

public class AccountPersistenceException extends RuntimeException {
    public AccountPersistenceException(Throwable cause) { super("Không thể truy cập dữ liệu account", cause); }
}
