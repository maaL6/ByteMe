package org.example.food.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record FoodRequest(
        @NotNull(message = "categoryId là bắt buộc")
        @Positive(message = "categoryId phải là số dương")
        Long categoryId,

        @NotBlank(message = "Tên món là bắt buộc")
        @Size(max = 150, message = "Tên món tối đa 150 ký tự")
        String name,

        @Size(max = 16000, message = "Mô tả tối đa 16000 ký tự")
        String description,

        @NotNull(message = "Giá là bắt buộc")
        @Positive(message = "Giá phải lớn hơn 0")
        @Digits(integer = 13, fraction = 2, message = "Giá tối đa 13 chữ số phần nguyên và 2 chữ số thập phân")
        BigDecimal price,

        @Size(max = 2048, message = "imageUrl tối đa 2048 ký tự")
        String imageUrl,

        @Min(value = 0, message = "Tồn kho không được âm")
        Integer stockQuantity,

        @Pattern(regexp = "^(ACTIVE|INACTIVE)$", message = "Trạng thái phải là ACTIVE hoặc INACTIVE")
        String status) {
}
