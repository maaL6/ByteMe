package org.example.cart.repository;

import org.example.cart.entity.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.userId = :userId")
    Optional<Cart> lockByUserId(Long userId);

    // Atomic creation also serializes concurrent first additions for the same user.
    @Modifying
    @Query(value = "INSERT INTO carts (user_id) VALUES (:userId) ON DUPLICATE KEY UPDATE id = id", nativeQuery = true)
    void ensureExists(Long userId);
}
