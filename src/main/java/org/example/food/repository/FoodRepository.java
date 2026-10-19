package org.example.food.repository;

import jakarta.persistence.LockModeType;
import org.example.food.entity.Food;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FoodRepository extends JpaRepository<Food, Long> {
    // SELECT ... FOR UPDATE: checkout và hủy đơn khóa món theo foodId tăng dần để không bán vượt kho.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Food f where f.id = :id")
    Optional<Food> lockById(Long id);

    List<Food> findByStatusOrderByIdAsc(String status);
    
    List<Food> findByStatusAndCategoryIdOrderByIdAsc(String status, Long categoryId);
    
    @Query("SELECT f FROM Food f WHERE f.status = 'ACTIVE' AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!' OR LOWER(f.description) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!') ORDER BY f.id ASC")
    List<Food> searchActiveFoods(@Param("keyword") String keyword);

    @Query("SELECT f FROM Food f WHERE f.status = 'ACTIVE' AND f.categoryId = :categoryId AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!' OR LOWER(f.description) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!') ORDER BY f.id ASC")
    List<Food> searchActiveFoodsWithCategory(@Param("keyword") String keyword, @Param("categoryId") Long categoryId);

    // normalized_name = LOWER(TRIM(name)), unique trên toàn menu (uq_foods_normalized_name).
    boolean existsByNormalizedName(String normalizedName);
    boolean existsByNormalizedNameAndIdNot(String normalizedName, Long id);
}
