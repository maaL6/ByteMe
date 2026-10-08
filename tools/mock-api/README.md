# Mock API

Đây là mock học tập gồm hai phần mã: xác thực request trong `jwt_auth.py` và route nghiệp vụ Food/Cart/Order trong `app.py`. Cả hai được nạp trong **cùng một server FastAPI** tại `127.0.0.1:8001`; không có Auth server hoặc endpoint đăng ký/đăng nhập riêng trong mock hiện tại.

Mock gồm 16 operation UC03–18, đường dẫn `/api/...`. Dữ liệu Food/Cart/Order lưu trong bộ nhớ và trở về ban đầu khi server khởi động lại. Chỉ chạy một worker.

## Cài đặt

Cần Python 3.13 trở lên. Chạy từ thư mục gốc repo:

```sh
python3 -m venv tools/mock-api/.venv
tools/mock-api/.venv/bin/python -m pip install -r tools/mock-api/requirements.txt
```

## Phần xác thực: chọn một chế độ

### Fixture token để thử độc lập

Chế độ này không cần issuer JWT hay public key. Mở terminal tại thư mục gốc và chạy:

```sh
MOCK_AUTH_MODE=fixture tools/mock-api/.venv/bin/python -m uvicorn app:app \
  --app-dir tools/mock-api --host 127.0.0.1 --port 8001
```

Swagger: <http://127.0.0.1:8001/docs>. Bấm **Authorize** và dùng một token mẫu: `mock-customer-a`, `mock-customer-b`, `mock-staff` (EMPLOYEE), hoặc `mock-admin`. Đây là token giả cố định dành cho mock; không có đăng ký/login, chữ ký, expiry hay password.

### Kiểm tra JWT do một issuer bên ngoài tạo

Đặt `MOCK_JWT_PUBLIC_KEY_FILE` trỏ tới public key tương ứng với private key đã ký token. JWT phải dùng RS256, issuer `sa-local-dev`, audience `sa-mock-api`, và có `sub`, `role`, `iat`, `exp`, `iss`, `aud`:

```sh
MOCK_AUTH_MODE=jwt \
MOCK_JWT_PUBLIC_KEY_FILE=/duong-dan/public.pem \
tools/mock-api/.venv/bin/python -m uvicorn app:app \
  --app-dir tools/mock-api --host 127.0.0.1 --port 8001
```

Trong Swagger, bấm **Authorize** và dán JWT. Chế độ này chỉ xác minh JWT; nó không cung cấp endpoint để phát token. Để đổi chế độ, dừng server bằng `Ctrl+C` rồi chạy lại với chế độ còn lại.

## Phần API Food / Cart / Order

Các route nghiệp vụ chạy cùng server với lớp xác thực ở cả hai chế độ trên:

- `GET /api/foods`: public; danh sách có `food-1`, `food-2`.
- CUSTOMER: `GET /api/cart`; thêm món bằng `POST /api/cart/items` với `{"foodId":"food-2","quantity":2}`; tạo đơn bằng `POST /api/orders` với `{"deliveryAddress":"Địa chỉ tổng hợp","phone":"+84900000001","paymentMethod":"COD"}`.
- EMPLOYEE: `GET /api/orders?status=PENDING`, xác nhận đơn qua `POST /api/orders/{orderId}/confirm`.
- ADMIN: thêm/sửa/ngừng bán món qua `/api/foods`.

OpenAPI được tạo động ở `/openapi.json`. Response mock có header `X-Mock-API: true`. Quyền và ownership được minh họa; schema nghiệp vụ chưa phải hợp đồng Approved. Không có database, payment hoặc độ bền dữ liệu. `.venv/` và `__pycache__/` không đưa lên repo.
