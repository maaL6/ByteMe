package org.example.food.repository;

import jakarta.persistence.LockModeType;
import org.example.food.entity.Food;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface FoodRepository extends JpaRepository<Food, Long> {
    // SELECT ... FOR UPDATE: checkout và hủy đơn khóa món theo foodId tăng dần để không bán vượt kho.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Food f where f.id = :id")
    Optional<Food> lockById(Long id);

    // categoryId = null nghĩa là không lọc theo danh mục.
    @Query("""
            select f from Food f
            where f.status = 'ACTIVE' and (:categoryId is null or f.categoryId = :categoryId)
            order by f.id""")
    List<Food> findActive(Long categoryId);

    // keyword đã được escape '%', '_' và '!' (ký tự escape) ở FoodService.
    @Query("""
            select f from Food f
            where f.status = 'ACTIVE' and (:categoryId is null or f.categoryId = :categoryId)
              and (lower(f.name) like lower(concat('%', :keyword, '%')) escape '!'
                   or lower(f.description) like lower(concat('%', :keyword, '%')) escape '!')
            order by f.id""")
    List<Food> searchActive(String keyword, Long categoryId);

    // normalized_name = LOWER(TRIM(name)), unique trên toàn menu (uq_foods_normalized_name).
    boolean existsByNormalizedName(String normalizedName);
    boolean existsByNormalizedNameAndIdNot(String normalizedName, Long id);
}
