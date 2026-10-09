package org.example.order.service.port;

import java.math.BigDecimal;

/** Minimal food integration for checkout/cancellation, not a Food CRUD service. */
public interface OrderFoodRepository {
    record Food(long id, String name, BigDecimal price, int stockQuantity, boolean active) {}

    Food lockById(long foodId);
    void decreaseStock(long foodId, int quantity);
    void restoreStock(long foodId, int quantity);
}
