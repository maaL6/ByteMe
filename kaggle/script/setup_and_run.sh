#!/usr/bin/env bash
set -e

echo "=== [1/5] Cài đặt OpenJDK 21, MySQL Server, OpenSSL & k6 ==="
apt-get update -qq
apt-get install -y -qq openjdk-21-jdk mysql-server openssl curl gnupg

# Cài đặt k6 nếu chưa có
if ! command -v k6 &> /dev/null; then
    echo "Đang cài đặt k6..."
    gpg -k 2>/dev/null || true
    gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69 2>/dev/null || true
    echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | tee /etc/apt/sources.list.d/k6.list
    apt-get update -qq && apt-get install -y -qq k6
fi

echo "=== [2/5] Khởi động MySQL và Khởi tạo Database ==="
service mysql start

# Xác định thư mục chứa Dataset (application.jar, schema.sql, seed.sql)
if [ -n "$1" ]; then
    DATASET_DIR="$1"
elif [ -n "$DATASET_DIR" ]; then
    DATASET_DIR="$DATASET_DIR"
else
    FOUND_JAR=$(find /kaggle/input . -name "application.jar" 2>/dev/null | head -n 1)
    if [ -n "$FOUND_JAR" ]; then
        DATASET_DIR="$(dirname "$FOUND_JAR")"
    else
        DATASET_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    fi
fi
echo "-> Thư mục Dataset: $DATASET_DIR"

WORK_DIR="/kaggle/working"
mkdir -p "$WORK_DIR/keys"

# Tạo DB và User
mysql -u root -e "
CREATE DATABASE IF NOT EXISTS starter CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'starter'@'localhost' IDENTIFIED BY 'local-development-only';
GRANT ALL PRIVILEGES ON starter.* TO 'starter'@'localhost';
FLUSH PRIVILEGES;
"

# Nạp Schema và Seed
mysql -u starter -plocal-development-only starter < "$DATASET_DIR/schema.sql"
mysql -u starter -plocal-development-only starter < "$DATASET_DIR/seed.sql"
echo "-> MySQL database và seed data đã sẵn sàng."

echo "=== [3/5] Khởi tạo RSA Keys cho JWT ==="
if [ ! -f "$WORK_DIR/keys/private.pem" ]; then
    openssl genpkey -algorithm RSA -out "$WORK_DIR/keys/private.pem" -pkeyopt rsa_keygen_bits:2048
    openssl rsa -pubout -in "$WORK_DIR/keys/private.pem" -out "$WORK_DIR/keys/public.pem"
    chmod 600 "$WORK_DIR/keys/private.pem"
fi
echo "-> Cặp khóa RSA đã được tạo tại $WORK_DIR/keys/."

echo "=== [4/5] Khởi động Spring Boot Backend ngầm ==="
export DB_URL="jdbc:mysql://localhost:3306/starter?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export DB_USERNAME="starter"
export DB_PASSWORD="local-development-only"
export SERVER_PORT="8080"
export AUTH_JWT_PRIVATE_KEY_FILE="$WORK_DIR/keys/private.pem"
export AUTH_JWT_PUBLIC_KEY_FILE="$WORK_DIR/keys/public.pem"
# Tăng rate limiter lên cao để phục vụ load test không bị chặn 429
export AUTH_LOGIN_EMAIL_LIMIT="1000000"
export AUTH_LOGIN_IP_LIMIT="1000000"
export AUTH_LOGIN_WINDOW_SECONDS="900"
# Tối ưu hóa HikariCP & Tomcat Thread pool cho tải lớn
export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE="50"
export SERVER_TOMCAT_THREADS_MAX="200"

nohup java -Xms2g -Xmx6g -jar "$DATASET_DIR/application.jar" > "$WORK_DIR/server.log" 2>&1 &
SERVER_PID=$!
echo "-> Server PID: $SERVER_PID. Đang chờ server khởi động..."

echo "=== [5/5] Kiểm tra Healthcheck ==="
SUCCESS=0
for i in {1..30}; do
    if curl -s -f http://localhost:8080/actuator/health > /dev/null; then
        echo ">>> SERVER IS HEALTHY & READY TO ACCEPT TRAFFIC! <<<"
        SUCCESS=1
        break
    fi
    sleep 2
    echo "Đang chờ server ($i/30)..."
done

if [ $SUCCESS -ne 1 ]; then
    echo "!!! Khởi động thất bại. Kiểm tra log gần nhất:"
    tail -n 50 "$WORK_DIR/server.log"
    exit 1
fi
