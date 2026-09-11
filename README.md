# Java Spring Boot Starter Template

Starter thuần kỹ thuật, không có business API, entity, migration nghiệp vụ hoặc dữ liệu mẫu.

## Tech stack

- Java 25, Maven 3.9.11 qua Maven Wrapper.
- Spring Boot 3.5.16: Web, Validation, Data JPA, Security, Actuator.
- PostgreSQL 17, Flyway, Springdoc OpenAPI/Swagger UI 2.8.17.
- JJWT 0.13.0 (chỉ dependency), Lombok.
- Spring Boot Test, JUnit 5, Mockito, Testcontainers PostgreSQL.

Boot 3.5 được chọn để dùng JUnit 5 theo BOM và hỗ trợ Java 25:
[Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
[Springdoc compatibility](https://springdoc.org/v2/) xác nhận Springdoc 2.8.x phù hợp Boot 3.5.

## Prerequisites

JDK 25, JAVA_HOME trỏ tới JDK, Java trong PATH; Docker Engine hoặc Docker Desktop
đang chạy Linux containers và Docker Compose v2. Lần đầu cần Internet để tải dependencies/images.
Không cần cài Maven riêng.

Các lệnh dùng POSIX shell. Trên Windows PowerShell thay ./mvnw bằng .\mvnw.cmd.
Nếu bản sao thư mục không giữ executable bit, chạy chmod +x mvnw trên Linux/macOS.

## Chạy Docker Compose

```sh
cp .env.example .env
docker compose config --quiet
docker compose up --build
```

PowerShell dùng Copy-Item .env.example .env. Compose cũng chạy được khi chưa có .env;
các mặc định chỉ phục vụ development. PostgreSQL có persistent volume và healthcheck;
backend đợi database healthy. Port chỉ bind localhost.
Docker build bỏ qua thực thi test vì Testcontainers cần Docker host; chạy verify riêng.

Dừng bằng docker compose down; volume được giữ lại. Sửa credentials trong .env không
thay credentials của database đã khởi tạo trong volume hiện có.

## Chạy Java local

```sh
docker compose up -d postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Profile dev dùng localhost:5432, database/user starter, password local-development-only.
Nếu sửa .env, export DB_NAME, DB_PORT, DB_USERNAME, DB_PASSWORD tương ứng vào shell chạy Java.
Spring Boot/Maven không tự đọc .env; Compose đọc file này để nội suy cấu hình.
Ví dụ PowerShell: `$env:DB_USERNAME = "starter"`; POSIX: `export DB_USERNAME=starter`.

## Build và test

```sh
./mvnw clean verify
```

Test contextLoads khởi động PostgreSQL 17 qua Testcontainers; Docker phải đang chạy.
Test tự cấp connection properties, không dùng database development, không bỏ qua khi thiếu Docker.
JAR: target/application.jar.

Chỉ đóng gói khi không có Docker (không xác nhận test thành công):

```sh
./mvnw -DskipTests package
java -jar target/application.jar --spring.profiles.active=dev
```

## Endpoint kỹ thuật

- Swagger UI: http://localhost:8080/swagger-ui.html (redirect /swagger-ui/index.html).
- OpenAPI JSON: http://localhost:8080/v3/api-docs.
- Actuator health: http://localhost:8080/actuator/health.

```sh
curl -f http://localhost:8080/actuator/health
curl -f http://localhost:8080/swagger-ui/index.html
curl -f http://localhost:8080/v3/api-docs
```

Health trả status UP khi database hoạt động. Swagger không có business operation;
thông báo "No operations defined in spec!" là bình thường.
Security chỉ mở các endpoint kỹ thuật trên, từ chối request khác và giữ CSRF mặc định.
Không có login, tài khoản mặc định hoặc JWT implementation. Actuator chỉ expose health.

## Environment variables

| Biến | Ý nghĩa | Mặc định development |
| --- | --- | --- |
| DB_URL | JDBC URL JVM; Compose đặt hostname postgres | jdbc:postgresql://localhost:5432/starter |
| DB_NAME | Database khi Compose khởi tạo PostgreSQL | starter |
| DB_USERNAME | Database username | starter |
| DB_PASSWORD | Database password | local-development-only |
| DB_PORT | PostgreSQL port host | 5432 |
| SERVER_PORT | Port JVM local hoặc port backend host Compose | 8080 |
| SPRING_PROFILES_ACTIVE | Profile Spring | Compose: dev; JVM: không mặc định |

Ngoài dev, DB_URL, DB_USERNAME, DB_PASSWORD bắt buộc. Backend trong Compose luôn nghe
port 8080 trong container. Không commit .env thật; .env.example chỉ chứa giá trị mẫu.
Hibernate ddl-auto=none không tạo schema. Flyway bật với migration trống;
Flyway có thể tạo bảng lịch sử kỹ thuật flyway_schema_history. Không có migration SQL.

## Project structure

```text
.mvn/wrapper/
  maven-wrapper.jar
  maven-wrapper.properties
src/main/java/org/example/
  Application.java
  config/SecurityConfiguration.java
src/main/resources/
  application.yml
  application-dev.yml
  application-test.yml
  db/migration/.gitkeep
src/test/java/org/example/
  ApplicationTests.java
.dockerignore
.env.example
.gitattributes
.gitignore
Dockerfile
compose.yaml
mvnw
mvnw.cmd
pom.xml
README.md
```
