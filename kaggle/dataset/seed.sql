-- Optional sample data: run once on an empty ByteMe database.
-- Synthetic accounts; password ByteMeDemo!2026, stored as BCrypt.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_as_ci;
SET time_zone = '+00:00';
START TRANSACTION;

INSERT INTO `users`
    (`id`, `full_name`, `email`, `phone`, `password_hash`, `role`, `status`, `created_at`, `updated_at`)
VALUES
    (1, 'Quản trị ByteMe', 'admin@byteme.example', '0900000001', '$2b$10$G62Lilvg88zuj9uaBhyVS.6353AW89LP/D3PEnYridbRObApk.MfW', 'ADMIN', 'ACTIVE', '2026-09-01 00:00:00', '2026-09-01 00:00:00'),
    (2, 'Nguyễn Minh Hương', 'huong.employee@byteme.example', '0900000002', '$2b$10$G62Lilvg88zuj9uaBhyVS.6353AW89LP/D3PEnYridbRObApk.MfW', 'EMPLOYEE', 'ACTIVE', '2026-09-01 00:00:00', '2026-09-01 00:00:00'),
    (3, 'Trần Quốc Nam', 'nam.employee@byteme.example', '0900000003', '$2b$10$G62Lilvg88zuj9uaBhyVS.6353AW89LP/D3PEnYridbRObApk.MfW', 'EMPLOYEE', 'ACTIVE', '2026-09-01 00:00:00', '2026-09-01 00:00:00'),
    (4, 'Nguyễn Hoàng An', 'an.customer@byteme.example', '0900000004', '$2b$10$G62Lilvg88zuj9uaBhyVS.6353AW89LP/D3PEnYridbRObApk.MfW', 'CUSTOMER', 'ACTIVE', '2026-09-02 00:00:00', '2026-09-02 00:00:00'),
    (5, 'Lê Thanh Bình', 'binh.customer@byteme.example', '0900000005', '$2b$10$G62Lilvg88zuj9uaBhyVS.6353AW89LP/D3PEnYridbRObApk.MfW', 'CUSTOMER', 'ACTIVE', '2026-09-02 00:00:00', '2026-09-02 00:00:00'),
    (6, 'Phạm Thùy Chi', 'chi.customer@byteme.example', '0900000006', '$2b$10$G62Lilvg88zuj9uaBhyVS.6353AW89LP/D3PEnYridbRObApk.MfW', 'CUSTOMER', 'ACTIVE', '2026-09-02 00:00:00', '2026-09-02 00:00:00'),
    (7, 'Vũ Anh Dũng', 'dung.inactive@byteme.example', '0900000007', '$2b$10$G62Lilvg88zuj9uaBhyVS.6353AW89LP/D3PEnYridbRObApk.MfW', 'CUSTOMER', 'INACTIVE', '2026-09-03 00:00:00', '2026-09-03 00:00:00'),
    (8, 'Đỗ Thu Hà', 'ha.locked@byteme.example', '0900000008', '$2b$10$G62Lilvg88zuj9uaBhyVS.6353AW89LP/D3PEnYridbRObApk.MfW', 'CUSTOMER', 'LOCKED', '2026-09-03 00:00:00', '2026-09-20 00:00:00');

INSERT INTO `categories`
    (`id`, `name`, `description`, `created_at`, `updated_at`)
VALUES
    (1, 'Phở và bún', 'Các món phở, bún nước và bún trộn.', '2026-09-01 00:00:00', '2026-09-01 00:00:00'),
    (2, 'Cơm', 'Các món cơm phần.', '2026-09-01 00:00:00', '2026-09-01 00:00:00'),
    (3, 'Bánh mì', 'Bánh mì với nhiều loại nhân.', '2026-09-01 00:00:00', '2026-09-01 00:00:00'),
    (4, 'Món chay', 'Các món chính chế biến từ nguyên liệu thực vật.', '2026-09-01 00:00:00', '2026-09-01 00:00:00'),
    (5, 'Thức uống', 'Trà, cà phê và sữa đậu nành.', '2026-09-01 00:00:00', '2026-09-01 00:00:00'),
    (6, 'Tráng miệng', 'Chè và bánh ngọt.', '2026-09-01 00:00:00', '2026-09-01 00:00:00');

