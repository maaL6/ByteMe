# ByteMe

Backend REST/JSON đặt món cho một nhà hàng: khách đăng ký, xem thực đơn, quản lý giỏ
và đặt món ăn tại chỗ (`DINE_IN`) hoặc giao hàng (`DELIVERY`); nhân viên xử lý đơn;
admin quản lý món ăn.

Java 21, Spring Boot 3.5.16, MySQL 8.4, Spring Data JPA, Spring Security,
Springdoc OpenAPI, JUnit 5 và Testcontainers. Maven 3.9.11 qua Maven Wrapper.

## Kiến trúc

Chi tiết: [docs/architecture-single-restaurant.md](docs/architecture-single-restaurant.md),
[docs/database-design.md](docs/database-design.md), đặc tả use case
[docs/Project KTPM.md](<docs/Project KTPM.md>).

Modular monolith gồm 4 module: `auth`, `food`, `cart`, `order`. Mọi module dùng cùng
một kiểu phân tầng:

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

Quy tắc chung (các quy tắc về phụ thuộc giữa tầng/module được `ArchitectureTest` kiểm tra tự động):

- Tầng `service` không phụ thuộc Spring Web/HTTP, Servlet, Spring Data, JDBC, JPA hay Hibernate.
  Service của Food/Cart/Order ném `BusinessException` (`INVALID`/`FORBIDDEN`/`NOT_FOUND`/`CONFLICT` + mã lỗi);
  `GlobalErrors` chuyển thành HTTP 400/403/404/409 với body chung
  `{status, code, message, path, fieldErrors}`. Auth dùng `AuthFailure` và handler riêng `AuthErrors`.
  Service vẫn dùng entity JPA làm mô hình dữ liệu và `@Transactional` (spring-tx) để khai báo
  ranh giới giao dịch; tách thêm lớp domain riêng sẽ gấp đôi số lớp mà không thêm chức năng.
  Thao tác riêng của ORM (flush) nằm trong repository, ví dụ
  `CartRepository.saveChanges`; service chỉ gọi thao tác kiểu CRUD và phương thức tự khai báo.
- Bean Validation (`@Valid` trên DTO) kiểm tra trường bắt buộc và độ dài cho Food/Cart/Order
  (Auth giữ `AuthValidation` riêng);
  service chỉ kiểm tra quy tắc nghiệp vụ, như trường bắt buộc theo loại đơn `DINE_IN`/`DELIVERY`.
- Controller không gọi thẳng repository.
- Module khác chỉ gọi **service/dto** của Food, Cart, Order, không dùng entity/repository
  của nhau: Cart gọi `FoodService`; Order gọi `CartService` và `FoodService`.
- Xác thực và phân quyền chỉ nằm ở `SecurityConfiguration`; service chỉ kiểm tra quyền sở
  hữu (khách chỉ xem/hủy đơn của mình).

Checkout (`POST /api/orders`) chạy đồng bộ trong **một transaction**:

1. Đọc giỏ của khách theo thứ tự dòng giỏ; chưa có giỏ hoặc giỏ rỗng trả `400 EMPTY_CART`.
2. Với từng món: đọc món, kiểm tra còn bán và đủ kho, trừ kho.
3. Lưu đơn `PENDING` với snapshot tên/giá món (giá do server quyết định).
4. Dọn giỏ. Lỗi ở bất kỳ bước nào sẽ rollback toàn bộ.

Hủy đơn đọc đơn, kiểm tra bước chuyển rồi hoàn kho từng dòng trong cùng transaction.
`GET /api/cart` khi khách chưa có giỏ trả giỏ rỗng (`id: null`, `items: []`) và không tạo
giỏ; giỏ được tạo ở lần thêm món đầu tiên.

**Giới hạn phase 1.** Theo yêu cầu "chỉ triển khai chức năng cơ bản, không cần tối ưu",
phase 1 không dùng khóa hàng (`SELECT … FOR UPDATE`) hay khóa lạc quan (`@Version`):

