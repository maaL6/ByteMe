# Order API — phase 1

Module Order có một Controller, một Service Java thuần và các Repository adapter
dùng JDBC với schema MySQL hiện có. Spring chỉ nằm ở web, persistence và integration.
Không thêm bảng hoặc thư viện. Cart CRUD dùng JPA; adapter checkout dùng JDBC chung giao dịch với Order.

## Endpoint

Auth luôn bật; dùng JWT từ đăng nhập thật để subject khớp ID tài khoản trong database.
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

`CartCheckout` là contract giữa Order và Cart. Bean `MySqlCartCheckout` dùng JDBC
và bắt buộc tham gia transaction của Order:

1. `lockByUserId(userId)` khóa hàng carts, rồi đọc cartId và foodId/quantity.
2. `clearItems(cartId)` xóa các item và tăng version giỏ trong cùng transaction.

Cart CRUD dùng JPA, khóa cùng hàng carts trước khi thêm/sửa/xóa. Lần đầu thêm món
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

`OrderCreated(orderId, userId, cartId)` vẫn được phát trong transaction cho các consumer
sau này. Không có listener dọn giỏ; dọn giỏ luôn thực hiện đồng bộ trước commit.
Hủy đơn hoàn kho theo state machine, không tự khôi phục Cart. Chưa có idempotency key:
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
Test tích hợp dùng adapter Cart thật, CartService JPA và Order JDBC để kiểm tra
checkout, rollback chung transaction và concurrency. Spy chỉ gây lỗi có chủ đích
hoặc giữ giao dịch tại một điểm để kiểm tra tranh chấp khóa.

```powershell
.\mvnw.cmd '-Dtest=OrderServiceTest,OrderWebTest,OrderIntegrationTest,ArchitectureTest' test
```

Test tích hợp cần Docker đang chạy. JAVA_HOME trỏ tới JDK 25 cài trên máy.
