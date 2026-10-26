# ByteMe

Backend REST/JSON đặt món cho một nhà hàng: khách đăng ký, xem thực đơn, quản lý giỏ và đặt món ăn tại chỗ (`DINE_IN`) hoặc giao hàng (`DELIVERY`); nhân viên xử lý đơn; admin quản lý món ăn.

Java 21, Spring Boot 3.5.16, MySQL 8.4, Spring Data JPA, Spring Security, Springdoc OpenAPI, JUnit 5 và Testcontainers. Maven 3.9.11 qua Maven Wrapper.

- **Swagger UI**: http://localhost:8080/swagger-ui/index.html
- **OpenAPI spec**: http://localhost:8080/openapi.yaml
- **Tài liệu chi tiết**: [docs/architecture-single-restaurant.md](docs/architecture-single-restaurant.md), [docs/database-design.md](docs/database-design.md), đặc tả use case [docs/Project KTPM.md](<docs/Project KTPM.md>).

---

## Cài đặt và khởi chạy

### Yêu cầu môi trường
- Java 21 trở lên
- Docker và Docker Compose
- Python 3 (thư viện `cryptography==50.0.2` để sinh cặp khóa RSA cho JWT)

### 1. Chuẩn bị cặp khóa RSA
Ứng dụng sử dụng cặp khóa RSA đặt trong `.local` (đã gitignore) để phát và kiểm tra JWT:
```sh
python3 -m venv .venv
.venv/bin/pip install cryptography==50.0.2
.venv/bin/python scripts/generate-keys.py
```

### 2. Khởi chạy ứng dụng

#### Cách 1: Chạy bằng Docker Compose (Khuyên dùng)
Chuẩn bị bản sao khóa cho Docker và build/khởi chạy toàn bộ hệ thống (MySQL + Backend):
```sh
.venv/bin/python scripts/prepare-docker-keys.py
docker compose up --build -d
```

Nạp dữ liệu mẫu vào MySQL của Docker Compose (chạy một lần):
```sh
docker compose exec mysql sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" < /opt/byteme/seed.sql'
```

#### Cách 2: Chạy trực tiếp trên máy host (Local JVM)
Chạy riêng container MySQL:
```sh
docker compose up mysql -d
```
Sau đó khởi chạy backend qua Maven Wrapper:
- **Linux / macOS**:
  ```sh
  DB_URL=jdbc:mysql://localhost:3306/starter DB_USERNAME=starter DB_PASSWORD=local-development-only ./mvnw spring-boot:run
  ```
- **Windows (PowerShell)**:
  ```powershell
  $env:DB_URL="jdbc:mysql://localhost:3306/starter"; $env:DB_USERNAME="starter"; $env:DB_PASSWORD="local-development-only"; .\mvnw.cmd spring-boot:run
  ```

### Tài khoản mẫu (Seed Accounts)
Sau khi nạp `seed.sql`, hệ thống có sẵn các tài khoản thử nghiệm với mật khẩu chung là **`ByteMeDemo!2026`**:
- **ADMIN**: `admin@byteme.example` (thêm, sửa, ngừng bán món ăn)
- **EMPLOYEE**: `huong.employee@byteme.example` hoặc `nam.employee@byteme.example` (tiếp nhận đơn, cập nhật trạng thái đơn)
- **CUSTOMER**: `an.customer@byteme.example`, `binh.customer@byteme.example` (xem giỏ, đặt hàng, hủy đơn của mình)

---

## Kiến trúc

Modular monolith gồm 4 module: `auth`, `food`, `cart`, `order`. Mọi module dùng cùng một kiểu phân tầng:

```
HTTP/JSON
   │
   ▼
Spring Security filter (JWT) ── xác thực + phân quyền theo vai trò cho mọi endpoint
   │
   ▼
controller   nhận/trả DTO, lấy user từ JWT, không chứa nghiệp vụ
   │
   ▼
service      nghiệp vụ; KHÔNG import framework web hay thư viện DB
   │
   ▼
repository   Spring Data JPA (entity) → MySQL
```

```
src/main/java/org/example/
├── auth/  food/  cart/  order/      mỗi module: controller/ dto/ entity/ repository/ service/
│                                    (order/domain/: enum OrderStatus, OrderType, PaymentMethod)
├── common/                          BusinessException, CurrentUser, ApiError, GlobalErrors
├── shared/api/                      AuthenticatedUser, Role (dùng chung với Auth)
├── config/SecurityConfiguration     phân quyền endpoint theo vai trò
└── platform/                        BCrypt, phát/kiểm tra JWT, rate limit đăng nhập
```

