package org.example.auth.service;
public record LoginCommand(String email, String password) {
    @Override public String toString() { return "LoginCommand[REDACTED]"; }
}
