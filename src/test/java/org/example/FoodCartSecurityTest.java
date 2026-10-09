package org.example;

import org.example.cart.controller.CartController;
import org.example.cart.service.CartService;
import org.example.config.SecurityConfiguration;
import org.example.food.controller.FoodController;
import org.example.food.service.FoodService;
import org.example.platform.security.LoginRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({FoodController.class, CartController.class})
@ActiveProfiles("test")
@Import(SecurityConfiguration.class)
class FoodCartSecurityTest {
    @Autowired MockMvc http;
    @MockitoBean FoodService foods;
    @MockitoBean CartService cart;

    @Test void developmentFoodRemainsAvailableButCartRequiresAuthProfile() throws Exception {
        http.perform(get("/api/foods")).andExpect(status().isOk());
        http.perform(delete("/api/foods/1")).andExpect(status().isNoContent());
        http.perform(get("/api/cart").header("X-User-Id", "4")).andExpect(status().isForbidden());
        verify(foods).deleteFood(1L);
        verifyNoInteractions(cart);
    }

    @Test void unrelatedEndpointsRemainDenied() throws Exception {
        http.perform(get("/api/orders")).andExpect(status().isForbidden());
        verifyNoInteractions(foods, cart);
    }
}

@WebMvcTest({FoodController.class, CartController.class})
@ActiveProfiles("auth")
@Import(SecurityConfiguration.class)
class AuthFoodCartSecurityTest {
    @Autowired MockMvc http;
    @MockitoBean FoodService foods;
    @MockitoBean CartService cart;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LoginRateLimiter limiter;

    @BeforeEach void tokens() {
        for (String role : new String[]{"CUSTOMER", "EMPLOYEE", "ADMIN"}) {
            when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg", "RS256")
                    .subject("4").claim("role", role).issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300)).build());
        }
    }

    @Test void cartUsesJwtOwnerAndIgnoresSpoofedHeader() throws Exception {
        http.perform(get("/api/cart").header("Authorization", "Bearer CUSTOMER").header("X-User-Id", "5"))
                .andExpect(status().isOk());
        verify(cart).getCart(4L);
    }

    @Test void onlyCustomersCanUseAnyCartEndpoint() throws Exception {
        for (String role : new String[]{"EMPLOYEE", "ADMIN"}) {
            http.perform(get("/api/cart").header("Authorization", "Bearer " + role)).andExpect(status().isForbidden());
            http.perform(post("/api/cart/items").header("Authorization", "Bearer " + role)
                    .contentType("application/json").content("{\"foodId\":1,\"quantity\":1}"))
                    .andExpect(status().isForbidden());
            http.perform(put("/api/cart/items/1").header("Authorization", "Bearer " + role)
                    .contentType("application/json").content("{\"quantity\":1}"))
                    .andExpect(status().isForbidden());
            http.perform(delete("/api/cart/items/1").header("Authorization", "Bearer " + role))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(cart);
    }

    @Test void invalidQuantitiesAndFoodIdsNeverReachCartService() throws Exception {
        for (String body : new String[]{"{\"foodId\":0,\"quantity\":1}", "{\"foodId\":1,\"quantity\":0}"}) {
            http.perform(post("/api/cart/items").header("Authorization", "Bearer CUSTOMER")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(cart);
    }

    @Test void menuRemainsPublicWithAuthEnabled() throws Exception {
        http.perform(get("/api/foods")).andExpect(status().isOk());
        verify(foods).getActiveFoods(null);
    }

    @Test void foodWritesAndCartRequireTokenWithAuthEnabled() throws Exception {
        http.perform(post("/api/foods").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        http.perform(put("/api/foods/1").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        http.perform(delete("/api/foods/1")).andExpect(status().isUnauthorized());
        http.perform(get("/api/cart").header("X-User-Id", "4")).andExpect(status().isUnauthorized());
        verifyNoInteractions(foods, cart);
    }
}
