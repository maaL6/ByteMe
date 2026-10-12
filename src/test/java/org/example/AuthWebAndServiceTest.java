package org.example;

import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.example.auth.controller.AuthController;
import org.example.auth.dto.*;
import org.example.auth.entity.AccountStatus;
import org.example.auth.repository.AccountRepository;
import org.example.auth.service.*;
import org.example.config.SecurityConfiguration;
import org.example.platform.security.LoginRateLimiter;
import org.example.shared.api.Role;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@ActiveProfiles("test")
@Import(SecurityConfiguration.class)
class AuthWebAndServiceTest {

    @Autowired MockMvc http;
    @Autowired ObjectMapper mapper;
    @MockitoBean RegisterService registerService;
    @MockitoBean LoginService loginService;
    @MockitoBean LoginRateLimiter limiter;
    @MockitoBean JwtDecoder decoder;

    AccountRepository repository;
    PasswordHasher passwords;
    TokenIssuer tokens;
    LoginService realLoginService;
    RegisterService realRegisterService;

    @BeforeEach
    void setUp() {
        repository = mock(AccountRepository.class);
        passwords = mock(PasswordHasher.class);
        tokens = mock(TokenIssuer.class);
        when(passwords.hash(anyString())).thenReturn("hashed-pass");
        when(passwords.matches(eq("Secret123!"), eq("hashed-pass"))).thenReturn(true);
        when(passwords.matches(eq("WrongPass"), eq("hashed-pass"))).thenReturn(false);

        realLoginService = new LoginService(repository, passwords, tokens);
        realRegisterService = new RegisterService(repository, passwords);
    }

    @Test
    void registerServiceSuccess() {
        when(repository.existsByEmailOrPhone("user@example.com", "0900000001")).thenReturn(false);
        var expected = new AccountRecord("1", "Nguyen Van A", "user@example.com", "0900000001", Role.CUSTOMER, AccountStatus.ACTIVE);
        when(repository.createCustomer(any())).thenReturn(expected);

        var result = realRegisterService.register(new RegisterCommand("Nguyen Van A", "user@example.com", "0900000001", "Secret123!", "Secret123!"));
        assertEquals("1", result.id());
        assertEquals("user@example.com", result.email());
        assertEquals(Role.CUSTOMER, result.role());
    }

    @Test
    void registerServicePasswordMismatch() {
        var ex = assertThrows(AuthFailure.class, () ->
            realRegisterService.register(new RegisterCommand("Nguyen Van A", "user@example.com", "0900000001", "Secret123!", "Mismatch!"))
        );
        assertEquals("PASSWORD_MISMATCH", ex.code());
    }

    @Test
    void registerServiceDuplicateAccount() {
        when(repository.existsByEmailOrPhone("dup@example.com", "0900000002")).thenReturn(true);
        assertThrows(DuplicateAccountException.class, () ->
            realRegisterService.register(new RegisterCommand("Nguyen Van A", "dup@example.com", "0900000002", "Secret123!", "Secret123!"))
        );
    }

    @Test
    void loginServiceSuccess() {
        var user = new AccountRecord("10", "User Ten", "ten@example.com", "0900000010", Role.CUSTOMER, AccountStatus.ACTIVE);
        when(repository.findCredentialByEmail("ten@example.com"))
                .thenReturn(Optional.of(new AccountCredential(user, "hashed-pass")));
        when(tokens.issue(any())).thenReturn(new AccessToken("mock-token", 5400, java.time.Instant.now().plusSeconds(5400)));

        var result = realLoginService.login(new LoginCommand("ten@example.com", "Secret123!"));
        assertEquals("mock-token", result.token().value());
        assertEquals("User Ten", result.user().fullName());
    }

    @Test
    void loginServiceInvalidCredentials() {
        var user = new AccountRecord("10", "User Ten", "ten@example.com", "0900000010", Role.CUSTOMER, AccountStatus.ACTIVE);
        when(repository.findCredentialByEmail("ten@example.com"))
                .thenReturn(Optional.of(new AccountCredential(user, "hashed-pass")));

        var ex = assertThrows(AuthFailure.class, () ->
            realLoginService.login(new LoginCommand("ten@example.com", "WrongPass"))
        );
        assertEquals("INVALID_CREDENTIALS", ex.code());
    }

    @Test
    void loginServiceAccountLocked() {
        var user = new AccountRecord("10", "User Ten", "ten@example.com", "0900000010", Role.CUSTOMER, AccountStatus.LOCKED);
        when(repository.findCredentialByEmail("ten@example.com"))
                .thenReturn(Optional.of(new AccountCredential(user, "hashed-pass")));

        var ex = assertThrows(AuthFailure.class, () ->
            realLoginService.login(new LoginCommand("ten@example.com", "Secret123!"))
        );
        assertEquals("ACCOUNT_NOT_ACTIVE", ex.code());
    }

    @Test
    void webRegisterEndpoint() throws Exception {
        var user = new AccountRecord("1", "Nguyen Van A", "a@example.com", "0900000001", Role.CUSTOMER, AccountStatus.ACTIVE);
        when(registerService.register(any())).thenReturn(user);

        http.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "fullName": "Nguyen Van A",
                            "email": "a@example.com",
                            "phone": "0900000001",
                            "password": "Password123!",
                            "confirmPassword": "Password123!"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("1"))
                .andExpect(jsonPath("$.email").value("a@example.com"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void webLoginEndpoint() throws Exception {
        var user = new AccountRecord("1", "Nguyen Van A", "a@example.com", "0900000001", Role.CUSTOMER, AccountStatus.ACTIVE);
        var token = new AccessToken("jwt-token-abc", 5400, java.time.Instant.now().plusSeconds(5400));
        when(loginService.login(any())).thenReturn(new LoginResult(token, user));

        http.perform(post("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "email": "a@example.com",
                            "password": "Password123!"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-token-abc"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(5400))
                .andExpect(jsonPath("$.user.id").value("1"));
    }
}
