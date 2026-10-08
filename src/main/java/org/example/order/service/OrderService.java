package org.example.order.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.example.order.domain.*;
import org.example.order.service.port.*;
import org.example.shared.api.AuthenticatedUser;
import org.example.shared.api.Role;

public final class OrderService {
    private final OrderRepository orders;
    private final CartCheckout carts;
    private final OrderFoodRepository foods;
    private final UnitOfWork transactions;
    private final OrderEvents events;
    private final Clock clock;

    public OrderService(OrderRepository orders, CartCheckout carts, OrderFoodRepository foods,
            UnitOfWork transactions, OrderEvents events, Clock clock) {
        this.orders = orders;
        this.carts = carts;
        this.foods = foods;
        this.transactions = transactions;
        this.events = events;
        this.clock = clock;
    }

    public Order create(AuthenticatedUser user, CreateOrderCommand command) {
        requireRole(user, Role.CUSTOMER);
        long userId = userId(user);
        CreateOrderCommand input = validate(command);
        return transactions.execute(() -> {
            var cart = carts.lockByUserId(userId);
            if (cart == null) throw failure("CART_NOT_FOUND", "Không tìm thấy giỏ hàng.");
            if (cart.items().isEmpty()) throw failure("EMPTY_CART", "Giỏ hàng đang rỗng.");
            var lines = cart.items().stream().sorted(Comparator.comparingLong(CartCheckout.CartItem::foodId)).toList();
            var items = new ArrayList<OrderItem>();
            var ids = new HashSet<Long>();
            for (var line : lines) {
                if (line.foodId() <= 0 || line.quantity() <= 0 || !ids.add(line.foodId()))
                    throw failure("VALIDATION_ERROR", "Dữ liệu món trong giỏ không hợp lệ.");
                var food = foods.lockById(line.foodId());
                if (!food.active()) throw failure("FOOD_UNAVAILABLE", "Món ăn đã ngừng bán.");
                if (food.stockQuantity() < line.quantity())
                    throw failure("INSUFFICIENT_STOCK", "Món ăn không đủ tồn kho.");
                items.add(new OrderItem(food.id(), food.name(), food.price(), line.quantity()));
            }
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            var order = orders.save(new Order(null, userId, input.orderType(), OrderStatus.PENDING,
                    input.tableNumber(), input.recipientName(), input.shippingPhone(), input.shippingAddress(),
                    input.paymentMethod(), input.note(), null, null, null, null, now, now, items));
            for (var item : items) foods.decreaseStock(item.foodId(), item.quantity());
            carts.clearItems(cart.cartId());
            events.publish(new OrderEvents.OrderCreated(order.id(), userId, cart.cartId()));
            return order;
        });
    }

    private static CreateOrderCommand validate(CreateOrderCommand input) {
        if (input == null || input.orderType() == null || input.paymentMethod() == null)
            throw failure("VALIDATION_ERROR", "orderType và paymentMethod là bắt buộc.");
        String note = text(input.note(), "note", 500, false);
        if (input.orderType() == OrderType.DINE_IN) {
            if (input.recipientName() != null || input.shippingPhone() != null || input.shippingAddress() != null
                    || input.paymentMethod() == PaymentMethod.COD)
                throw failure("VALIDATION_ERROR", "DINE_IN không nhận thông tin giao hàng hoặc thanh toán COD.");
            return new CreateOrderCommand(input.orderType(), text(input.tableNumber(), "tableNumber", 20, true),
                    null, null, null, input.paymentMethod(), note);
        }
        if (input.tableNumber() != null || input.paymentMethod() == PaymentMethod.CASH)
            throw failure("VALIDATION_ERROR", "DELIVERY không nhận tableNumber hoặc thanh toán CASH.");
        return new CreateOrderCommand(input.orderType(), null, text(input.recipientName(), "recipientName", 150, true),
                text(input.shippingPhone(), "shippingPhone", 20, true),
                text(input.shippingAddress(), "shippingAddress", 500, true), input.paymentMethod(), note);
    }

    private static String text(String value, String field, int max, boolean required) {
        String result = value == null ? null : value.trim();
        if ((required && (result == null || result.isEmpty())) || (result != null && result.length() > max))
            throw failure("VALIDATION_ERROR", field + " không hợp lệ (tối đa " + max + " ký tự).");
        return result == null || result.isEmpty() ? null : result;
    }

    private static void requireRole(AuthenticatedUser user, Role role) {
        if (user.role() != role) throw failure("FORBIDDEN", "Không có quyền thực hiện thao tác này.");
    }

    private static long userId(AuthenticatedUser user) {
        try {
            long id = Long.parseLong(user.userId());
            if (id > 0) return id;
        } catch (NumberFormatException ignored) { }
        throw failure("FORBIDDEN", "JWT phải chứa ID tài khoản hợp lệ trong database.");
    }

    private static OrderFailure failure(String code, String message) { return new OrderFailure(code, message); }
}
