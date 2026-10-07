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
    `normalized_name` VARCHAR(100) GENERATED ALWAYS AS (LOWER(TRIM(`name`))) STORED,
    `description` VARCHAR(500) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `uq_categories_normalized_name` UNIQUE (`normalized_name`),
    CONSTRAINT `ck_categories_name` CHECK (CHAR_LENGTH(TRIM(`name`)) > 0),
    CONSTRAINT `ck_categories_timestamps` CHECK (`updated_at` >= `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE IF NOT EXISTS `foods` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `category_id` BIGINT NOT NULL,
    `name` VARCHAR(150) NOT NULL,
    `normalized_name` VARCHAR(150) GENERATED ALWAYS AS (LOWER(TRIM(`name`))) STORED,
    `description` TEXT NULL,
    `price` DECIMAL(15,2) NOT NULL COMMENT 'VND; never use floating-point money',
    `image_url` VARCHAR(2048) NULL,
    `stock_quantity` INT NOT NULL DEFAULT 0 COMMENT 'Available units; service reserves/releases in checkout/cancellation transaction',
    `status` VARCHAR(20) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'ACTIVE',
    `version` BIGINT NOT NULL DEFAULT 0,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `uq_foods_normalized_name` UNIQUE (`normalized_name`),
    KEY `ix_foods_status_category_price` (`status`, `category_id`, `price`, `id`),
    KEY `ix_foods_status_price` (`status`, `price`, `id`),
    KEY `ix_foods_status_name` (`status`, `name`, `id`),
    KEY `ix_foods_category` (`category_id`),
    CONSTRAINT `fk_foods_category` FOREIGN KEY (`category_id`) REFERENCES `categories` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_foods_name` CHECK (CHAR_LENGTH(TRIM(`name`)) > 0),
    CONSTRAINT `ck_foods_price` CHECK (`price` > 0),
    CONSTRAINT `ck_foods_stock` CHECK (`stock_quantity` >= 0),
    CONSTRAINT `ck_foods_status` CHECK (`status` IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT `ck_foods_version` CHECK (`version` >= 0),
    CONSTRAINT `ck_foods_timestamps` CHECK (`updated_at` >= `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE IF NOT EXISTS `carts` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `version` BIGINT NOT NULL DEFAULT 0,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `uq_carts_user` UNIQUE (`user_id`),
    CONSTRAINT `fk_carts_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_carts_version` CHECK (`version` >= 0),
    CONSTRAINT `ck_carts_timestamps` CHECK (`updated_at` >= `created_at`)
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
    KEY `ix_cart_items_food` (`food_id`),
    CONSTRAINT `fk_cart_items_cart` FOREIGN KEY (`cart_id`) REFERENCES `carts` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `fk_cart_items_food` FOREIGN KEY (`food_id`) REFERENCES `foods` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_cart_items_quantity` CHECK (`quantity` > 0),
    CONSTRAINT `ck_cart_items_timestamps` CHECK (`updated_at` >= `created_at`)
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
    `version` BIGINT NOT NULL DEFAULT 0,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    KEY `ix_orders_user_created` (`user_id`, `created_at`, `id`),
    KEY `ix_orders_user_status_created` (`user_id`, `status`, `created_at`, `id`),
    KEY `ix_orders_status_created` (`status`, `created_at`, `id`),
    KEY `ix_orders_confirmed_by` (`confirmed_by`),
    CONSTRAINT `fk_orders_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_orders_confirmed_by` FOREIGN KEY (`confirmed_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_orders_status` CHECK (`status` IN ('PENDING', 'CONFIRMED', 'PROCESSING', 'SHIPPING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT `ck_orders_type` CHECK (`order_type` IN ('DINE_IN', 'DELIVERY')),
    CONSTRAINT `ck_orders_fulfillment` CHECK (
        (`order_type` = 'DELIVERY'
         AND `table_number` IS NULL
         AND `recipient_name` IS NOT NULL AND CHAR_LENGTH(TRIM(`recipient_name`)) > 0
         AND `shipping_phone` IS NOT NULL AND CHAR_LENGTH(TRIM(`shipping_phone`)) > 0
         AND `shipping_address` IS NOT NULL AND CHAR_LENGTH(TRIM(`shipping_address`)) > 0)
        OR
        (`order_type` = 'DINE_IN'
         AND `table_number` IS NOT NULL AND CHAR_LENGTH(TRIM(`table_number`)) > 0
         AND `recipient_name` IS NULL
         AND `shipping_phone` IS NULL
         AND `shipping_address` IS NULL)
    ),
    CONSTRAINT `ck_orders_type_status` CHECK (`order_type` <> 'DINE_IN' OR `status` <> 'SHIPPING'),
    CONSTRAINT `ck_orders_payment_method` CHECK (`payment_method` IN ('CASH', 'COD', 'BANK_TRANSFER')),
    CONSTRAINT `ck_orders_type_payment` CHECK (
        `payment_method` = 'BANK_TRANSFER'
        OR (`order_type` = 'DINE_IN' AND `payment_method` = 'CASH')
        OR (`order_type` = 'DELIVERY' AND `payment_method` = 'COD')
    ),
    CONSTRAINT `ck_orders_status_timestamps` CHECK (
        (`status` = 'PENDING'
         AND `confirmed_by` IS NULL AND `confirmed_at` IS NULL
         AND `cancelled_at` IS NULL AND `completed_at` IS NULL)
        OR (`status` IN ('CONFIRMED', 'PROCESSING', 'SHIPPING')
            AND `confirmed_by` IS NOT NULL AND `confirmed_at` IS NOT NULL
            AND `cancelled_at` IS NULL AND `completed_at` IS NULL)
        OR (`status` = 'COMPLETED'
            AND `confirmed_by` IS NOT NULL AND `confirmed_at` IS NOT NULL
            AND `cancelled_at` IS NULL AND `completed_at` IS NOT NULL)
        OR (`status` = 'CANCELLED'
            AND `cancelled_at` IS NOT NULL AND `completed_at` IS NULL)
    ),
    CONSTRAINT `ck_orders_terminal_after_confirmation` CHECK (
        (`confirmed_at` IS NULL OR `cancelled_at` IS NULL OR `cancelled_at` >= `confirmed_at`)
        AND (`confirmed_at` IS NULL OR `completed_at` IS NULL OR `completed_at` >= `confirmed_at`)
    ),
    CONSTRAINT `ck_orders_confirmation_pair` CHECK (
        (`confirmed_by` IS NULL AND `confirmed_at` IS NULL)
        OR (`confirmed_by` IS NOT NULL AND `confirmed_at` IS NOT NULL)
    ),
    CONSTRAINT `ck_orders_terminal_timestamps` CHECK (`cancelled_at` IS NULL OR `completed_at` IS NULL),
    CONSTRAINT `ck_orders_confirmation_time` CHECK (`confirmed_at` IS NULL OR `confirmed_at` >= `created_at`),
    CONSTRAINT `ck_orders_cancellation_time` CHECK (`cancelled_at` IS NULL OR `cancelled_at` >= `created_at`),
    CONSTRAINT `ck_orders_completion_time` CHECK (`completed_at` IS NULL OR `completed_at` >= `created_at`),
    CONSTRAINT `ck_orders_version` CHECK (`version` >= 0),
    CONSTRAINT `ck_orders_timestamps` CHECK (`updated_at` >= `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE IF NOT EXISTS `order_items` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `order_id` BIGINT NOT NULL,
    `food_id` BIGINT NOT NULL,
    `food_name` VARCHAR(150) NOT NULL COMMENT 'Immutable food name at checkout; independent of later food edits',
    `unit_price` DECIMAL(15,2) NOT NULL COMMENT 'Immutable VND price at checkout',
    `quantity` INT NOT NULL,
    `line_total` DECIMAL(15,2) GENERATED ALWAYS AS (`unit_price` * `quantity`) STORED,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `uq_order_items_order_food` UNIQUE (`order_id`, `food_id`),
    KEY `ix_order_items_food` (`food_id`),
    CONSTRAINT `fk_order_items_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_order_items_food` FOREIGN KEY (`food_id`) REFERENCES `foods` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_order_items_name` CHECK (CHAR_LENGTH(TRIM(`food_name`)) > 0),
    CONSTRAINT `ck_order_items_price` CHECK (`unit_price` > 0),
    CONSTRAINT `ck_order_items_quantity` CHECK (`quantity` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE OR REPLACE SQL SECURITY INVOKER VIEW `order_totals` AS
SELECT o.`id` AS `order_id`, COALESCE(SUM(oi.`line_total`), 0.00) AS `total_amount`
FROM `orders` o
LEFT JOIN `order_items` oi ON oi.`order_id` = o.`id`
GROUP BY o.`id`;
