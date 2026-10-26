package org.example;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.example.common.BusinessException;
import org.example.config.SecurityConfiguration;
import org.example.order.controller.OrderController;
import org.example.order.dto.OrderDto;
import org.example.order.dto.OrderPage;
import org.example.order.domain.*;
import org.example.order.service.OrderService;
import org.example.platform.security.LoginRateLimiter;
import org.example.shared.api.Role;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@ActiveProfiles("test")
@Import(SecurityConfiguration.class)
class OrderWebTest {
    @Autowired MockMvc http;
    @MockitoBean OrderService service;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LoginRateLimiter limiter;

    @BeforeEach void tokens() {
        for (var role : Role.values()) {
            token(role.name().toLowerCase(), role == Role.CUSTOMER ? "4" : role == Role.EMPLOYEE ? "2" : "1", role);
        }
        token("fixture", "customer-a", Role.CUSTOMER);
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("invalid"));
    }

    private void token(String value, String subject, Role role) {
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(subject).claim("role", role.name())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
    }

    private static OrderDto order(OrderType type, OrderStatus status) {
        return OrderDto.fromEntity(OrderServiceTest.order(type, status));
    }

    @Test void unauthenticatedAndWrongRolesNeverReachService() throws Exception {
        http.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        http.perform(get("/api/orders").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        http.perform(get("/api/orders").header("Authorization", "Bearer admin")).andExpect(status().isForbidden());
        http.perform(get("/api/orders/1001").header("Authorization", "Bearer admin")).andExpect(status().isForbidden());
        http.perform(post("/api/orders/1001/confirm").header("Authorization", "Bearer customer")).andExpect(status().isForbidden());
        http.perform(put("/api/orders/1001/status").header("Authorization", "Bearer customer")
                .contentType("application/json").content("{\"status\":\"CONFIRMED\"}")).andExpect(status().isForbidden());
        http.perform(delete("/api/orders/1001").header("Authorization", "Bearer employee")).andExpect(status().isForbidden());
        http.perform(post("/api/orders").header("Authorization", "Bearer employee")
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        http.perform(post("/api/orders").header("Authorization", "Bearer admin")
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void subjectThatIsNotADatabaseIdIsForbidden() throws Exception {
        http.perform(get("/api/orders").header("Authorization", "Bearer fixture"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(service);
    }

    @Test void creationReturnsLocationAndServerCalculatedSnapshot() throws Exception {
        when(service.create(anyLong(), any())).thenReturn(order(OrderType.DINE_IN, OrderStatus.PENDING));
        http.perform(post("/api/orders").header("Authorization", "Bearer customer").contentType("application/json")
                .content("{\"orderType\":\"DINE_IN\",\"tableNumber\":\"B05\",\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/orders/1001"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalAmount").value(130000)).andExpect(jsonPath("$.items[0].lineTotal").value(130000))
                .andExpect(jsonPath("$.items[0].unitPrice").value(65000))
                .andExpect(jsonPath("$.items[0].foodName").value("Tên snapshot"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-08T01:58:00Z"))
                .andExpect(jsonPath("$.userId").value(4)).andExpect(jsonPath("$.tableNumber").value("B05"));
        verify(service).create(eq(4L), eq(new org.example.order.dto.CreateOrderRequest(
                OrderType.DINE_IN, "B05", null, null, null, PaymentMethod.CASH, null)));
    }

    @Test void listAndDetailReturnPageAndSnapshot() throws Exception {
        var order = order(OrderType.DELIVERY, OrderStatus.PENDING);
        when(service.list(2L, Role.EMPLOYEE, OrderStatus.PENDING, OrderType.DELIVERY, 1, 5))
                .thenReturn(new OrderPage(List.of(order), 1, 5, 6));
        http.perform(get("/api/orders?status=PENDING&orderType=DELIVERY&page=1&size=5")
                .header("Authorization", "Bearer employee")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.content[0].items[0].foodName").value("Tên snapshot"));
        when(service.list(4L, Role.CUSTOMER, null, null, 0, 20)).thenReturn(new OrderPage(List.of(), 0, 20, 0));
        http.perform(get("/api/orders").header("Authorization", "Bearer customer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        when(service.get(4L, Role.CUSTOMER, 1001L)).thenReturn(order);
        http.perform(get("/api/orders/1001").header("Authorization", "Bearer customer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderType").value("DELIVERY"))
                .andExpect(jsonPath("$.recipientName").value("Khách"));
    }

    @Test void allThreeStatusEndpointsCallTheCorrectUseCase() throws Exception {
        when(service.cancel(4L, 1001L)).thenReturn(order(OrderType.DELIVERY, OrderStatus.CANCELLED));
        when(service.confirm(2L, 1001L)).thenReturn(order(OrderType.DELIVERY, OrderStatus.CONFIRMED));
        when(service.updateStatus(2L, 1001L, OrderStatus.SHIPPING))
                .thenReturn(order(OrderType.DELIVERY, OrderStatus.SHIPPING));
        http.perform(delete("/api/orders/1001").header("Authorization", "Bearer customer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        http.perform(post("/api/orders/1001/confirm").header("Authorization", "Bearer employee"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmedBy").value(2));
        http.perform(put("/api/orders/1001/status").header("Authorization", "Bearer employee")
                .contentType("application/json").content("{\"status\":\"SHIPPING\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPING"));
        verify(service).cancel(4L, 1001L);
        verify(service).confirm(2L, 1001L);
        verify(service).updateStatus(2L, 1001L, OrderStatus.SHIPPING);
    }

    @Test void businessFailuresHaveExplicitJsonStatus() throws Exception {
        when(service.create(anyLong(), any())).thenThrow(BusinessException.invalid("EMPTY_CART", "Giỏ hàng đang rỗng."));
        http.perform(post("/api/orders").header("Authorization", "Bearer customer")
                .contentType("application/json")
                .content("{\"orderType\":\"DINE_IN\",\"tableNumber\":\"B05\",\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_CART"))
                .andExpect(jsonPath("$.path").value("/api/orders"))
                .andExpect(header().string("Cache-Control", "no-store"));
        for (var error : List.of(BusinessException.forbidden("FORBIDDEN", "forbidden"),
                BusinessException.notFound("ORDER_NOT_FOUND", "missing"),
                BusinessException.conflict("INVALID_ORDER_TRANSITION", "conflict"),
                BusinessException.conflict("INSUFFICIENT_STOCK", "stock"))) {
            doThrow(error).when(service).get(anyLong(), any(), eq(1001L));
            int expected = switch (error.type()) {
                case INVALID -> 400;
                case FORBIDDEN -> 403;
                case NOT_FOUND -> 404;
                case CONFLICT -> 409;
            };
            http.perform(get("/api/orders/1001").header("Authorization", "Bearer customer"))
                    .andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(error.code()))
                    .andExpect(jsonPath("$.message").value(error.getMessage()));
        }
    }

    @Test void lockConflictsAre409RetryableInsteadOf500() throws Exception {
        for (var error : List.of(new CannotAcquireLockException("Deadlock found when trying to get lock"),
                new PessimisticLockingFailureException("Lock wait timeout exceeded"))) {
            doThrow(error).when(service).create(anyLong(), any());
            http.perform(post("/api/orders").header("Authorization", "Bearer customer")
                    .contentType("application/json")
                    .content("{\"orderType\":\"DINE_IN\",\"tableNumber\":\"B05\",\"paymentMethod\":\"CASH\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"))
                    .andExpect(jsonPath("$.path").value("/api/orders"))
                    .andExpect(header().string("Cache-Control", "no-store"));
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
        http.perform(get("/api/orders?page=abc").header("Authorization", "Bearer customer"))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/orders/abc").header("Authorization", "Bearer customer"))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/orders").header("Authorization", "Bearer customer")
                .contentType("application/json").content("{bad")).andExpect(status().isBadRequest());
        http.perform(post("/api/orders").header("Authorization", "Bearer customer")
                .contentType("application/json")).andExpect(status().isBadRequest());
        http.perform(put("/api/orders/1001/status").header("Authorization", "Bearer employee")
                .contentType("application/json").content("{\"status\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test void missingOrTooLongFieldsAre400WithFieldErrors() throws Exception {
        for (String[] c : new String[][]{
                {"{\"tableNumber\":\"B05\",\"paymentMethod\":\"CASH\"}", "orderType"},
                {"{\"orderType\":\"DINE_IN\",\"tableNumber\":\"B05\"}", "paymentMethod"},
                {"{\"orderType\":\"DINE_IN\",\"tableNumber\":\"" + "B".repeat(21) + "\",\"paymentMethod\":\"CASH\"}", "tableNumber"},
                {"{\"orderType\":\"DINE_IN\",\"tableNumber\":\"B05\",\"paymentMethod\":\"CASH\",\"note\":\"" + "x".repeat(501) + "\"}", "note"}}) {
            http.perform(post("/api/orders").header("Authorization", "Bearer customer")
                    .contentType("application/json").content(c[0]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(c[1]));
        }
        http.perform(put("/api/orders/1001/status").header("Authorization", "Bearer employee")
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("status"));
        verifyNoInteractions(service);
    }
}