INSERT INTO `foods`
    (`id`, `category_id`, `name`, `description`, `price`, `image_url`, `stock_quantity`, `status`, `version`, `created_at`, `updated_at`)
VALUES
    (1, 1, 'Phở bò tái', 'Bánh phở mềm, thịt bò tái và nước dùng ninh xương.', 65000.00, NULL, 38, 'ACTIVE', 0, '2026-09-01 00:00:00', '2026-09-30 00:00:00'),
    (2, 1, 'Phở gà', 'Phở gà ta với hành lá và lá chanh.', 60000.00, NULL, 45, 'ACTIVE', 0, '2026-09-01 00:00:00', '2026-09-30 00:00:00'),
    (3, 1, 'Bún chả Hà Nội', 'Bún, chả nướng và nước chấm chua ngọt.', 70000.00, NULL, 30, 'ACTIVE', 0, '2026-09-01 00:00:00', '2026-09-30 00:00:00'),
    (4, 3, 'Bánh mì bò', 'Bánh mì giòn với bò xào và rau thơm.', 35000.00, NULL, 50, 'ACTIVE', 0, '2026-09-01 00:00:00', '2026-09-30 00:00:00'),
    (5, 5, 'Trà đào', 'Trà đen, đào ngâm và đá.', 25000.00, NULL, 60, 'ACTIVE', 0, '2026-09-01 00:00:00', '2026-09-30 00:00:00'),
    (6, 6, 'Chè sen', 'Chè hạt sen thanh mát; hiện hết phần trong ngày.', 30000.00, NULL, 0, 'ACTIVE', 0, '2026-09-01 00:00:00', '2026-09-30 02:00:00'),
    (7, 2, 'Cơm tấm sườn', 'Cơm tấm, sườn nướng, đồ chua và mỡ hành.', 55000.00, NULL, 55, 'ACTIVE', 0, '2026-09-01 00:00:00', '2026-09-30 00:00:00'),
    (8, 2, 'Cơm gà xối mỡ', 'Cơm với đùi gà chiên giòn và dưa leo.', 60000.00, NULL, 35, 'ACTIVE', 1, '2026-09-01 00:00:00', '2026-09-28 00:00:00'),
    (9, 1, 'Bún thịt nướng', 'Bún trộn với thịt nướng và đậu phộng.', 50000.00, NULL, 48, 'ACTIVE', 0, '2026-09-01 00:00:00', '2026-09-30 00:00:00'),
    (10, 3, 'Bánh mì thịt', 'Bánh mì thịt nguội, pa tê và đồ chua.', 25000.00, NULL, 70, 'ACTIVE', 0, '2026-09-01 00:00:00', '2026-09-30 00:00:00'),
    (11, 5, 'Cà phê sữa đá', 'Cà phê phin pha sữa đặc.', 22000.00, NULL, 100, 'ACTIVE', 1, '2026-09-01 00:00:00', '2026-09-28 00:00:00'),
    (12, 6, 'Bánh flan', 'Bánh flan caramel; đã ngừng bán.', 18000.00, NULL, 0, 'INACTIVE', 1, '2026-09-01 00:00:00', '2026-09-28 00:00:00'),
    (13, 4, 'Cơm chay', 'Cơm, nấm kho và rau theo mùa.', 45000.00, NULL, 40, 'ACTIVE', 0, '2026-09-01 00:00:00', '2026-09-30 00:00:00'),
    (14, 5, 'Trà đào ít đường', 'Trà đào ít đường, phù hợp với thực đơn của nhà hàng ByteMe.', 20000.00, NULL, 80, 'ACTIVE', 0, '2026-09-01 00:00:00', '2026-09-30 00:00:00'),
    (15, 4, 'Bún riêu chay', 'Bún nước cà chua, đậu hũ và nấm.', 50000.00, NULL, 30, 'ACTIVE', 0, '2026-09-01 00:00:00', '2026-09-30 00:00:00'),
    (16, 3, 'Bánh mì chay', 'Bánh mì nhân nấm, đậu hũ và rau.', 28000.00, NULL, 45, 'ACTIVE', 0, '2026-09-01 00:00:00', '2026-09-30 00:00:00'),
    (17, 5, 'Sữa đậu nành', 'Sữa đậu nành nguyên chất.', 18000.00, NULL, 70, 'ACTIVE', 0, '2026-09-01 00:00:00', '2026-09-30 00:00:00'),
    (18, 6, 'Chè đậu xanh', 'Chè đậu xanh với nước cốt dừa.', 25000.00, NULL, 50, 'ACTIVE', 0, '2026-09-01 00:00:00', '2026-09-30 00:00:00');

