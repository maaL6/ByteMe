package org.example.food.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class FoodRequest {
    @NotNull(message = "categoryId là bắt buộc")
    @Positive(message = "categoryId phải là số dương")
    private Long categoryId;

    // Giới hạn khớp với cột trong schema.sql để dữ liệu quá dài trả 400 thay vì lỗi từ DB.
    @NotBlank(message = "Tên món là bắt buộc")
    @Size(max = 150, message = "Tên món tối đa 150 ký tự")
    private String name;

    // Cột TEXT tối đa 65.535 byte; 16.000 ký tự utf8mb4 luôn vừa.
    @Size(max = 16000, message = "Mô tả tối đa 16000 ký tự")
    private String description;

    @NotNull(message = "Giá là bắt buộc")
    @Min(value = 1, message = "Giá phải lớn hơn 0")
    @Digits(integer = 13, fraction = 2, message = "Giá tối đa 13 chữ số phần nguyên và 2 chữ số thập phân")
    private BigDecimal price;

    @Size(max = 2048, message = "imageUrl tối đa 2048 ký tự")
    private String imageUrl;

    // stockQuantity/status không có giá trị mặc định: null nghĩa là "giữ nguyên" khi update,
    // service tự gán mặc định khi create.
    @Min(value = 0, message = "Tồn kho không được âm")
    private Integer stockQuantity;

    @Pattern(regexp = "^(ACTIVE|INACTIVE)$", message = "Trạng thái phải là ACTIVE hoặc INACTIVE")
    private String status;
}
