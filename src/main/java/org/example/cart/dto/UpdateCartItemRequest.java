package org.example.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateCartItemRequest {
    @NotNull(message = "Số lượng là bắt buộc")
    @Min(value = 1, message = "Số lượng tối thiểu là 1")
    private Integer quantity;
}