INSERT INTO `carts`
    (`id`, `user_id`, `version`, `created_at`, `updated_at`)
VALUES
    (1, 4, 3, '2026-09-02 00:00:00', '2026-09-30 04:00:00'),
    (2, 5, 3, '2026-09-02 00:00:00', '2026-09-30 04:00:00'),
    (3, 6, 0, '2026-09-02 00:00:00', '2026-09-30 04:00:00'),
    (4, 7, 0, '2026-09-03 00:00:00', '2026-09-03 00:00:00'),
    (5, 8, 0, '2026-09-03 00:00:00', '2026-09-03 00:00:00');

INSERT INTO `cart_items`
    (`id`, `cart_id`, `food_id`, `quantity`, `created_at`, `updated_at`)
VALUES
    (1, 1, 1, 2, '2026-09-30 04:00:00', '2026-09-30 04:00:00'),
    (2, 1, 2, 1, '2026-09-30 04:00:00', '2026-09-30 04:00:00'),
    (3, 1, 14, 2, '2026-09-30 04:00:00', '2026-09-30 04:00:00'),
    (4, 2, 7, 1, '2026-09-30 04:00:00', '2026-09-30 04:00:00'),
    (5, 2, 11, 2, '2026-09-30 04:00:00', '2026-09-30 04:00:00'),
    (6, 2, 10, 1, '2026-09-30 04:00:00', '2026-09-30 04:00:00');

INSERT INTO `orders`
    (`id`, `user_id`, `status`, `order_type`, `table_number`, `recipient_name`, `shipping_phone`, `shipping_address`, `payment_method`, `note`, `confirmed_by`, `confirmed_at`, `cancelled_at`, `completed_at`, `version`, `created_at`, `updated_at`)