- Checkout đồng thời có thể bán vượt kho do lost update: hai transaction cùng đọc một tồn kho,
  cùng ghi `stock - qty`. Tồn kho không âm vì service kiểm tra `stock >= qty` trước khi ghi.
- Hai yêu cầu hủy cùng một đơn chạy đồng thời có thể hoàn kho hai lần.
- Admin sửa món đồng thời với checkout có thể ghi đè tồn kho.
- Checkout đồng thời trên cùng món dễ gặp deadlock InnoDB (khóa S do FK của `order_items`
  rồi nâng lên khóa X khi `UPDATE foods`). Transaction bị rollback và trả
  `409 CONCURRENT_UPDATE` để client gửi lại; phase 1 không tự retry.

Các điểm này để dành cho phase 2 và là mục tiêu đo trước/sau cải tiến.

## Đặc tả API

OpenAPI: `/openapi.yaml`; Swagger UI: `/swagger-ui/index.html`.

| UC | Method | Endpoint | Quyền |
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

Trạng thái đơn: `PENDING → CONFIRMED → PROCESSING`, rồi `DINE_IN → COMPLETED` hoặc
`DELIVERY → SHIPPING → COMPLETED`. Nhân viên hủy được ở `PENDING/CONFIRMED/PROCESSING`.

Mã lỗi nghiệp vụ thường gặp: `VALIDATION_ERROR`, `EMPTY_CART` (400); `FORBIDDEN` (403);
`FOOD_NOT_FOUND`, `CATEGORY_NOT_FOUND`, `CART_ITEM_NOT_FOUND`,
`ORDER_NOT_FOUND` (404); `FOOD_UNAVAILABLE`, `INSUFFICIENT_STOCK`, `FOOD_NAME_CONFLICT`,
`INVALID_ORDER_TRANSITION`, `CONFLICT` (409, vi phạm UNIQUE của database),
`CONCURRENT_UPDATE` (409, deadlock/hết thời gian chờ khóa; gửi lại được).

## Kiểm thử

```sh
./mvnw test          # cần Docker đang chạy cho test Testcontainers (MySQL thật)
```

Nạp dữ liệu mẫu (tài khoản mật khẩu `ByteMeDemo!2026`) vào MySQL của Docker Compose một lần:

```sh
docker compose exec mysql sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" < /opt/byteme/seed.sql'
```

Gồm test service, test HTTP/phân quyền (MockMvc), test MySQL tích hợp (checkout,
rollback, trừ/hoàn kho, giỏ rỗng) và `ArchitectureTest`. Các test đồng thời (khóa hàng,
`@Version`, không bán vượt kho khi tranh chấp) đã được bỏ ở phase 1 và sẽ quay lại ở
phase 2 cùng cơ chế chống tranh chấp. Kiểm thử tải trên
Kaggle: xem [kaggle/script/README.md](kaggle/script/README.md).

## Auth mặc định — UC-01/02

Auth và xác thực JWT luôn bật; không có profile `dev` hoặc chế độ chạy tắt Auth. Health, Swagger, xem thực đơn, đăng ký và đăng nhập công khai. Các API còn lại cần JWT và được phân quyền theo CUSTOMER/EMPLOYEE/ADMIN. JWT TTL 5400 giây; logout/revocation/rotation chưa triển khai. Quota login in-memory 10/email và 100/IP trong 900 giây.

Cần Java 21 trở lên, Docker và Python 3 với cryptography (baseline 50.0.2) để chạy đầy đủ ứng dụng và test:

```sh
python3 -m venv .venv
.venv/bin/pip install cryptography==50.0.2
.venv/bin/python scripts/generate-keys.py
DB_URL=jdbc:mysql://localhost:3306/starter DB_USERNAME=starter DB_PASSWORD=local-development-only ./mvnw spring-boot:run
```

