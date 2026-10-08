package org.example.food.repository;

import org.example.food.entity.Food;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FoodRepository extends JpaRepository<Food, Long> {
    List<Food> findByStatusOrderByIdAsc(String status);
    
    List<Food> findByStatusAndCategoryIdOrderByIdAsc(String status, Long categoryId);
    
    @Query("SELECT f FROM Food f WHERE f.status = 'ACTIVE' AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(f.description) LIKE LOWER(CONCAT('%', :keyword, '%'))) ORDER BY f.id ASC")
    List<Food> searchActiveFoods(@Param("keyword") String keyword);

    @Query("SELECT f FROM Food f WHERE f.status = 'ACTIVE' AND f.categoryId = :categoryId AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(f.description) LIKE LOWER(CONCAT('%', :keyword, '%'))) ORDER BY f.id ASC")
    List<Food> searchActiveFoodsWithCategory(@Param("keyword") String keyword, @Param("categoryId") Long categoryId);

    boolean existsByCategoryIdAndNameIgnoreCase(Long categoryId, String name);
    boolean existsByCategoryIdAndNameIgnoreCaseAndIdNot(Long categoryId, String name, Long id);
}
