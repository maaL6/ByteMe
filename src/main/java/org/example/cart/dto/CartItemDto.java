package org.example.cart.dto;

import lombok.Builder;
import lombok.Data;
import org.example.food.dto.FoodDto;

@Data
@Builder
public class CartItemDto {
    private Long id;
    private FoodDto food;
    private Integer quantity;
}