### Quy tắc thiết kế (kiểm tra tự động qua `ArchitectureTest`)
- **Tầng service**: Không phụ thuộc Spring Web/HTTP, Servlet, Spring Data, JDBC, JPA hay Hibernate.
  - Service của Food/Cart/Order ném `BusinessException` (`INVALID`/`FORBIDDEN`/`NOT_FOUND`/`CONFLICT` + mã lỗi).
  - Lớp `GlobalErrors` chuyển đổi tự động thành HTTP 400/403/404/409 với body chuẩn:
    `{status, code, message, path, fieldErrors}`.
  - Module Auth dùng cơ chế riêng (`AuthFailure` và `AuthErrors`).
  - Service sử dụng entity JPA làm mô hình dữ liệu và `@Transactional` (spring-tx) để quản lý ranh giới transaction.
  - Thao tác riêng của ORM (như `flush`) được đóng gói trong repository (ví dụ `CartRepository.saveChanges`); service chỉ gọi thao tác CRUD và phương thức nghiệp vụ.
- **Validation**: Bean Validation (`@Valid` trên DTO) kiểm tra trường bắt buộc và định dạng; service chỉ kiểm tra quy tắc nghiệp vụ (ví dụ các trường bắt buộc theo loại đơn `DINE_IN`/`DELIVERY`).
- **Phân tách trách nhiệm**: Controller không gọi trực tiếp repository.
- **Giao tiếp liên module**: Chỉ gọi qua **service/dto** của nhau, không truy cập chéo entity/repository:
  - Cart gọi `FoodService`.
  - Order gọi `CartService` và `FoodService`.
- **Bảo mật**: Xác thực và phân quyền tập trung tại `SecurityConfiguration`; service chỉ kiểm tra quyền sở hữu tài nguyên (khách chỉ xem/hủy đơn của mình).

### Quy trình Checkout & Hủy đơn
Checkout (`POST /api/orders`) chạy đồng bộ trong **một database transaction**:
1. Đọc giỏ của khách theo thứ tự dòng; chưa có giỏ hoặc giỏ rỗng trả `400 EMPTY_CART`.
2. Với từng món: đọc món, kiểm tra trạng thái bán và tồn kho, trừ tồn kho (`stock = stock - qty`).
3. Lưu đơn hàng `PENDING` với snapshot tên và giá món tại thời điểm đặt (giá do server quyết định).
4. Dọn sạch giỏ hàng. Lỗi ở bất kỳ bước nào sẽ rollback toàn bộ.

Hủy đơn: kiểm tra trạng thái hợp lệ, sau đó hoàn kho từng dòng sản phẩm trong cùng transaction.

`GET /api/cart`: khi khách chưa có giỏ sẽ trả giỏ rỗng (`id: null`, `items: []`) và không tạo bản ghi trong DB; bản ghi giỏ chỉ được tạo ở lần thêm món đầu tiên.

### Giới hạn Phase 1
Theo yêu cầu triển khai cơ bản ban đầu, Phase 1 chưa dùng khóa hàng (`SELECT … FOR UPDATE`) hay khóa lạc quan (`@Version`):
- Checkout đồng thời có thể gặp lost update tồn kho (tuy nhiên tồn kho không âm do service luôn kiểm tra `stock >= qty` trước khi ghi).
- Hai yêu cầu hủy cùng một đơn chạy đồng thời có thể hoàn kho 2 lần.
- Admin sửa món đồng thời với checkout có thể ghi đè tồn kho.

*Các điểm này sẽ được xử lý tại Phase 2 cùng cơ chế chống tranh chấp đồng thời.*

---

## Xác thực & Phân quyền (Auth)

- Auth và xác thực JWT luôn bật mặc định trên toàn hệ thống (không có profile bypass).
- **Endpoint công khai**: Xem thực đơn, tìm kiếm món, Swagger UI, OpenAPI spec, đăng ký (`POST /api/auth/register`) và đăng nhập (`POST /api/auth/token`).
- **Thời hạn JWT (TTL)**: 5.400 giây (90 phút).
- **Chống brute-force (Rate Limit in-memory)**: Tối đa 10 lần/email và 100 lần/IP trong 900 giây (15 phút).
- **Yêu cầu JWT**: Tầng Cart và Order yêu cầu trường `sub` (subject) trong JWT bắt buộc phải là **ID số nguyên dương** (ID tài khoản trong database). Subject không phải số sẽ bị trả về 403.

Tạo token fixture nhanh để thử nghiệm phân quyền cục bộ:
```sh
bash scripts/issue-local-tokens.sh
```

---

## Đặc tả API

OpenAPI: `/openapi.yaml` | Swagger UI: `/swagger-ui/index.html`

