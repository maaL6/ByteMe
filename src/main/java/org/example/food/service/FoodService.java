package org.example.food.service;

import lombok.RequiredArgsConstructor;
import org.example.food.dto.FoodDto;
import org.example.food.dto.FoodRequest;
import org.example.food.entity.Food;
import org.example.food.repository.CategoryRepository;
import org.example.food.repository.FoodRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FoodService {

    private final FoodRepository foodRepository;
    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<FoodDto> getActiveFoods(Long categoryId) {
        List<Food> foods;
        if (categoryId != null) {
            if (!categoryRepository.existsById(categoryId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found");
            }
            foods = foodRepository.findByStatusAndCategoryIdOrderByIdAsc("ACTIVE", categoryId);
        } else {
            foods = foodRepository.findByStatusOrderByIdAsc("ACTIVE");
        }
        return foods.stream().map(FoodDto::fromEntity).toList();
    }

    @Transactional(readOnly = true)
    public FoodDto getFoodById(Long id) {
        Food food = foodRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Food not found"));
        
        if (!"ACTIVE".equals(food.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Food is not active");
        }
        
        return FoodDto.fromEntity(food);
    }

    @Transactional(readOnly = true)
    public List<FoodDto> searchFoods(String keyword, Long categoryId) {
        if (keyword == null || keyword.trim().isEmpty() || keyword.trim().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid keyword");
        }
        
        String kw = keyword.trim();
        List<Food> foods;
        
        if (categoryId != null) {
            if (!categoryRepository.existsById(categoryId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found");
            }
            foods = foodRepository.searchActiveFoodsWithCategory(kw, categoryId);
        } else {
            foods = foodRepository.searchActiveFoods(kw);
        }
        
        return foods.stream().map(FoodDto::fromEntity).toList();
    }

    @Transactional
    public FoodDto createFood(FoodRequest request) {
        if (!categoryRepository.existsById(request.getCategoryId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found");
        }

        if (foodRepository.existsByCategoryIdAndNameIgnoreCase(request.getCategoryId(), request.getName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Food with this name already exists in the category");
        }

        Food food = Food.builder()
                .categoryId(request.getCategoryId())
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .imageUrl(request.getImageUrl())
                .stockQuantity(request.getStockQuantity() != null ? request.getStockQuantity() : 0)
                .status(request.getStatus() != null ? request.getStatus() : "ACTIVE")
                .build();

        Food savedFood = foodRepository.save(food);
        return FoodDto.fromEntity(savedFood);
    }

    @Transactional
    public FoodDto updateFood(Long id, FoodRequest request) {
        Food food = foodRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Food not found"));

        if (!food.getCategoryId().equals(request.getCategoryId()) && !categoryRepository.existsById(request.getCategoryId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found");
        }

        if (foodRepository.existsByCategoryIdAndNameIgnoreCaseAndIdNot(request.getCategoryId(), request.getName(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Food with this name already exists in the category");
        }

        food.setCategoryId(request.getCategoryId());
        food.setName(request.getName());
        food.setDescription(request.getDescription());
        food.setPrice(request.getPrice());
        food.setImageUrl(request.getImageUrl());
        food.setStockQuantity(request.getStockQuantity() != null ? request.getStockQuantity() : food.getStockQuantity());
        food.setStatus(request.getStatus() != null ? request.getStatus() : food.getStatus());

        Food updatedFood = foodRepository.save(food);
        return FoodDto.fromEntity(updatedFood);
    }

    @Transactional
    public void deleteFood(Long id) {
        Food food = foodRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Food not found"));
                
        // In reality, we should check if this food is in any active order
        // For now, we'll implement logic as soft delete to INACTIVE
        food.setStatus("INACTIVE");
        foodRepository.save(food);
    }
}