Lệnh ví dụ dùng database/user `starter` local. Cấu hình kết nối luôn đến từ `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`; với môi trường ngoài local, truyền credentials riêng qua biến môi trường. `POST /api/auth/register` nhận fullName/email/phone/password/confirmPassword, trả 201 CUSTOMER/ACTIVE. `POST /api/auth/token` nhận email/password, trả accessToken/tokenType/expiresIn/user. JSON strict và lỗi Auth chỉ áp dụng AuthController. OpenAPI thống nhất tại `/openapi.yaml`; Swagger UI tại `/swagger-ui/index.html` được cấu hình để đọc specification này thay vì tài liệu sinh tự động `/v3/api-docs`.

```sh
./mvnw test
bash scripts/issue-local-tokens.sh
```

Dev CLI tạo 4 JWT fixture local; subject fixture không phải ID users MySQL và không thay đăng nhập thật. Khóa/token nằm trong `.local` bị gitignore; không in token vào log. Script hỗ trợ macOS/Linux (file permissions POSIX), JDK 21+ trên macOS hoặc SA_JAVA_HOME/JAVA_HOME, và Python qua AUTH_DEV_PYTHON hoặc .venv.

Docker dùng bản sao khóa riêng cho runtime UID 10001. Chuẩn bị bản sao cho Docker một lần:

```sh
.venv/bin/python scripts/prepare-docker-keys.py
docker compose up --build
```

Trên Linux, cấp quyền đọc cho UID 10001 qua ownership/ACL của `.local/docker-keys`; không đổi private key sang world-readable. Trên Docker Desktop, mount read-only hoạt động với quyền file hiện tại. Compose giữ volume MySQL. Chưa có signing/rotation production hoặc NFR Kaggle; khóa/issuer/audience hiện dùng baseline local.

CLI thử nghiệm `LocalTokenCli` nằm trong `src/test/java/org/example/dev/`, script dùng test-compile và target/test-classes; CLI không được đóng gói trong application.jar. Application.main là entry point chạy backend, giữ trong src/main/java.

## Cart → Order

Dùng JWT đăng nhập thật cho cả Cart và Order; không dùng `X-User-Id`. Subject của JWT
phải là ID số dương (ID tài khoản); subject không phải số (ví dụ JWT fixture) bị 403 ở
Cart/Order. Service không kiểm tra ID đó có tồn tại trong database: token thật chỉ phát cho
tài khoản đã có và `users` không bị xóa (FK `RESTRICT`), nên chỉ JWT tự ký với ID không tồn tại
mới gặp lỗi; khi đó thêm giỏ/đặt đơn vi phạm FK và trả `409 CONFLICT`.
POST `/api/cart/items` với `{"foodId":1,"quantity":2}`, sau đó POST `/api/orders`:

```json
{ "orderType": "DINE_IN", "tableNumber": "B05", "paymentMethod": "CASH", "note": "Ít cay" }
{ "orderType": "DELIVERY", "recipientName": "An", "shippingPhone": "0900000004",
  "shippingAddress": "12 Nguyễn Văn Cừ", "paymentMethod": "COD" }
```

- DINE_IN dùng CASH/BANK_TRANSFER, DELIVERY dùng COD/BANK_TRANSFER; client không gửi món, giá hay userId.
- Enum phải gửi bằng tên (`DINE_IN`, `CASH`...); số hoặc chuỗi số trả 400.
- `GET /api/orders`: `page` mặc định 0, `size` mặc định 20 (1–100), sắp xếp mới nhất trước.
  Khách thấy mọi đơn của mình; nhân viên mặc định thấy PENDING/CONFIRMED/PROCESSING/SHIPPING.
- Sau checkout giỏ vẫn còn nhưng rỗng. Chưa có idempotency key: gửi lại POST trên giỏ đã dọn trả `EMPTY_CART`.

Request/response đầy đủ xem Swagger UI; luồng checkout và trạng thái xem
[docs/architecture-single-restaurant.md](docs/architecture-single-restaurant.md).
