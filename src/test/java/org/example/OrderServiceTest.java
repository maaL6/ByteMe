package org.example;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.example.cart.service.CartService;
import org.example.common.BusinessException;
import org.example.food.dto.FoodDto;
import org.example.food.service.FoodService;
import org.example.order.dto.*;
import org.example.order.entity.*;
import org.example.order.repository.OrderRepository;
import org.example.order.service.OrderService;
import org.example.shared.api.Role;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderServiceTest {
    static final Instant NOW = Instant.parse("2026-10-08T02:00:00Z");
    static final long CUSTOMER = 4;
    static final long EMPLOYEE = 2;
    OrderRepository orders;
    CartService carts;
    FoodService foods;
    OrderService service;

    @BeforeEach void setUp() {
        orders = mock(OrderRepository.class);
        carts = mock(CartService.class);
        foods = mock(FoodService.class);
        service = new OrderService(orders, carts, foods, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static CreateOrderRequest dineIn() {
        return new CreateOrderRequest(OrderType.DINE_IN, " B05 ", null, null, null, PaymentMethod.CASH, " Ít cay ");
    }

    static Order order(OrderType type, OrderStatus status) {
        boolean confirmed = status != OrderStatus.PENDING;
        Order order = Order.builder().id(1001L).userId(4L).orderType(type).status(status)
                .tableNumber(type == OrderType.DINE_IN ? "B05" : null)
                .recipientName(type == OrderType.DELIVERY ? "Khách" : null)
                .shippingPhone(type == OrderType.DELIVERY ? "0900000004" : null)
                .shippingAddress(type == OrderType.DELIVERY ? "Địa chỉ" : null)
                .paymentMethod(PaymentMethod.BANK_TRANSFER)
                .confirmedBy(confirmed ? 2L : null).confirmedAt(confirmed ? NOW.minusSeconds(60) : null)
                .cancelledAt(status == OrderStatus.CANCELLED ? NOW.minusSeconds(30) : null)
                .completedAt(status == OrderStatus.COMPLETED ? NOW.minusSeconds(30) : null)
                .createdAt(NOW.minusSeconds(120)).updatedAt(NOW.minusSeconds(30)).build();
        order.getItems().add(OrderItem.builder().order(order).foodId(1L).foodName("Tên snapshot")
                .unitPrice(new BigDecimal("65000.00")).quantity(2).build());
        return order;
    }

    static FoodDto food(long id, String name, String price) {
        return new FoodDto(id, 1L, name, null, new BigDecimal(price), null, 10, "ACTIVE");
    }

    static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(BusinessException.class, action).code());
    }

    @Test void checkoutUsesServerPricesLocksInFoodIdOrderAndClearsCartLast() {
        var cart = new TreeMap<Long, Integer>();
        cart.put(5L, 1);
        cart.put(1L, 2);
        when(carts.lockItemsForCheckout(4)).thenReturn(cart);
        when(foods.reserveStock(1, 2)).thenReturn(food(1, "Phở", "65000.00"));
        when(foods.reserveStock(5, 1)).thenReturn(food(5, "Trà", "25000.00"));
        when(orders.save(any())).thenAnswer(call -> {
            Order input = call.getArgument(0);
            input.setId(1010L);
            return input;
        });

        var result = service.create(CUSTOMER, dineIn());

        assertEquals(1010L, result.id());
        assertEquals(4L, result.userId());
        assertEquals(OrderStatus.PENDING, result.status());
        assertEquals("B05", result.tableNumber());
        assertEquals("Ít cay", result.note());
        assertEquals(NOW, result.createdAt());
        assertEquals(List.of(1L, 5L), result.items().stream().map(OrderItemDto::foodId).toList());
        assertEquals("Phở", result.items().getFirst().foodName());
        assertEquals(new BigDecimal("130000.00"), result.items().getFirst().lineTotal());
        assertEquals(new BigDecimal("155000.00"), result.totalAmount());
        var sequence = inOrder(carts, foods, orders);
        sequence.verify(carts).lockItemsForCheckout(4);
        sequence.verify(foods).reserveStock(1, 2);
        sequence.verify(foods).reserveStock(5, 1);
        sequence.verify(orders).save(any());
        sequence.verify(carts).clearCart(4);
    }

    @Test void missingCartDoesNotTouchFoodsOrOrders() {
        when(carts.lockItemsForCheckout(4)).thenThrow(BusinessException.notFound("CART_NOT_FOUND", "missing"));
        assertCode("CART_NOT_FOUND", () -> service.create(CUSTOMER, dineIn()));
        verifyNoInteractions(orders, foods);
    }

    @Test void validatesServingInformationAndPaymentBeforeCallingCart() {
        for (var input : java.util.Arrays.asList(
                null,
                new CreateOrderRequest(null, "B05", null, null, null, PaymentMethod.CASH, null),
                new CreateOrderRequest(OrderType.DINE_IN, "B05", null, null, null, null, null),
                new CreateOrderRequest(OrderType.DINE_IN, null, null, null, null, PaymentMethod.CASH, null),
                new CreateOrderRequest(OrderType.DINE_IN, " ", null, null, null, PaymentMethod.CASH, null),
                new CreateOrderRequest(OrderType.DINE_IN, "B".repeat(21), null, null, null, PaymentMethod.CASH, null),
                new CreateOrderRequest(OrderType.DINE_IN, "B05", "Khách", null, null, PaymentMethod.CASH, null),
                new CreateOrderRequest(OrderType.DINE_IN, "B05", null, null, null, PaymentMethod.COD, null),
                new CreateOrderRequest(OrderType.DINE_IN, "B05", null, null, null, PaymentMethod.CASH, "x".repeat(501)),
                new CreateOrderRequest(OrderType.DELIVERY, null, "Khách", "0900000004", "Địa chỉ", PaymentMethod.CASH, null),
                new CreateOrderRequest(OrderType.DELIVERY, "B05", "Khách", "0900000004", "Địa chỉ", PaymentMethod.COD, null),
                new CreateOrderRequest(OrderType.DELIVERY, null, null, "0900000004", "Địa chỉ", PaymentMethod.COD, null),
                new CreateOrderRequest(OrderType.DELIVERY, null, "Khách", "0900000004", " ", PaymentMethod.COD, null))) {
            assertCode("VALIDATION_ERROR", () -> service.create(CUSTOMER, input));
        }
        verifyNoInteractions(carts, orders, foods);
    }

    @Test void deliveryKeepsTrimmedRecipientAndBlankNoteBecomesNull() {
        when(carts.lockItemsForCheckout(4)).thenReturn(new TreeMap<>(java.util.Map.of(1L, 1)));
        when(foods.reserveStock(1, 1)).thenReturn(food(1, "Phở", "65000.00"));
        when(orders.save(any())).thenAnswer(call -> call.getArgument(0));
        var result = service.create(CUSTOMER, new CreateOrderRequest(OrderType.DELIVERY, null, " Khách ",
                " 0900000004 ", " Địa chỉ ", PaymentMethod.BANK_TRANSFER, "  "));
        assertEquals("Khách", result.recipientName());
        assertEquals("0900000004", result.shippingPhone());
        assertEquals("Địa chỉ", result.shippingAddress());
        assertNull(result.tableNumber());
        assertNull(result.note());
    }

    @Test void emptyCartOrFoodFailureNeverSavesOrClears() {
        when(carts.lockItemsForCheckout(4)).thenReturn(new TreeMap<>());
        assertCode("EMPTY_CART", () -> service.create(CUSTOMER, dineIn()));
        when(carts.lockItemsForCheckout(4)).thenReturn(new TreeMap<>(java.util.Map.of(1L, 2)));
        for (String code : List.of("INSUFFICIENT_STOCK", "FOOD_UNAVAILABLE")) {
            doThrow(BusinessException.conflict(code, code)).when(foods).reserveStock(1, 2);
            assertCode(code, () -> service.create(CUSTOMER, dineIn()));
        }
        doThrow(BusinessException.notFound("FOOD_NOT_FOUND", "missing")).when(foods).reserveStock(1, 2);
        assertCode("FOOD_NOT_FOUND", () -> service.create(CUSTOMER, dineIn()));
        verifyNoInteractions(orders);
        verify(carts, never()).clearCart(anyLong());
    }

    @Test void customerListIsAlwaysScopedAndEmployeeDefaultsToActiveOrders() {
        when(orders.findPage(any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(order(OrderType.DINE_IN, OrderStatus.PENDING)));
        when(orders.countMatching(any(), any(), any())).thenReturn(7L);
        var page = service.list(CUSTOMER, Role.CUSTOMER, null, null, 0, 20);
        assertEquals(7L, page.totalElements());
        assertEquals(1, page.content().size());
        assertEquals(new BigDecimal("130000.00"), page.content().getFirst().totalAmount());
        verify(orders).findPage(4L, List.of(OrderStatus.values()), null, 0, 20);
        verify(orders).countMatching(4L, List.of(OrderStatus.values()), null);

        var active = List.of(OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.PROCESSING, OrderStatus.SHIPPING);
        service.list(EMPLOYEE, Role.EMPLOYEE, null, null, 0, 20);
        verify(orders).findPage(null, active, null, 0, 20);
        service.list(EMPLOYEE, Role.EMPLOYEE, OrderStatus.PENDING, OrderType.DINE_IN, 1, 5);
        verify(orders).findPage(null, List.of(OrderStatus.PENDING), OrderType.DINE_IN, 1, 5);
        service.list(CUSTOMER, Role.CUSTOMER, OrderStatus.COMPLETED, OrderType.DELIVERY, 2, 100);
        verify(orders).findPage(4L, List.of(OrderStatus.COMPLETED), OrderType.DELIVERY, 2, 100);

        assertCode("VALIDATION_ERROR", () -> service.list(CUSTOMER, Role.CUSTOMER, null, null, -1, 20));
        assertCode("VALIDATION_ERROR", () -> service.list(CUSTOMER, Role.CUSTOMER, null, null, 0, 0));
        assertCode("VALIDATION_ERROR", () -> service.list(CUSTOMER, Role.CUSTOMER, null, null, 0, 101));
        assertCode("VALIDATION_ERROR", () -> service.list(CUSTOMER, Role.CUSTOMER, null, null, Integer.MAX_VALUE, 20));
        service.list(CUSTOMER, Role.CUSTOMER, null, null, Integer.MAX_VALUE / 100, 100);
    }

    @Test void ownershipIsCheckedBeforeAnyMutation() {
        when(orders.findById(1001L)).thenReturn(Optional.of(order(OrderType.DELIVERY, OrderStatus.PENDING)));
        when(orders.lockById(1001L)).thenReturn(Optional.of(order(OrderType.DELIVERY, OrderStatus.PENDING)));
        assertCode("FORBIDDEN", () -> service.get(5, Role.CUSTOMER, 1001));
        assertCode("FORBIDDEN", () -> service.cancel(5, 1001));
        assertEquals(1001L, service.get(CUSTOMER, Role.CUSTOMER, 1001).id());
        assertEquals(1001L, service.get(EMPLOYEE, Role.EMPLOYEE, 1001).id());
        assertCode("ORDER_NOT_FOUND", () -> service.get(CUSTOMER, Role.CUSTOMER, 9999));
        assertCode("ORDER_NOT_FOUND", () -> service.cancel(CUSTOMER, 9999));
        assertCode("ORDER_NOT_FOUND", () -> service.updateStatus(EMPLOYEE, 9999, OrderStatus.CONFIRMED));
        // ID ≤ 0 không tồn tại: trả 404 như Food/Cart.
        for (long missing : new long[]{0, -1}) {
            assertCode("ORDER_NOT_FOUND", () -> service.get(CUSTOMER, Role.CUSTOMER, missing));
            assertCode("ORDER_NOT_FOUND", () -> service.cancel(CUSTOMER, missing));
            assertCode("ORDER_NOT_FOUND", () -> service.confirm(EMPLOYEE, missing));
        }
        assertCode("VALIDATION_ERROR", () -> service.updateStatus(EMPLOYEE, 1001, null));
        assertCode("VALIDATION_ERROR", () -> service.updateStatus(EMPLOYEE, 1001, OrderStatus.PENDING));
        verify(orders, never()).save(any());
        verifyNoInteractions(foods, carts);
    }

    @Test void everyStatusTransitionMatchesServingTypeAndConfirmationMetadata() {
        for (var type : OrderType.values()) {
            for (var current : OrderStatus.values()) {
                for (var next : OrderStatus.values()) {
                    if (next == OrderStatus.PENDING) continue; // không bao giờ là đích hợp lệ: xem test bên dưới
                    var existing = order(type, current);
                    Long confirmedBefore = existing.getConfirmedBy();
                    when(orders.lockById(1001L)).thenReturn(Optional.of(existing));
                    boolean allowed = (current == OrderStatus.PENDING && (next == OrderStatus.CONFIRMED || next == OrderStatus.CANCELLED))
                            || (current == OrderStatus.CONFIRMED && (next == OrderStatus.PROCESSING || next == OrderStatus.CANCELLED))
                            || (current == OrderStatus.PROCESSING && (next == OrderStatus.CANCELLED
                                || next == (type == OrderType.DINE_IN ? OrderStatus.COMPLETED : OrderStatus.SHIPPING)))
                            || (current == OrderStatus.SHIPPING && next == OrderStatus.COMPLETED);
                    if (allowed) {
                        var result = service.updateStatus(EMPLOYEE, 1001, next);
                        assertEquals(next, result.status());
                        assertEquals(NOW, result.updatedAt());
                        if (next == OrderStatus.CONFIRMED) {
                            assertEquals(2L, result.confirmedBy());
                            assertEquals(NOW, result.confirmedAt());
                        } else assertEquals(confirmedBefore, result.confirmedBy());
                        if (next == OrderStatus.CANCELLED) assertEquals(NOW, result.cancelledAt());
                        if (next == OrderStatus.COMPLETED) assertEquals(NOW, result.completedAt());
                    } else {
                        assertCode("INVALID_ORDER_TRANSITION", () -> service.updateStatus(EMPLOYEE, 1001, next));
                        assertEquals(current, existing.getStatus());
                    }
                }
            }
        }
        // Chỉ các lần hủy hợp lệ (PENDING/CONFIRMED/PROCESSING × 2 loại đơn) mới hoàn kho.
        verify(foods, times(6)).releaseStock(1, 2);
    }

    @Test void confirmIsPendingToConfirmedOnly() {
        when(orders.lockById(1001L)).thenReturn(Optional.of(order(OrderType.DINE_IN, OrderStatus.PENDING)));
        var confirmed = service.confirm(EMPLOYEE, 1001);
        assertEquals(OrderStatus.CONFIRMED, confirmed.status());
        assertEquals(2L, confirmed.confirmedBy());
        when(orders.lockById(1001L)).thenReturn(Optional.of(order(OrderType.DINE_IN, OrderStatus.CONFIRMED)));
        assertCode("INVALID_ORDER_TRANSITION", () -> service.confirm(EMPLOYEE, 1001));
    }

    @Test void customerCanOnlyCancelPendingAndStockIsRestoredOnce() {
        var pending = order(OrderType.DELIVERY, OrderStatus.PENDING);
        pending.getItems().add(OrderItem.builder().order(pending).foodId(3L).foodName("Trà")
                .unitPrice(new BigDecimal("25000.00")).quantity(1).build());
        pending.getItems().addFirst(OrderItem.builder().order(pending).foodId(7L).foodName("Chè")
                .unitPrice(new BigDecimal("30000.00")).quantity(4).build());
        when(orders.lockById(1001L)).thenReturn(Optional.of(pending));
        var cancelled = service.cancel(CUSTOMER, 1001);
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertNull(cancelled.confirmedBy());
        assertEquals(NOW, cancelled.cancelledAt());
        var sequence = inOrder(foods);
        sequence.verify(foods).releaseStock(1, 2);
        sequence.verify(foods).releaseStock(3, 1);
        sequence.verify(foods).releaseStock(7, 4);

        assertCode("INVALID_ORDER_TRANSITION", () -> service.cancel(CUSTOMER, 1001));
        for (var status : List.of(OrderStatus.CONFIRMED, OrderStatus.PROCESSING, OrderStatus.SHIPPING, OrderStatus.COMPLETED)) {
            when(orders.lockById(1001L)).thenReturn(Optional.of(order(OrderType.DELIVERY, status)));
            assertCode("INVALID_ORDER_TRANSITION", () -> service.cancel(CUSTOMER, 1001));
        }
        verify(foods, times(1)).releaseStock(1, 2);
    }
}
