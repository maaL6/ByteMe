package org.example.food.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** created_at/updated_at do MySQL tự đặt (DEFAULT / ON UPDATE CURRENT_TIMESTAMP), không map ở đây. */
@Entity
@Table(name = "foods")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Food {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 150)
    private String name;

    // Cột sinh tự động LOWER(TRIM(name)) trong MySQL; dùng để kiểm tra trùng tên toàn menu.
    @Column(name = "normalized_name", insertable = false, updatable = false)
    private String normalizedName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal price;

    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    @Column(name = "stock_quantity", nullable = false)
    private Integer stockQuantity;

    @Column(nullable = false, length = 20)
    private String status;

    // Khóa lạc quan: admin sửa món đúng lúc checkout trừ kho thì bên ghi sau nhận 409.
    // Để null khi tạo mới: Spring Data dùng version == null để chọn persist; Hibernate tự gán 0.
    @Version
    @Column(nullable = false)
    private Long version;
}
