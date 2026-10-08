package org.example.order.domain;

import java.math.BigDecimal;

public record OrderItem(long foodId, String foodName, BigDecimal unitPrice, int quantity) {
    public BigDecimal lineTotal() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }
    public BigDecimal getLineTotal() { return lineTotal(); }
}
