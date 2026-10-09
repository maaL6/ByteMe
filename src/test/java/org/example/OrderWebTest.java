package org.example;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.example.config.SecurityConfiguration;
import org.example.order.domain.*;
import org.example.order.service.OrderFailure;
import org.example.order.service.OrderService;
import org.example.order.service.port.OrderRepository.OrderPage;
import org.example.order.web.OrderController;
import org.example.platform.security.LoginRateLimiter;
import org.example.shared.api.AuthenticatedUser;
import org.example.shared.api.Role;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@ActiveProfiles("auth")
@Import(SecurityConfiguration.class)
class OrderWebTest {
    @Autowired MockMvc http;
    @MockitoBean OrderService service;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LoginRateLimiter limiter;

    @BeforeEach void tokens() {
        for (var role : Role.values()) {
            String token = role.name().toLowerCase();
            when(decoder.decode(token)).thenReturn(Jwt.withTokenValue(token).header("alg", "RS256")
                    .subject(role == Role.CUSTOMER ? "4" : role == Role.EMPLOYEE ? "2" : "1")
                    .claim("role", role.name()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
        }
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("invalid"));
    }

    @Test void unauthenticatedAndWrongRolesNeverReachService() throws Exception {
        http.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        http.perform(get("/api/orders").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        http.perform(get("/api/orders").header("Authorization", "Bearer admin")).andExpect(status().isForbidden());
        http.perform(post("/api/orders/1001/confirm").header("Authorization", "Bearer customer")).andExpect(status().isForbidden());
        http.perform(put("/api/orders/1001/status").header("Authorization", "Bearer customer")
                .contentType("application/json").content("{\"status\":\"CONFIRMED\"}")).andExpect(status().isForbidden());
        http.perform(delete("/api/orders/1001").header("Authorization", "Bearer employee")).andExpect(status().isForbidden());
        http.perform(post("/api/orders").header("Authorization", "Bearer employee")
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void creationReturnsLocationAndServerCalculatedSnapshot() throws Exception {
        when(service.create(any(), any())).thenReturn(OrderServiceTest.order(OrderType.DINE_IN, OrderStatus.PENDING));
        http.perform(post("/api/orders").header("Authorization", "Bearer customer").contentType("application/json")
                .content("{\"orderType\":\"DINE_IN\",\"tableNumber\":\"B05\",\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/orders/1001"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalAmount").value(130000)).andExpect(jsonPath("$.items[0].lineTotal").value(130000));
        verify(service).create(eq(new AuthenticatedUser("4", Role.CUSTOMER)), any());
    }

    @Test void listAndDetailReturnPageAndSnapshot() throws Exception {
        var order = OrderServiceTest.order(OrderType.DELIVERY, OrderStatus.PENDING);
        when(service.list(any(), eq(OrderStatus.PENDING), eq(OrderType.DELIVERY), eq(1), eq(5)))
                .thenReturn(new OrderPage(List.of(order), 1, 5, 6));
        http.perform(get("/api/orders?status=PENDING&orderType=DELIVERY&page=1&size=5")
                .header("Authorization", "Bearer employee")).andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.content[0].items[0].foodName").value("Tên snapshot"));
        when(service.get(any(), eq(1001L))).thenReturn(order);
        http.perform(get("/api/orders/1001").header("Authorization", "Bearer customer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderType").value("DELIVERY"));
    }

    @Test void allThreeStatusEndpointsCallTheCorrectUseCase() throws Exception {
        when(service.cancel(any(), eq(1001L))).thenReturn(OrderServiceTest.order(OrderType.DELIVERY, OrderStatus.CANCELLED));
        when(service.confirm(any(), eq(1001L))).thenReturn(OrderServiceTest.order(OrderType.DELIVERY, OrderStatus.CONFIRMED));
        when(service.updateStatus(any(), eq(1001L), eq(OrderStatus.SHIPPING)))
                .thenReturn(OrderServiceTest.order(OrderType.DELIVERY, OrderStatus.SHIPPING));
        http.perform(delete("/api/orders/1001").header("Authorization", "Bearer customer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        http.perform(post("/api/orders/1001/confirm").header("Authorization", "Bearer employee"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmedBy").value(2));
        http.perform(put("/api/orders/1001/status").header("Authorization", "Bearer employee")
                .contentType("application/json").content("{\"status\":\"SHIPPING\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPING"));
        verify(service).cancel(new AuthenticatedUser("4", Role.CUSTOMER), 1001);
        verify(service).confirm(new AuthenticatedUser("2", Role.EMPLOYEE), 1001);
        verify(service).updateStatus(new AuthenticatedUser("2", Role.EMPLOYEE), 1001, OrderStatus.SHIPPING);
    }

    @Test void cartAndBusinessFailuresHaveExplicitJsonStatus() throws Exception {
        when(service.create(any(), any())).thenThrow(new OrderFailure("EMPTY_CART", "Giỏ hàng đang rỗng."));
        http.perform(post("/api/orders").header("Authorization", "Bearer customer")
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_CART"))
                .andExpect(header().string("Cache-Control", "no-store"));
        for (var error : List.of(new OrderFailure("FORBIDDEN", "forbidden"),
                new OrderFailure("ORDER_NOT_FOUND", "missing"), new OrderFailure("INVALID_ORDER_TRANSITION", "conflict"))) {
            when(service.get(any(), eq(1001L))).thenThrow(error);
            int expected = error.code().equals("FORBIDDEN") ? 403 : error.code().equals("ORDER_NOT_FOUND") ? 404 : 409;
            http.perform(get("/api/orders/1001").header("Authorization", "Bearer customer"))
                    .andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(error.code()));
        }
    }

    @Test void numericEnumsAre400AndNeverReachService() throws Exception {
        for (String value : List.of("1", "\"1\"")) {
            http.perform(put("/api/orders/1001/status").header("Authorization", "Bearer employee")
                    .contentType("application/json").content("{\"status\":" + value + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        for (String body : List.of(
                "{\"orderType\":0,\"tableNumber\":\"B05\",\"paymentMethod\":\"CASH\"}",
                "{\"orderType\":\"0\",\"tableNumber\":\"B05\",\"paymentMethod\":\"CASH\"}",
                "{\"orderType\":\"DINE_IN\",\"tableNumber\":\"B05\",\"paymentMethod\":0}",
                "{\"orderType\":\"DINE_IN\",\"tableNumber\":\"B05\",\"paymentMethod\":\"0\"}")) {
            http.perform(post("/api/orders").header("Authorization", "Bearer customer")
                    .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(service);
    }

    @Test void malformedBodiesIdsAndEnumsAre400() throws Exception {
        http.perform(get("/api/orders?status=UNKNOWN").header("Authorization", "Bearer customer"))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/orders/abc").header("Authorization", "Bearer customer"))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/orders").header("Authorization", "Bearer customer")
                .contentType("application/json").content("{bad")).andExpect(status().isBadRequest());
        http.perform(put("/api/orders/1001/status").header("Authorization", "Bearer employee")
                .contentType("application/json").content("{\"status\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }
}
