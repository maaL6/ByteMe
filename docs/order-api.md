# Order API — phase 1

Module Order dùng cùng kiến trúc với Food/Cart: `OrderController` → `OrderService` →
`OrderRepository` (Spring Data JPA) trên schema MySQL hiện có. `OrderService` không import
framework web hay thư viện DB; Order gọi Cart và Food qua `CartService`/`FoodService`.

## Endpoint

Auth luôn bật; dùng JWT từ đăng nhập thật để subject khớp ID tài khoản trong database.
Subject không phải ID số (ví dụ token fixture `customer-a`, `staff-a` của
`scripts/issue-local-tokens.sh`) nhận 403 trên mọi API Cart/Order, kể cả với EMPLOYEE.
Swagger tại `/swagger-ui/index.html` có bearerAuth.

| Method | Endpoint | Quyền | Kết quả |
| --- | --- | --- | --- |
| POST | `/api/orders` | CUSTOMER | 201 + đơn mới từ giỏ hàng hiện tại |
| GET | `/api/orders` | CUSTOMER / EMPLOYEE | Danh sách có phân trang |
| GET | `/api/orders/{orderId}` | CUSTOMER / EMPLOYEE | Đơn và snapshot món |
| DELETE | `/api/orders/{orderId}` | CUSTOMER | Hủy đơn PENDING của mình, trả 200; không xóa dữ liệu |
| POST | `/api/orders/{orderId}/confirm` | EMPLOYEE | Xác nhận PENDING, trả 200 |
| PUT | `/api/orders/{orderId}/status` | EMPLOYEE | Chuyển trạng thái/hủy, trả 200 |

ADMIN chưa có quyền xử lý Order theo UC13–18. ID/role lấy từ JWT.
Khách chỉ xem/hủy đơn của mình; truy cập đơn khách khác trả 403.

Danh sách nhận `status`, `orderType`, `page` (mặc định 0), `size` (mặc định 20,
từ 1 đến 100). Sắp xếp `created_at DESC, id DESC`. Response có `content`, `page`,
`size`, `totalElements`; mỗi đơn có `items`, `totalAmount`.
Nhân viên không truyền status sẽ nhận PENDING/CONFIRMED/PROCESSING/SHIPPING.
Khách không truyền status sẽ nhận mọi trạng thái của đơn mình.

## Request

Tạo đơn tại chỗ:

```json
{
  "orderType": "DINE_IN",
  "tableNumber": "B05",
  "paymentMethod": "CASH",
  "note": "Ít cay"
}
```

Tạo đơn giao hàng:

```json
{
  "orderType": "DELIVERY",
  "recipientName": "Nguyễn Văn An",
  "shippingPhone": "0900000004",
  "shippingAddress": "12 Nguyễn Văn Cừ",
  "paymentMethod": "COD"
}
```

DINE_IN dùng CASH/BANK_TRANSFER; DELIVERY dùng COD/BANK_TRANSFER.
Thông tin phục vụ phải đúng loại đơn; client không truyền items, giá hoặc userId.

Cập nhật trạng thái:

```json
{ "status": "PROCESSING" }
```

Luồng chung: PENDING → CONFIRMED → PROCESSING.
DINE_IN đi tiếp COMPLETED; DELIVERY đi SHIPPING → COMPLETED.
Nhân viên hủy từ PENDING/CONFIRMED/PROCESSING; khách chỉ hủy PENDING.
Chuyển trạng thái sai hoặc hủy lại trả 409. Xác nhận lưu confirmed_by/confirmed_at;
hủy và hoàn thành lưu timestamp tương ứng. Hủy sau xác nhận giữ thông tin xác nhận.
Enum phải dùng tên như `CONFIRMED`, `DINE_IN`, `CASH`; số hoặc chuỗi số trả 400.

## Cart và checkout

`OrderService.create` chạy trong một transaction và gọi các service khác (tham gia
cùng transaction):

1. `CartService.lockItemsForCheckout(userId)` khóa hàng carts, trả foodId → số lượng
   theo foodId tăng dần.
2. `FoodService.reserveStock(foodId, quantity)` khóa từng món, kiểm tra còn bán/đủ kho,
   trừ kho và trả tên/giá để lưu snapshot.
3. Lưu đơn PENDING và chi tiết qua `OrderRepository`.
4. `CartService.clearCart(userId)` xóa các item và tăng version giỏ.

Cart CRUD khóa cùng hàng carts trước khi thêm/sửa/xóa. Lần đầu thêm món
dùng INSERT ... ON DUPLICATE KEY UPDATE để tạo giỏ an toàn khi có request đồng thời.
Order lấy giá/trạng thái/tồn kho từ Food, lưu snapshot, trừ kho và dọn giỏ cùng commit;
lỗi ở bất kỳ bước nào sẽ rollback toàn bộ. Giữ hàng carts để khách tiếp tục thêm món.

Các API `/api/cart` và `/api/cart/items/**` yêu cầu Bearer JWT và role
CUSTOMER. ID khách lấy từ JWT; header `X-User-Id` không còn được sử dụng. GET giỏ chưa
tồn tại trả 404; sau checkout trả giỏ có items rỗng. Item không nằm trong giỏ hiện tại
trả 404, không thể sửa/xóa item của khách khác.

Luồng thử: đăng nhập bằng `/api/auth/token`, POST `/api/cart/items` với
`{"foodId":1,"quantity":2}`, rồi POST `/api/orders` theo một trong hai mẫu phía trên.
Dùng cùng Bearer JWT cho cả Cart và Order. Cart không giữ kho; Order kiểm tra lại khi đặt.

Dọn giỏ luôn thực hiện đồng bộ trước commit, không dùng event.
Hủy đơn hoàn kho qua `FoodService.releaseStock` theo state machine, không tự khôi phục Cart. Chưa có idempotency key:
hai checkout liên tiếp trên giỏ đã dọn trả EMPTY_CART; không tự retry POST tạo đơn.

## Lỗi và kiểm tra

Lỗi JSON có `status`, `code`, `message`, `path`, `fieldErrors`.
400: dữ liệu sai/giỏ rỗng; 401: thiếu JWT hợp lệ; 403: thiếu quyền;
404: không có đơn/giỏ/món; 409: sai trạng thái/hết hàng/xung đột khóa;
500: lỗi hệ thống.

Khóa đơn khi chuyển trạng thái và khóa món theo foodId tăng dần. Hủy đơn và
hoàn kho dùng một giao dịch, tránh hoàn kho hai lần. Tiền dùng BigDecimal và
tổng được tính từ snapshot order_items. DATETIME trong database được đọc/ghi theo UTC.

Các test Order gồm service, HTTP/phân quyền và MySQL/Testcontainers.
Test tích hợp dùng CartService/FoodService/OrderService thật trên MySQL để kiểm tra
checkout, rollback chung transaction và concurrency. Spy chỉ gây lỗi có chủ đích
hoặc giữ giao dịch tại một điểm để kiểm tra tranh chấp khóa.

```powershell
.\mvnw.cmd '-Dtest=OrderServiceTest,OrderWebTest,OrderIntegrationTest,ArchitectureTest' test
```

Test tích hợp cần Docker đang chạy. JAVA_HOME trỏ tới JDK 21 trở lên.
