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

    // Tạo giỏ nếu chưa có; câu lệnh nguyên tử nên hai lần thêm món đầu tiên đồng thời chỉ tạo một giỏ.
    @Modifying
    @Query(value = "INSERT INTO carts (user_id) VALUES (:userId) ON DUPLICATE KEY UPDATE id = id", nativeQuery = true)
    void ensureExists(Long userId);

    // Đánh dấu giỏ vừa thay đổi: version đếm số lần ghi, updated_at lấy theo đồng hồ MySQL như created_at.
    @Modifying
    @Query(value = "UPDATE carts SET version = version + 1, updated_at = CURRENT_TIMESTAMP(6) WHERE id = :cartId",
            nativeQuery = true)
    void touch(Long cartId);

    /** Ghi các item đã thêm/sửa/xóa (flush để item mới có id) rồi đánh dấu giỏ vừa thay đổi. */
    default void saveChanges(Cart cart) {
        saveAndFlush(cart);
        touch(cart.getId());
    }
}
