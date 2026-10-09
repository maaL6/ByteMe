# ByteMe

Java 25, Spring Boot 3.5.16, MySQL 8.4, Spring Data JPA, Spring Security,
Springdoc OpenAPI, JUnit 5 và Testcontainers. Maven 3.9.11 qua Maven Wrapper.



## Auth mặc định — UC-01/02

Auth và xác thực JWT luôn bật; không có profile `dev` hoặc chế độ chạy tắt Auth. Health, Swagger, xem thực đơn, đăng ký và đăng nhập công khai. Các API còn lại cần JWT và được phân quyền theo CUSTOMER/EMPLOYEE/ADMIN. JWT TTL 5400 giây; logout/revocation/rotation chưa triển khai. Quota login in-memory 10/email và 100/IP trong 900 giây.

Cần Java 21 trở lên, Docker và Python 3 với cryptography (baseline 50.0.2) để chạy đầy đủ ứng dụng và test:

```sh
python3 -m venv .venv
.venv/bin/pip install cryptography==50.0.2
.venv/bin/python scripts/generate-keys.py
DB_URL=jdbc:mysql://localhost:3306/starter DB_USERNAME=starter DB_PASSWORD=local-development-only ./mvnw spring-boot:run
```

Lệnh ví dụ dùng database/user `starter` local. Cấu hình kết nối luôn đến từ `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`; với môi trường ngoài local, truyền credentials riêng qua biến môi trường. `POST /api/auth/register` nhận fullName/email/phone/password/confirmPassword, trả 201 CUSTOMER/ACTIVE. `POST /api/auth/token` nhận email/password, trả accessToken/tokenType/expiresIn/user. JSON strict và lỗi Auth chỉ áp dụng AuthController. OpenAPI Auth tại `/openapi/openapi.yaml`; Swagger tổng vẫn dùng `/v3/api-docs`.

```sh
./mvnw test
bash scripts/issue-local-tokens.sh
```

Dev CLI tạo 4 JWT fixture local; subject fixture không phải ID users MySQL và không thay đăng nhập thật. Khóa/token nằm trong `.local` bị gitignore; không in token vào log. Script hỗ trợ macOS/Linux (file permissions POSIX), JDK 25 trên macOS hoặc SA_JAVA_HOME/JAVA_HOME, và Python qua AUTH_DEV_PYTHON hoặc .venv.

Docker dùng bản sao khóa riêng cho runtime UID 10001. Chuẩn bị bản sao cho Docker một lần:

```sh
.venv/bin/python scripts/prepare-docker-keys.py
docker compose up --build
```

Trên Linux, cấp quyền đọc cho UID 10001 qua ownership/ACL của `.local/docker-keys`; không đổi private key sang world-readable. Trên Docker Desktop, mount read-only hoạt động với quyền file hiện tại. Compose giữ volume MySQL. Chưa có signing/rotation production hoặc NFR Kaggle; khóa/issuer/audience hiện dùng baseline local.

CLI thử nghiệm `LocalTokenCli` nằm trong `src/test/java/org/example/dev/`, script dùng test-compile và target/test-classes; CLI không được đóng gói trong application.jar. Application.main là entry point chạy backend, giữ trong src/main/java.

## Cart → Order

Dùng JWT đăng nhập thật cho cả Cart và Order; không dùng `X-User-Id`.
POST `/api/cart/items` với `{"foodId":1,"quantity":2}`, sau đó POST `/api/orders`
với thông tin bàn hoặc giao hàng. Order lấy món/số lượng từ giỏ, chốt giá server,
lưu đơn PENDING, trừ kho và dọn giỏ trong một transaction. Lỗi sẽ rollback toàn bộ.
Xem request mẫu và quy tắc tại [Order API](docs/order-api.md).