| UC | Method | Endpoint | Quyền hạn |
| --- | --- | --- | --- |
| UC01 Đăng ký | POST | `/api/auth/register` | Công khai |
| UC02 Đăng nhập | POST | `/api/auth/token` | Công khai |
| UC03 Danh sách món | GET | `/api/foods?categoryId=` | Công khai |
| UC04 Chi tiết món | GET | `/api/foods/{foodId}` | Công khai |
| UC05 Tìm món | GET | `/api/foods/search?q=&categoryId=` | Công khai |
| UC06 Thêm món | POST | `/api/foods` | ADMIN |
| UC07 Sửa món | PUT | `/api/foods/{foodId}` | ADMIN |
| UC08 Ngừng bán món | DELETE | `/api/foods/{foodId}` | ADMIN |
| UC09 Xem giỏ | GET | `/api/cart` | CUSTOMER |
| UC10 Thêm vào giỏ | POST | `/api/cart/items` | CUSTOMER |
| UC11 Sửa số lượng | PUT | `/api/cart/items/{cartItemId}` | CUSTOMER |
| UC12 Xóa khỏi giỏ | DELETE | `/api/cart/items/{cartItemId}` | CUSTOMER |
| UC13 Tạo đơn | POST | `/api/orders` | CUSTOMER |
| UC14 Xem đơn | GET | `/api/orders`, `/api/orders/{orderId}` | CUSTOMER (đơn của mình) / EMPLOYEE |
| UC15 Hủy đơn | DELETE | `/api/orders/{orderId}` | CUSTOMER (chỉ đơn PENDING) |
| UC16 Xác nhận đơn | POST | `/api/orders/{orderId}/confirm` | EMPLOYEE |
| UC17 Đổi trạng thái | PUT | `/api/orders/{orderId}/status` | EMPLOYEE |
| UC18 Đơn cần xử lý | GET | `/api/orders?status=&orderType=&page=&size=` | EMPLOYEE |

### Trạng thái đơn hàng
- Luồng chính: `PENDING → CONFIRMED → PROCESSING`
  - Đơn ăn tại chỗ (`DINE_IN`): `PROCESSING → COMPLETED`
  - Đơn giao hàng (`DELIVERY`): `PROCESSING → SHIPPING → COMPLETED`
- Khách chỉ được hủy khi đơn ở `PENDING`. Nhân viên có thể hủy khi đơn ở `PENDING`, `CONFIRMED` hoặc `PROCESSING`.

### Mã lỗi nghiệp vụ thường gặp
- **400**: `VALIDATION_ERROR` (sai tham số DTO), `EMPTY_CART` (giỏ hàng rỗng).
- **403**: `FORBIDDEN` (không đủ quyền hạn hoặc không phải chủ đơn hàng).
- **404**: `FOOD_NOT_FOUND`, `CATEGORY_NOT_FOUND`, `CART_ITEM_NOT_FOUND`, `ORDER_NOT_FOUND`.
- **409**: `FOOD_UNAVAILABLE` (món ngừng bán), `INSUFFICIENT_STOCK` (không đủ tồn kho), `FOOD_NAME_CONFLICT` (trùng tên món), `INVALID_ORDER_TRANSITION` (chuyển sai trạng thái), `CONFLICT` (vi phạm ràng buộc dữ liệu).

---

## Luồng Giỏ hàng → Đặt đơn (Cart → Order)

Sau khi đăng nhập tài khoản `CUSTOMER`, thêm món vào giỏ qua `POST /api/cart/items` (`{"foodId": 1, "quantity": 2}`), sau đó tạo đơn qua `POST /api/orders`:

**Đơn ăn tại chỗ (`DINE_IN`)**:
```json
{
  "orderType": "DINE_IN",
  "tableNumber": "B05",
  "paymentMethod": "CASH",
  "note": "Ít cay"
}
```
*Phương thức thanh toán hợp lệ: `CASH` hoặc `BANK_TRANSFER`.*

**Đơn giao hàng (`DELIVERY`)**:
```json
{
  "orderType": "DELIVERY",
  "recipientName": "An",
  "shippingPhone": "0900000004",
  "shippingAddress": "12 Nguyễn Văn Cừ",
  "paymentMethod": "COD"
}
```
*Phương thức thanh toán hợp lệ: `COD` hoặc `BANK_TRANSFER`.*

### Lưu ý quan trọng
- Client **không gửi** danh sách món, đơn giá hay `userId`; server tự lấy từ giỏ hàng và JWT.
- Giá trị enum (`DINE_IN`, `CASH`, `COD`...) bắt buộc gửi dạng tên chuỗi; gửi dạng số hoặc chuỗi số sẽ trả về lỗi 400.
- `GET /api/orders`: Hỗ trợ phân trang với `page` mặc định là `0`, `size` mặc định là `20` (tối đa 100), sắp xếp đơn mới nhất lên đầu.
- Chưa hỗ trợ idempotency key: gửi lại request POST tạo đơn khi giỏ đã dọn sẽ trả lỗi `400 EMPTY_CART`.

---

## Kiểm thử

### 1. Chạy Unit & Integration Tests (Testcontainers)
Yêu cầu Docker Daemon đang chạy để Testcontainers khởi tạo MySQL thật:
- Linux / macOS: `./mvnw test`
- Windows: `.\mvnw.cmd test`

*Bao gồm test service, test bảo mật MockMvc, test MySQL tích hợp (checkout, rollback, trừ/hoàn kho) và kiểm tra phụ thuộc qua `ArchitectureTest`.*

### 2. Chạy kịch bản kiểm thử API tự động
Kiểm thử toàn diện các endpoint trên hệ thống đang chạy (localhost:8080):
```sh
python -X utf8 scripts/test-endpoints.py
```

### 3. Kiểm thử tải (Load Testing)
Kịch bản và báo cáo đo tải trên Kaggle: xem [kaggle/script/README.md](kaggle/script/README.md).
