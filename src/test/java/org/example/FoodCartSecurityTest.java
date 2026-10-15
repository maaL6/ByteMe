package org.example;

import java.time.Instant;
import org.example.cart.controller.CartController;
import org.example.cart.service.CartService;
import org.example.config.SecurityConfiguration;
import org.example.food.controller.FoodController;
import org.example.food.service.FoodService;
import org.example.platform.security.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({FoodController.class, CartController.class})
@ActiveProfiles("test")
@Import(SecurityConfiguration.class)
class FoodCartSecurityTest {
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

    @Test void publicMenuWorksWithoutTokenButFoodWritesRequireAuthentication() throws Exception {
        http.perform(get("/api/foods")).andExpect(status().isOk());
        http.perform(post("/api/foods").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        http.perform(put("/api/foods/1").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        http.perform(delete("/api/foods/1")).andExpect(status().isUnauthorized());
        verify(foods).getActiveFoods(null);
    }

    @Test void onlyAdminCanWriteFoods() throws Exception {
        String body = "{\"categoryId\":1,\"name\":\"Pho\",\"price\":50000,\"stockQuantity\":10}";
        for (String role : new String[]{"CUSTOMER", "EMPLOYEE"}) {
            http.perform(post("/api/foods").header("Authorization", "Bearer " + role)
                    .contentType("application/json").content(body)).andExpect(status().isForbidden());
            http.perform(put("/api/foods/1").header("Authorization", "Bearer " + role)
                    .contentType("application/json").content(body)).andExpect(status().isForbidden());
            http.perform(delete("/api/foods/1").header("Authorization", "Bearer " + role))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(foods);
        http.perform(delete("/api/foods/1").header("Authorization", "Bearer ADMIN"))
                .andExpect(status().isNoContent());
        verify(foods).deleteFood(1L);
    }

    @Test void cartUsesJwtOwnerAndIgnoresSpoofedOwnerHeader() throws Exception {
        http.perform(get("/api/cart").header("X-User-Id", "4")).andExpect(status().isUnauthorized());
        http.perform(get("/api/cart").header("Authorization", "Bearer CUSTOMER")
                .header("X-User-Id", "5")).andExpect(status().isOk());
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

    @Test void foodAndCartErrorsUseTheSharedErrorBody() throws Exception {
        when(foods.getFoodById(999L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Food not found"));
        http.perform(get("/api/foods/999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND")).andExpect(jsonPath("$.path").value("/api/foods/999"));
        http.perform(get("/api/foods/search")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        http.perform(post("/api/cart/items").header("Authorization", "Bearer CUSTOMER")
                .contentType("application/json").content("{\"foodId\":0,\"quantity\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("foodId"));
    }
}
