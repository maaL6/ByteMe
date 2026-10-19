package org.example.cart.service;

import lombok.RequiredArgsConstructor;
import org.example.cart.dto.AddToCartRequest;
import org.example.cart.dto.CartDto;
import org.example.cart.dto.CartItemDto;
import org.example.cart.dto.UpdateCartItemRequest;
import org.example.cart.entity.Cart;
import org.example.cart.entity.CartItem;
import org.example.cart.repository.CartRepository;
import org.example.common.BusinessException;
import org.example.food.dto.FoodDto;
import org.example.food.service.FoodService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final FoodService foodService;

    @Transactional(readOnly = true)
    public CartDto getCart(long userId) {
        Cart cart = cartRepository.findByUserId(userId).orElseThrow(CartService::cartNotFound);
        return toCartDto(cart);
    }

    @Transactional
    public CartDto addToCart(long userId, AddToCartRequest request) {
        cartRepository.ensureExists(userId);
        Cart cart = lockCart(userId);
        FoodDto food = foodService.getAvailableFood(request.getFoodId());

        Optional<CartItem> existingItem = cart.getItems().stream()
                .filter(item -> item.getFoodId().equals(food.getId()))
                .findFirst();

        if (existingItem.isPresent()) {
            CartItem item = existingItem.get();
            try {
                item.setQuantity(Math.addExact(item.getQuantity(), request.getQuantity()));
            } catch (ArithmeticException error) {
                throw BusinessException.invalid("VALIDATION_ERROR", "Số lượng quá lớn.");
            }
        } else {
            CartItem newItem = CartItem.builder()
                    .cart(cart)
                    .foodId(food.getId())
                    .quantity(request.getQuantity())
                    .build();
            cart.getItems().add(newItem);
        }

        saveChanges(cart);
        return toCartDto(cart);
    }

    @Transactional
    public CartDto updateCartItem(long userId, Long cartItemId, UpdateCartItemRequest request) {
        Cart cart = lockCart(userId);

        CartItem cartItem = cart.getItems().stream()
                .filter(item -> item.getId().equals(cartItemId))
                .findFirst()
                .orElseThrow(CartService::cartItemNotFound);

        cartItem.setQuantity(request.getQuantity());
        saveChanges(cart);
        return toCartDto(cart);
    }

    @Transactional
    public CartDto removeCartItem(long userId, Long cartItemId) {
        Cart cart = lockCart(userId);

        boolean removed = cart.getItems().removeIf(item -> item.getId().equals(cartItemId));
        if (!removed) {
            throw cartItemNotFound();
        }

        saveChanges(cart);
        return toCartDto(cart);
    }

    // ----- Dùng bởi module Order, luôn trong giao dịch checkout -----

    /**
     * Khóa giỏ tới hết giao dịch checkout (thêm/sửa giỏ đồng thời phải chờ) và trả về
     * foodId → số lượng, sắp theo foodId tăng dần để Order khóa món đúng thứ tự.
     */
    @Transactional
    public SortedMap<Long, Integer> lockItemsForCheckout(long userId) {
        Cart cart = lockCart(userId);
        SortedMap<Long, Integer> items = new TreeMap<>();
        cart.getItems().forEach(item -> items.put(item.getFoodId(), item.getQuantity()));
        return items;
    }

    /** Dọn giỏ sau khi tạo đơn; giữ lại hàng carts để khách tiếp tục thêm món. */
    @Transactional
    public void clearCart(long userId) {
        Cart cart = lockCart(userId);
        cart.getItems().clear();
        saveChanges(cart);
    }

    private Cart lockCart(long userId) {
        return cartRepository.lockByUserId(userId).orElseThrow(CartService::cartNotFound);
    }

    /** Ghi các item đã thêm/sửa/xóa (flush để item mới có id) rồi đánh dấu giỏ vừa thay đổi. */
    private void saveChanges(Cart cart) {
        cartRepository.saveAndFlush(cart);
        cartRepository.touch(cart.getId());
    }

    private CartDto toCartDto(Cart cart) {
        Map<Long, FoodDto> foods = foodService.findByIds(
                cart.getItems().stream().map(CartItem::getFoodId).toList());
        return CartDto.builder()
                .id(cart.getId())
                .userId(cart.getUserId())
                .items(cart.getItems().stream()
                        .map(item -> CartItemDto.builder()
                                .id(item.getId())
                                .food(foods.get(item.getFoodId()))
                                .quantity(item.getQuantity())
                                .build())
                        .toList())
                .build();
    }

    private static BusinessException cartNotFound() {
        return BusinessException.notFound("CART_NOT_FOUND", "Không tìm thấy giỏ hàng.");
    }

    private static BusinessException cartItemNotFound() {
        return BusinessException.notFound("CART_ITEM_NOT_FOUND", "Không tìm thấy món trong giỏ hàng.");
    }
}
