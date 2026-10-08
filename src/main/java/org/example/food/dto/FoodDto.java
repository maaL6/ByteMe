package org.example.food.dto;

import lombok.Builder;
import lombok.Data;
import org.example.food.entity.Food;

import java.math.BigDecimal;

@Data
@Builder
public class FoodDto {
    private Long id;
    private Long categoryId;
    private String name;
    private String description;
    private BigDecimal price;
    private String imageUrl;
    private Integer stockQuantity;
    private String status;

    public static FoodDto fromEntity(Food food) {
        return FoodDto.builder()
                .id(food.getId())
                .categoryId(food.getCategoryId())
                .name(food.getName())
                .description(food.getDescription())
                .price(food.getPrice())
                .imageUrl(food.getImageUrl())
                .stockQuantity(food.getStockQuantity())
                .status(food.getStatus())
                .build();
    }
}
