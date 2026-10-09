package org.example.auth.service;
import org.example.auth.domain.account.AccountRecord;
import org.example.auth.service.port.AccessToken;
public record LoginResult(AccessToken token,AccountRecord user) {
    @Override public String toString() { return "LoginResult[REDACTED]"; }
}