VALUES
    (1001, 4, 'PENDING', 'DELIVERY', NULL, 'Nguyễn Hoàng An', '0900000004', '10 Đường Khách Hàng, Hà Nội (địa chỉ mẫu)', 'COD', 'Gọi trước khi giao.', NULL, NULL, NULL, NULL, 0, '2026-09-30 03:00:00', '2026-09-30 03:00:00'),
    (1002, 5, 'CONFIRMED', 'DINE_IN', 'B02', NULL, NULL, NULL, 'CASH', NULL, 2, '2026-09-30 03:00:00', NULL, NULL, 1, '2026-09-30 02:50:00', '2026-09-30 03:00:00'),
    (1003, 6, 'PROCESSING', 'DINE_IN', 'B03', NULL, NULL, NULL, 'BANK_TRANSFER', 'Không dùng hành.', 3, '2026-09-30 02:05:00', NULL, NULL, 2, '2026-09-30 02:00:00', '2026-09-30 02:30:00'),
    (1004, 4, 'SHIPPING', 'DELIVERY', NULL, 'Nguyễn Hoàng An', '0900000004', '10 Đường Khách Hàng, Hà Nội (địa chỉ mẫu)', 'COD', NULL, 2, '2026-09-30 01:05:00', NULL, NULL, 3, '2026-09-30 01:00:00', '2026-09-30 02:00:00'),
    (1005, 5, 'COMPLETED', 'DINE_IN', 'B05', NULL, NULL, NULL, 'BANK_TRANSFER', NULL, 2, '2026-09-26 02:05:00', NULL, '2026-09-26 03:15:00', 4, '2026-09-26 02:00:00', '2026-09-26 03:15:00'),
    (1006, 6, 'CANCELLED', 'DINE_IN', 'B06', NULL, NULL, NULL, 'CASH', 'Khách hủy khi đơn còn PENDING; đã hoàn tồn kho.', NULL, NULL, '2026-09-29 07:10:00', NULL, 1, '2026-09-29 07:00:00', '2026-09-29 07:10:00'),
    (1007, 4, 'COMPLETED', 'DELIVERY', NULL, 'Nguyễn Hoàng An', '0900000004', 'Địa chỉ giao hàng cũ, Hà Nội (địa chỉ mẫu)', 'COD', 'Đơn lịch sử có giá cà phê cũ.', 3, '2026-09-27 03:03:00', NULL, '2026-09-27 04:00:00', 4, '2026-09-27 03:00:00', '2026-09-27 04:00:00'),
    (1008, 5, 'PENDING', 'DELIVERY', NULL, 'Lê Thanh Bình', '0900000005', '20 Đường Khách Hàng, TP. Hồ Chí Minh (địa chỉ mẫu)', 'BANK_TRANSFER', 'Đơn giao tận nhà gồm món chính và tráng miệng.', NULL, NULL, NULL, NULL, 0, '2026-09-30 03:30:00', '2026-09-30 03:30:00');

INSERT INTO `order_items`
    (`id`, `order_id`, `food_id`, `food_name`, `unit_price`, `quantity`, `created_at`)
VALUES
    (1, 1001, 1, 'Phở bò tái', 65000.00, 2, '2026-09-30 03:00:00'),
    (2, 1001, 5, 'Trà đào', 25000.00, 2, '2026-09-30 03:00:00'),
    (3, 1002, 7, 'Cơm tấm sườn', 55000.00, 1, '2026-09-30 02:50:00'),
    (4, 1002, 11, 'Cà phê sữa đá', 22000.00, 1, '2026-09-30 02:50:00'),
    (5, 1003, 13, 'Cơm chay', 45000.00, 2, '2026-09-30 02:00:00'),
    (6, 1003, 17, 'Sữa đậu nành', 18000.00, 2, '2026-09-30 02:00:00'),
    (7, 1004, 3, 'Bún chả Hà Nội', 70000.00, 1, '2026-09-30 01:00:00'),
    (8, 1004, 6, 'Chè sen', 30000.00, 2, '2026-09-30 01:00:00'),
    (9, 1005, 8, 'Cơm gà xối mỡ', 58000.00, 2, '2026-09-26 02:00:00'),
    (10, 1005, 12, 'Bánh flan', 18000.00, 2, '2026-09-26 02:00:00'),
    (11, 1006, 15, 'Bún riêu chay', 50000.00, 1, '2026-09-29 07:00:00'),
    (12, 1006, 14, 'Trà đào', 20000.00, 2, '2026-09-29 07:00:00'),
    (13, 1007, 4, 'Bánh mì bò', 35000.00, 2, '2026-09-27 03:00:00'),
    (14, 1007, 11, 'Cà phê sữa đá', 20000.00, 2, '2026-09-27 03:00:00'),
    (15, 1008, 9, 'Bún thịt nướng', 50000.00, 2, '2026-09-30 03:30:00'),
    (16, 1008, 18, 'Chè đậu xanh', 25000.00, 1, '2026-09-30 03:30:00');

COMMIT;
