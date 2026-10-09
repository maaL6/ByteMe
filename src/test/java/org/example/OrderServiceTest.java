package org.example;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.example.order.domain.*;
import org.example.order.integration.CartCheckoutPlaceholder;
import org.example.order.service.*;
import org.example.order.service.port.*;
import org.example.shared.api.AuthenticatedUser;
import org.example.shared.api.Role;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderServiceTest {
    static final Instant NOW = Instant.parse("2026-10-08T02:00:00Z");
    static final AuthenticatedUser CUSTOMER = new AuthenticatedUser("4", Role.CUSTOMER);
    static final AuthenticatedUser EMPLOYEE = new AuthenticatedUser("2", Role.EMPLOYEE);
    OrderRepository orders;
    CartCheckout carts;
    OrderFoodRepository foods;
    OrderEvents events;
    OrderService service;

    @BeforeEach void setUp() {
        orders = mock(OrderRepository.class);
        carts = mock(CartCheckout.class);
        foods = mock(OrderFoodRepository.class);
        events = mock(OrderEvents.class);
        UnitOfWork direct = new UnitOfWork() {
            @Override public <T> T execute(java.util.function.Supplier<T> action) { return action.get(); }
        };
        service = new OrderService(orders, carts, foods, direct, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static CreateOrderCommand dineIn() {
        return new CreateOrderCommand(OrderType.DINE_IN, " B05 ", null, null, null, PaymentMethod.CASH, " Ít cay ");
    }

    static Order order(OrderType type, OrderStatus status) {
        boolean confirmed = status != OrderStatus.PENDING;
        return new Order(1001L, 4, type, status, type == OrderType.DINE_IN ? "B05" : null,
                type == OrderType.DELIVERY ? "Khách" : null, type == OrderType.DELIVERY ? "0900000004" : null,
                type == OrderType.DELIVERY ? "Địa chỉ" : null, PaymentMethod.BANK_TRANSFER, null,
                confirmed ? 2L : null, confirmed ? NOW.minusSeconds(60) : null,
                status == OrderStatus.CANCELLED ? NOW.minusSeconds(30) : null,
                status == OrderStatus.COMPLETED ? NOW.minusSeconds(30) : null, NOW.minusSeconds(120), NOW.minusSeconds(30),
                List.of(new OrderItem(1, "Tên snapshot", new BigDecimal("65000.00"), 2)));
    }

    @Test void checkoutUsesServerPricesSortedLocksAndPublishesOnlyAfterCartClear() {
        when(carts.lockByUserId(4)).thenReturn(new CartCheckout.CartSnapshot(8,
                List.of(new CartCheckout.CartItem(5, 1), new CartCheckout.CartItem(1, 2))));
        when(foods.lockById(1)).thenReturn(new OrderFoodRepository.Food(1, "Phở", new BigDecimal("65000.00"), 10, true));
        when(foods.lockById(5)).thenReturn(new OrderFoodRepository.Food(5, "Trà", new BigDecimal("25000.00"), 5, true));
        when(orders.save(any())).thenAnswer(call -> {
            Order input = call.getArgument(0);
            assertEquals("B05", input.tableNumber());
            assertEquals("Ít cay", input.note());
            assertEquals(OrderStatus.PENDING, input.status());
            return new Order(1010L, input.userId(), input.orderType(), input.status(), input.tableNumber(), null,
                    null, null, input.paymentMethod(), input.note(), null, null, null, null,
                    input.createdAt(), input.updatedAt(), input.items());
        });
        var result = service.create(CUSTOMER, dineIn());
        assertEquals(new BigDecimal("155000.00"), result.totalAmount());
        var sequence = inOrder(carts, foods, orders, events);
        sequence.verify(carts).lockByUserId(4);
        sequence.verify(foods).lockById(1);
        sequence.verify(foods).lockById(5);
        sequence.verify(orders).save(any());
        sequence.verify(foods).decreaseStock(1, 2);
        sequence.verify(foods).decreaseStock(5, 1);
        sequence.verify(carts).clearItems(8);
        sequence.verify(events).publish(new OrderEvents.OrderCreated(1010, 4, 8));
    }

    @Test void placeholderFailsExplicitlyAndDoesNotWriteOrders() {
        when(carts.lockByUserId(4)).thenAnswer(call -> new CartCheckoutPlaceholder().lockByUserId(4));
        assertCode("CART_NOT_IMPLEMENTED", () -> service.create(CUSTOMER, dineIn()));
        verifyNoInteractions(orders, foods, events);
    }

    @Test void validatesServingInformationAndPaymentBeforeCallingCart() {
        for (var input : List.of(
                new CreateOrderCommand(OrderType.DINE_IN, null, null, null, null, PaymentMethod.CASH, null),
                new CreateOrderCommand(OrderType.DINE_IN, "B05", "Khách", null, null, PaymentMethod.CASH, null),
                new CreateOrderCommand(OrderType.DINE_IN, "B05", null, null, null, PaymentMethod.COD, null),
                new CreateOrderCommand(OrderType.DELIVERY, null, "Khách", "0900000004", "Địa chỉ", PaymentMethod.CASH, null),
                new CreateOrderCommand(OrderType.DELIVERY, "B05", "Khách", "0900000004", "Địa chỉ", PaymentMethod.COD, null),
                new CreateOrderCommand(OrderType.DELIVERY, null, "Khách", "0900000004", " ", PaymentMethod.COD, null))) {
            assertCode("VALIDATION_ERROR", () -> service.create(CUSTOMER, input));
        }
        verifyNoInteractions(carts, orders, foods, events);
    }

    @Test void invalidCartOrInsufficientStockNeverSavesAnOrder() {
        when(carts.lockByUserId(4)).thenReturn(new CartCheckout.CartSnapshot(8, List.of()));
        assertCode("EMPTY_CART", () -> service.create(CUSTOMER, dineIn()));
        when(carts.lockByUserId(4)).thenReturn(new CartCheckout.CartSnapshot(8, List.of(new CartCheckout.CartItem(1, 2))));
        when(foods.lockById(1)).thenReturn(new OrderFoodRepository.Food(1, "Phở", new BigDecimal("65000.00"), 1, true));
        assertCode("INSUFFICIENT_STOCK", () -> service.create(CUSTOMER, dineIn()));
        when(foods.lockById(1)).thenReturn(new OrderFoodRepository.Food(1, "Phở", new BigDecimal("65000.00"), 10, false));
        assertCode("FOOD_UNAVAILABLE", () -> service.create(CUSTOMER, dineIn()));
        verifyNoInteractions(orders, events);
        verify(carts, never()).clearItems(anyLong());
    }

    @Test void customerListIsAlwaysScopedAndEmployeeDefaultsToActiveOrders() {
        service.list(CUSTOMER, null, null, 0, 20);
        verify(orders).findAll(4L, List.of(), null, 0, 20);
        service.list(EMPLOYEE, null, null, 0, 20);
        verify(orders).findAll(null, List.of(OrderStatus.PENDING, OrderStatus.CONFIRMED,
                OrderStatus.PROCESSING, OrderStatus.SHIPPING), null, 0, 20);
        service.list(EMPLOYEE, OrderStatus.PENDING, OrderType.DINE_IN, 1, 5);
        verify(orders).findAll(null, List.of(OrderStatus.PENDING), OrderType.DINE_IN, 1, 5);
        assertCode("VALIDATION_ERROR", () -> service.list(CUSTOMER, null, null, -1, 20));
        assertCode("VALIDATION_ERROR", () -> service.list(CUSTOMER, null, null, 0, 101));
    }

    @Test void ownerAndRoleAreCheckedBeforeAnyMutation() {
        when(orders.findById(1001)).thenReturn(Optional.of(order(OrderType.DELIVERY, OrderStatus.PENDING)));
        when(orders.lockById(1001)).thenReturn(Optional.of(order(OrderType.DELIVERY, OrderStatus.PENDING)));
        var other = new AuthenticatedUser("5", Role.CUSTOMER);
        assertCode("FORBIDDEN", () -> service.get(other, 1001));
        assertCode("FORBIDDEN", () -> service.cancel(other, 1001));
        assertCode("FORBIDDEN", () -> service.confirm(CUSTOMER, 1001));
        assertCode("FORBIDDEN", () -> service.create(EMPLOYEE, dineIn()));
        assertCode("FORBIDDEN", () -> service.list(new AuthenticatedUser("1", Role.ADMIN), null, null, 0, 20));
        assertCode("ORDER_NOT_FOUND", () -> service.get(CUSTOMER, 9999));
        verify(orders, never()).save(any());
        verifyNoInteractions(foods, carts, events);
    }

    @Test void everyStatusTransitionMatchesServingTypeAndConfirmationMetadata() {
        when(orders.save(any())).thenAnswer(call -> call.getArgument(0));
        for (var type : OrderType.values()) {
            for (var current : OrderStatus.values()) {
                var existing = order(type, current);
                when(orders.lockById(1001)).thenReturn(Optional.of(existing));
                for (var next : OrderStatus.values()) {
                    boolean allowed = (current == OrderStatus.PENDING && (next == OrderStatus.CONFIRMED || next == OrderStatus.CANCELLED))
                            || (current == OrderStatus.CONFIRMED && (next == OrderStatus.PROCESSING || next == OrderStatus.CANCELLED))
                            || (current == OrderStatus.PROCESSING && (next == OrderStatus.CANCELLED
                                || next == (type == OrderType.DINE_IN ? OrderStatus.COMPLETED : OrderStatus.SHIPPING)))
                            || (current == OrderStatus.SHIPPING && next == OrderStatus.COMPLETED);
                    if (allowed) {
                        var result = service.updateStatus(EMPLOYEE, 1001, next);
                        assertEquals(next, result.status());
                        if (next == OrderStatus.CONFIRMED) {
                            assertEquals(2L, result.confirmedBy());
                            assertEquals(NOW, result.confirmedAt());
                        } else assertEquals(existing.confirmedBy(), result.confirmedBy());
                        if (next == OrderStatus.CANCELLED) assertEquals(NOW, result.cancelledAt());
                        if (next == OrderStatus.COMPLETED) assertEquals(NOW, result.completedAt());
                    } else assertCode("INVALID_ORDER_TRANSITION", () -> service.updateStatus(EMPLOYEE, 1001, next));
                }
            }
        }
    }

    @Test void customerCanOnlyCancelPendingAndCannotRestoreStockTwice() {
        when(orders.lockById(1001)).thenReturn(Optional.of(order(OrderType.DELIVERY, OrderStatus.PENDING)));
        when(orders.save(any())).thenAnswer(call -> call.getArgument(0));
        var cancelled = service.cancel(CUSTOMER, 1001);
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertNull(cancelled.confirmedBy());
        verify(foods).lockById(1);
        verify(foods).restoreStock(1, 2);
        when(orders.lockById(1001)).thenReturn(Optional.of(cancelled));
        assertCode("INVALID_ORDER_TRANSITION", () -> service.cancel(CUSTOMER, 1001));
        when(orders.lockById(1001)).thenReturn(Optional.of(order(OrderType.DELIVERY, OrderStatus.CONFIRMED)));
        assertCode("INVALID_ORDER_TRANSITION", () -> service.cancel(CUSTOMER, 1001));
        verify(foods, times(1)).restoreStock(1, 2);
    }

    static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(OrderFailure.class, action).code());
    }
}
