package org.example.auth.domain.exception;

public class DuplicateAccountException extends RuntimeException {
    public DuplicateAccountException() { super("Tài khoản bị trùng"); }
}
