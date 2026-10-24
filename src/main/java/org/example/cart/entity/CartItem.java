package org.example.cart.entity;

import jakarta.persistence.*;
import lombok.*;


@Entity
@Table(name = "cart_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "food_id", nullable = false)
    private Long foodId;

    @Column(nullable = false)
    private Integer quantity;
}
