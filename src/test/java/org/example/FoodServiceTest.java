package org.example;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.example.common.BusinessException;
import org.example.food.dto.FoodRequest;
import org.example.food.entity.Food;
import org.example.food.repository.CategoryRepository;
import org.example.food.repository.FoodRepository;
import org.example.food.service.FoodService;
import org.junit.jupiter.api.Test;

class FoodServiceTest {
    private final FoodRepository foods = mock(FoodRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final FoodService service = new FoodService(foods, categories);

    private static FoodRequest request(String name) {
        return request(1L, name);
    }

    private static FoodRequest request(long categoryId, String name) {
        return new FoodRequest(categoryId, name, null, new BigDecimal("50000"), null, null, null);
    }

    private static Food food(long id, int stock, String status) {
        return Food.builder().id(id).categoryId(1L).name("Phở").price(new BigDecimal("40000.00"))
                .stockQuantity(stock).status(status).build();
    }

    private static void assertError(BusinessException.Type type, String code, org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(BusinessException.class, action);
        assertEquals(type, error.type());
        assertEquals(code, error.code());
    }

    @Test void updateWithoutStockOrStatusKeepsCurrentValues() {
        var food = food(7, 25, "INACTIVE");
        when(foods.findById(7L)).thenReturn(Optional.of(food));
        when(categories.existsById(1L)).thenReturn(true);

        var result = service.updateFood(7L, request("Phở bò"));

        assertEquals(25, result.stockQuantity());
        assertEquals("INACTIVE", result.status());
        assertEquals(new BigDecimal("50000"), result.price());
        assertEquals("Phở bò", food.getName());
    }

    @Test void updateChecksFoodCategoryAndNameOfOtherFoods() {
        assertError(BusinessException.Type.NOT_FOUND, "FOOD_NOT_FOUND", () -> service.updateFood(7L, request("Phở")));
        when(foods.findById(7L)).thenReturn(Optional.of(food(7, 25, "ACTIVE")));
        assertError(BusinessException.Type.NOT_FOUND, "CATEGORY_NOT_FOUND", () -> service.updateFood(7L, request(2L, "Phở")));
        when(categories.existsById(1L)).thenReturn(true);
        // Service chỉ trim; so khớp không phân biệt hoa thường do collation _ci của MySQL đảm nhận.
        when(foods.existsByNameAndIdNot("Phở Gà", 7L)).thenReturn(true);
        assertError(BusinessException.Type.CONFLICT, "FOOD_NAME_CONFLICT", () -> service.updateFood(7L, request(" Phở Gà ")));
        assertEquals("Phở", service.updateFood(7L, request("Phở")).name());
    }

    @Test void deleteOnlyStopsSelling() {
        var food = food(7, 25, "ACTIVE");
        when(foods.findById(7L)).thenReturn(Optional.of(food));
        service.deleteFood(7L);
        assertEquals("INACTIVE", food.getStatus());
        verify(foods, never()).delete(any());
    }

    @Test void createUsesDefaultsAndChecksNameAcrossWholeMenu() {
        when(categories.existsById(1L)).thenReturn(true);
        when(foods.save(any())).thenAnswer(call -> call.getArgument(0));

        var created = service.createFood(request("  Cơm Gà "));
        assertEquals("Cơm Gà", created.name());
        assertEquals(0, created.stockQuantity());
        assertEquals("ACTIVE", created.status());
        verify(foods).existsByName("Cơm Gà");

        when(foods.existsByName("CƠM GÀ")).thenReturn(true);
        assertError(BusinessException.Type.CONFLICT, "FOOD_NAME_CONFLICT", () -> service.createFood(request("CƠM GÀ")));
    }

    @Test void missingCategoryOrFoodIsNotFound() {
        assertError(BusinessException.Type.NOT_FOUND, "CATEGORY_NOT_FOUND", () -> service.createFood(request("Phở")));
        assertError(BusinessException.Type.NOT_FOUND, "CATEGORY_NOT_FOUND", () -> service.getActiveFoods(9L));
        assertError(BusinessException.Type.NOT_FOUND, "FOOD_NOT_FOUND", () -> service.getFoodById(9L));
        assertError(BusinessException.Type.NOT_FOUND, "FOOD_NOT_FOUND", () -> service.deleteFood(9L));
        when(foods.findById(8L)).thenReturn(Optional.of(food(8, 3, "INACTIVE")));
        assertError(BusinessException.Type.NOT_FOUND, "FOOD_NOT_FOUND", () -> service.getFoodById(8L));
    }

    @Test void searchEscapesLikeWildcardsAndRejectsBadKeywords() {
        service.searchFoods(" 50%_off! ", null);
        verify(foods).searchActive("50!%!_off!!", null);
        for (String keyword : new String[]{null, " ", "x".repeat(101)}) {
            assertError(BusinessException.Type.INVALID, "VALIDATION_ERROR", () -> service.searchFoods(keyword, null));
        }
    }

    @Test void cartCanOnlyAddExistingActiveFood() {
        when(foods.findById(1L)).thenReturn(Optional.of(food(1, 0, "ACTIVE")));
        when(foods.findById(2L)).thenReturn(Optional.of(food(2, 5, "INACTIVE")));
        assertEquals(1L, service.getAvailableFood(1).id());
        assertError(BusinessException.Type.CONFLICT, "FOOD_UNAVAILABLE", () -> service.getAvailableFood(2));
        assertError(BusinessException.Type.NOT_FOUND, "FOOD_NOT_FOUND", () -> service.getAvailableFood(3));
    }

    @Test void findByIdsReturnsDisplayInfoKeyedById() {
        when(foods.findAllById(List.of(1L, 2L))).thenReturn(List.of(food(1, 5, "ACTIVE"), food(2, 0, "INACTIVE")));
        var result = service.findByIds(List.of(1L, 2L));
        assertEquals(2, result.size());
        assertEquals("INACTIVE", result.get(2L).status());
    }

    @Test void reserveStockChecksAndDecrements() {
        var food = food(1, 5, "ACTIVE");
        when(foods.findById(1L)).thenReturn(Optional.of(food));
        var snapshot = service.reserveStock(1, 5);
        assertEquals(0, food.getStockQuantity());
        assertEquals("Phở", snapshot.name());
        assertEquals(new BigDecimal("40000.00"), snapshot.price());
        assertError(BusinessException.Type.CONFLICT, "INSUFFICIENT_STOCK", () -> service.reserveStock(1, 1));
        assertEquals(0, food.getStockQuantity());

        when(foods.findById(2L)).thenReturn(Optional.of(food(2, 5, "INACTIVE")));
        assertError(BusinessException.Type.CONFLICT, "FOOD_UNAVAILABLE", () -> service.reserveStock(2, 1));
        assertError(BusinessException.Type.NOT_FOUND, "FOOD_NOT_FOUND", () -> service.reserveStock(3, 1));
    }

    @Test void releaseStockRestoresEvenForInactiveFood() {
        var food = food(2, 1, "INACTIVE");
        when(foods.findById(2L)).thenReturn(Optional.of(food));
        service.releaseStock(2, 3);
        assertEquals(4, food.getStockQuantity());
        assertError(BusinessException.Type.NOT_FOUND, "FOOD_NOT_FOUND", () -> service.releaseStock(3, 1));
    }
}
