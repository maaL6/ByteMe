# ByteMe

Backend cho một nhà hàng, hỗ trợ đặt món tại chỗ (`DINE_IN`) và giao hàng
(`DELIVERY`). Hiện có schema MySQL và dữ liệu mẫu cho UC01–UC18; API nghiệp vụ,
đăng nhập và JWT chưa được triển khai.

- [Use case](docs/Project%20KTPM.md)
- [Database và ERD](docs/database-design.md)
- [Truy vấn mẫu](docs/database-queries.sql)
- [Kiến trúc và sequence](docs/architecture-single-restaurant.md)
- [Đề KTPM 2026](https://github.com/maytinhdibo/KTPM-architecture-solution/blob/main/2026.md)

## Công nghệ

Java 25, Spring Boot 3.5.16, MySQL 8.4, Spring Data JPA, Spring Security,
Springdoc OpenAPI, JUnit 5 và Testcontainers. Maven 3.9.11 qua Maven Wrapper.

## Chạy dự án

Cần JDK 25 và Docker đang chạy Linux containers. Trong PowerShell:

```powershell
Copy-Item .env.example .env # chỉ khi chưa có .env
docker compose up --build -d
```

Compose dùng cấu hình trong `.env`; các giá trị mẫu chỉ dành cho development.
MySQL lưu dữ liệu trong volume, backend chờ database sẵn sàng. `docker compose down`
giữ dữ liệu; thay mật khẩu trong `.env` không đổi mật khẩu của database đã tạo.

MySQL tự chạy `db/schema.sql` khi tạo volume mới. Nạp mẫu một lần trên database
rỗng bằng lệnh sau; backend không tự nạp seed khi khởi động:

```powershell
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" < /opt/byteme/seed.sql'
```

Mật khẩu tài khoản mẫu: `ByteMeDemo!2026`, lưu BCrypt; email nằm trong `db/seed.sql`.

Chạy Java local với MySQL trong Docker:

```powershell
docker compose up -d mysql
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
```

Profile `dev` dùng `localhost:3306`, database/user `starter` và mật khẩu
`local-development-only`. Java local không tự đọc `.env`; nếu thay cấu hình,
đặt `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` trong môi trường chạy Java.

## Kiểm thử

```powershell
.\mvnw.cmd verify
```

Test chạy trên MySQL riêng qua Testcontainers, kiểm tra schema, dữ liệu mẫu,
snapshot, tổng tiền và ràng buộc database. Docker build bỏ qua test; chạy lệnh
trên để kiểm chứng. JAR nằm ở `target/application.jar`.

## Endpoint kỹ thuật

- Health: http://localhost:8080/actuator/health
- Swagger: http://localhost:8080/swagger-ui.html
- OpenAPI: http://localhost:8080/v3/api-docs

Security hiện chỉ mở các endpoint này. Swagger chưa có API nghiệp vụ.

## Database

Schema có 7 bảng nghiệp vụ và view `order_totals`. Hibernate dùng `ddl-auto=none`;
timestamp lưu UTC. Hai script nằm trong `src/main/resources/db/`:

- `schema.sql`: tạo bảng còn thiếu và view; không sửa cấu trúc bảng đã tồn tại.
- `seed.sql`: dữ liệu mẫu tùy chọn, nạp một lần trên database rỗng.

Docker chỉ chạy script khởi tạo khi tạo volume mới. Với database có sẵn hoặc chạy
Java local ngoài Compose, dùng MySQL client để nạp schema nếu chưa có bảng.
Thay đổi schema sau này dùng SQL thủ công; sao lưu trước khi sửa dữ liệu.
