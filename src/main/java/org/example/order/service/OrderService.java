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

    public OrderRepository.OrderPage list(AuthenticatedUser user, OrderStatus status, OrderType type,
            int page, int size) {
        requireReader(user);
        if (page < 0 || size < 1 || size > 100) throw failure("VALIDATION_ERROR", "page >= 0; size từ 1 đến 100.");
        List<OrderStatus> statuses = List.of();
        if (status != null) statuses = List.of(status);
        else if (user.role() == Role.EMPLOYEE)
            statuses = List.of(OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.PROCESSING, OrderStatus.SHIPPING);
        Long ownerId = null;
        if (user.role() == Role.CUSTOMER) ownerId = userId(user);
        return orders.findAll(ownerId, statuses, type, page, size);
    }

    public Order get(AuthenticatedUser user, long id) {
        requireReader(user);
        requireId(id);
        var order = orders.findById(id).orElseThrow(() -> failure("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng."));
        requireOwner(user, order);
        return order;
    }

    public Order cancel(AuthenticatedUser user, long id) {
        requireRole(user, Role.CUSTOMER);
        return changeStatus(user, id, OrderStatus.CANCELLED);
    }

    public Order confirm(AuthenticatedUser user, long id) {
        return updateStatus(user, id, OrderStatus.CONFIRMED);
    }

    public Order updateStatus(AuthenticatedUser user, long id, OrderStatus next) {
        requireRole(user, Role.EMPLOYEE);
        return changeStatus(user, id, next);
    }

    private Order changeStatus(AuthenticatedUser user, long id, OrderStatus next) {
        requireId(id);
        if (next == null) throw failure("VALIDATION_ERROR", "status là bắt buộc.");
        long actorId = userId(user);
        return transactions.execute(() -> {
            var order = orders.lockById(id).orElseThrow(() -> failure("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng."));
            requireOwner(user, order);
            boolean allowed = switch (order.status()) {
                case PENDING -> next == OrderStatus.CONFIRMED || next == OrderStatus.CANCELLED;
                case CONFIRMED -> next == OrderStatus.PROCESSING || next == OrderStatus.CANCELLED;
                case PROCESSING -> next == OrderStatus.CANCELLED
                        || (order.orderType() == OrderType.DINE_IN && next == OrderStatus.COMPLETED)
                        || (order.orderType() == OrderType.DELIVERY && next == OrderStatus.SHIPPING);
                case SHIPPING -> next == OrderStatus.COMPLETED;
                case COMPLETED, CANCELLED -> false;
            };
            if (user.role() == Role.CUSTOMER)
                allowed = order.status() == OrderStatus.PENDING && next == OrderStatus.CANCELLED;
            if (!allowed) throw failure("INVALID_ORDER_TRANSITION", "Không thể chuyển trạng thái đơn hàng.");
            if (next == OrderStatus.CANCELLED) {
                var items = order.items().stream().sorted(Comparator.comparingLong(OrderItem::foodId)).toList();
                for (var item : items) foods.lockById(item.foodId());
                for (var item : items) foods.restoreStock(item.foodId(), item.quantity());
            }
            return orders.save(order.transitionTo(next, actorId, clock.instant().truncatedTo(ChronoUnit.MICROS)));
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

    private static void requireReader(AuthenticatedUser user) {
        if (user.role() != Role.CUSTOMER && user.role() != Role.EMPLOYEE)
            throw failure("FORBIDDEN", "Không có quyền xử lý đơn hàng.");
    }

    private static void requireRole(AuthenticatedUser user, Role role) {
        if (user.role() != role) throw failure("FORBIDDEN", "Không có quyền thực hiện thao tác này.");
    }

    private static void requireOwner(AuthenticatedUser user, Order order) {
        if (user.role() == Role.CUSTOMER && order.userId() != userId(user))
            throw failure("FORBIDDEN", "Đơn hàng không thuộc khách hàng hiện tại.");
    }

    private static long userId(AuthenticatedUser user) {
        try {
            long id = Long.parseLong(user.userId());
            if (id > 0) return id;
        } catch (NumberFormatException ignored) { }
        throw failure("FORBIDDEN", "JWT phải chứa ID tài khoản hợp lệ trong database.");
    }

    private static void requireId(long id) {
        if (id <= 0) throw failure("VALIDATION_ERROR", "orderId phải là số nguyên dương.");
    }

    private static OrderFailure failure(String code, String message) { return new OrderFailure(code, message); }
}
