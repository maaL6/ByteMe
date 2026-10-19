package org.example.food.service;

import lombok.RequiredArgsConstructor;
import org.example.common.BusinessException;
import org.example.food.dto.FoodDto;
import org.example.food.dto.FoodRequest;
import org.example.food.entity.Food;
import org.example.food.repository.CategoryRepository;
import org.example.food.repository.FoodRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FoodService {

    private static final String ACTIVE = "ACTIVE";

    private final FoodRepository foodRepository;
    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<FoodDto> getActiveFoods(Long categoryId) {
        List<Food> foods;
        if (categoryId != null) {
            requireCategory(categoryId);
            foods = foodRepository.findByStatusAndCategoryIdOrderByIdAsc(ACTIVE, categoryId);
        } else {
            foods = foodRepository.findByStatusOrderByIdAsc(ACTIVE);
        }
        return foods.stream().map(FoodDto::fromEntity).toList();
    }

    @Transactional(readOnly = true)
    public FoodDto getFoodById(Long id) {
        Food food = findFood(id);
        if (!ACTIVE.equals(food.getStatus())) {
            throw foodNotFound();
        }
        return FoodDto.fromEntity(food);
    }

    @Transactional(readOnly = true)
    public List<FoodDto> searchFoods(String keyword, Long categoryId) {
        if (keyword == null || keyword.trim().isEmpty() || keyword.trim().length() > 100) {
            throw BusinessException.invalid("VALIDATION_ERROR", "Từ khóa phải có từ 1 đến 100 ký tự.");
        }

        String kw = escapeLike(keyword.trim());
        List<Food> foods;

        if (categoryId != null) {
            requireCategory(categoryId);
            foods = foodRepository.searchActiveFoodsWithCategory(kw, categoryId);
        } else {
            foods = foodRepository.searchActiveFoods(kw);
        }

        return foods.stream().map(FoodDto::fromEntity).toList();
    }

    @Transactional
    public FoodDto createFood(FoodRequest request) {
        requireCategory(request.getCategoryId());

        String name = request.getName().trim();
        if (foodRepository.existsByNormalizedName(normalize(name))) {
            throw duplicateName();
        }

        Food food = Food.builder()
                .categoryId(request.getCategoryId())
                .name(name)
                .description(request.getDescription())
                .price(request.getPrice())
                .imageUrl(request.getImageUrl())
                .stockQuantity(request.getStockQuantity() != null ? request.getStockQuantity() : 0)
                .status(request.getStatus() != null ? request.getStatus() : ACTIVE)
                .build();

        Food savedFood = foodRepository.save(food);
        return FoodDto.fromEntity(savedFood);
    }

    @Transactional
    public FoodDto updateFood(Long id, FoodRequest request) {
        Food food = findFood(id);

        if (!food.getCategoryId().equals(request.getCategoryId())) {
            requireCategory(request.getCategoryId());
        }

        String name = request.getName().trim();
        if (foodRepository.existsByNormalizedNameAndIdNot(normalize(name), id)) {
            throw duplicateName();
        }

        food.setCategoryId(request.getCategoryId());
        food.setName(name);
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
        Food food = findFood(id);

        // Ngừng bán (INACTIVE) thay vì xóa: đơn cũ giữ snapshot, hủy đơn vẫn hoàn kho được.
        food.setStatus("INACTIVE");
        foodRepository.save(food);
    }

    // ----- Dùng bởi module Cart -----

    /** Món phải tồn tại (404) và đang bán (409) mới được thêm vào giỏ. */
    @Transactional(readOnly = true)
    public FoodDto getAvailableFood(long id) {
        Food food = findFood(id);
        requireActive(food);
        return FoodDto.fromEntity(food);
    }

    /** Thông tin món để hiển thị trong giỏ, kể cả món đã ngừng bán. */
    @Transactional(readOnly = true)
    public Map<Long, FoodDto> findByIds(Collection<Long> ids) {
        return foodRepository.findAllById(ids).stream()
                .map(FoodDto::fromEntity)
                .collect(Collectors.toMap(FoodDto::getId, Function.identity()));
    }

    // ----- Dùng bởi module Order, luôn trong giao dịch của Order -----

    /**
     * Khóa món tới hết giao dịch, kiểm tra còn bán và đủ kho rồi trừ kho.
     * Trả về tên/giá hiện tại để Order lưu snapshot.
     */
    @Transactional
    public FoodDto reserveStock(long id, int quantity) {
        Food food = lockFood(id);
        requireActive(food);
        if (food.getStockQuantity() < quantity) {
            throw BusinessException.conflict("INSUFFICIENT_STOCK", "Món ăn không đủ tồn kho.");
        }
        food.setStockQuantity(food.getStockQuantity() - quantity);
        return FoodDto.fromEntity(food);
    }

    /** Khóa món và hoàn lại kho khi hủy đơn, kể cả món đã ngừng bán. */
    @Transactional
    public void releaseStock(long id, int quantity) {
        Food food = lockFood(id);
        food.setStockQuantity(food.getStockQuantity() + quantity);
    }

    private Food findFood(long id) {
        return foodRepository.findById(id).orElseThrow(FoodService::foodNotFound);
    }

    private Food lockFood(long id) {
        return foodRepository.lockById(id).orElseThrow(FoodService::foodNotFound);
    }

    private void requireCategory(Long categoryId) {
        if (!categoryRepository.existsById(categoryId)) {
            throw BusinessException.notFound("CATEGORY_NOT_FOUND", "Không tìm thấy danh mục.");
        }
    }

    private static void requireActive(Food food) {
        if (!ACTIVE.equals(food.getStatus())) {
            throw BusinessException.conflict("FOOD_UNAVAILABLE", "Món ăn đã ngừng bán.");
        }
    }

    private static BusinessException foodNotFound() {
        return BusinessException.notFound("FOOD_NOT_FOUND", "Không tìm thấy món ăn.");
    }

    private static BusinessException duplicateName() {
        return BusinessException.conflict("FOOD_NAME_CONFLICT", "Tên món ăn đã tồn tại trong thực đơn.");
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** Escape ký tự đại diện của LIKE để '%' và '_' trong từ khóa được tìm đúng nghĩa đen. */
    private static String escapeLike(String keyword) {
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
