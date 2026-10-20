-- ByteMe: MySQL 8.4, seven business tables. Times are UTC.
-- Initializes missing tables; existing tables are not automatically upgraded.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_as_ci;
SET time_zone = '+00:00';

CREATE TABLE IF NOT EXISTS `users` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `full_name` VARCHAR(150) NOT NULL,
    `email` VARCHAR(254) NOT NULL,
    `phone` VARCHAR(20) NOT NULL,
    `password_hash` VARCHAR(60) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `role` VARCHAR(20) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'CUSTOMER',
    `status` VARCHAR(20) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `uq_users_email` UNIQUE (`email`),
    CONSTRAINT `uq_users_phone` UNIQUE (`phone`),
    CONSTRAINT `ck_users_full_name` CHECK (CHAR_LENGTH(TRIM(`full_name`)) > 0),
    CONSTRAINT `ck_users_email` CHECK (
        CHAR_LENGTH(`email`) > 0 AND CHAR_LENGTH(`email`) = CHAR_LENGTH(TRIM(`email`))
    ),
    CONSTRAINT `ck_users_phone` CHECK (
        CHAR_LENGTH(`phone`) > 0 AND CHAR_LENGTH(`phone`) = CHAR_LENGTH(TRIM(`phone`))
    ),
    CONSTRAINT `ck_users_password_hash` CHECK (
        `password_hash` REGEXP '^[$]2[aby][$][0-9]{2}[$][./A-Za-z0-9]{53}$'
    ),
    CONSTRAINT `ck_users_role` CHECK (`role` IN ('CUSTOMER', 'EMPLOYEE', 'ADMIN')),
    CONSTRAINT `ck_users_status` CHECK (`status` IN ('ACTIVE', 'INACTIVE', 'LOCKED')),
    CONSTRAINT `ck_users_timestamps` CHECK (`updated_at` >= `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE IF NOT EXISTS `categories` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL,
    `description` VARCHAR(500) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `uq_categories_name` UNIQUE (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE IF NOT EXISTS `foods` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `category_id` BIGINT NOT NULL,
    `name` VARCHAR(150) NOT NULL,
    `description` TEXT NULL,
    `price` DECIMAL(15,2) NOT NULL COMMENT 'VND; never use floating-point money',
    `image_url` VARCHAR(2048) NULL,
    `stock_quantity` INT NOT NULL DEFAULT 0 COMMENT 'Available units; service reserves/releases in checkout/cancellation transaction',
    `status` VARCHAR(20) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `uq_foods_name` UNIQUE (`name`),
    CONSTRAINT `fk_foods_category` FOREIGN KEY (`category_id`) REFERENCES `categories` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE IF NOT EXISTS `carts` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `uq_carts_user` UNIQUE (`user_id`),
    CONSTRAINT `fk_carts_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE IF NOT EXISTS `cart_items` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `cart_id` BIGINT NOT NULL,
    `food_id` BIGINT NOT NULL,
    `quantity` INT NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `uq_cart_items_cart_food` UNIQUE (`cart_id`, `food_id`),
    CONSTRAINT `fk_cart_items_cart` FOREIGN KEY (`cart_id`) REFERENCES `carts` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `fk_cart_items_food` FOREIGN KEY (`food_id`) REFERENCES `foods` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE IF NOT EXISTS `orders` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `status` VARCHAR(20) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'PENDING',
    `order_type` VARCHAR(20) COLLATE utf8mb4_0900_as_cs NOT NULL,
    `table_number` VARCHAR(20) NULL,
    `recipient_name` VARCHAR(150) NULL,
    `shipping_phone` VARCHAR(20) NULL,
    `shipping_address` VARCHAR(500) NULL,
    `payment_method` VARCHAR(20) COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'CASH at table, COD on delivery, or BANK_TRANSFER',
    `note` VARCHAR(500) NULL,
    `confirmed_by` BIGINT NULL,
    `confirmed_at` DATETIME(6) NULL,
    `cancelled_at` DATETIME(6) NULL,
    `completed_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_orders_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_orders_confirmed_by` FOREIGN KEY (`confirmed_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE IF NOT EXISTS `order_items` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `order_id` BIGINT NOT NULL,
    `food_id` BIGINT NOT NULL,
    `food_name` VARCHAR(150) NOT NULL COMMENT 'Immutable food name at checkout; independent of later food edits',
    `unit_price` DECIMAL(15,2) NOT NULL COMMENT 'Immutable VND price at checkout',
    `quantity` INT NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `uq_order_items_order_food` UNIQUE (`order_id`, `food_id`),
    CONSTRAINT `fk_order_items_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_order_items_food` FOREIGN KEY (`food_id`) REFERENCES `foods` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;
