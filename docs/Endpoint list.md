

| UC | Chức năng | Method | Endpoint | Actor |
| :---- | :---- | :---- | :---- | :---- |
| UC01 | Đăng ký tài khoản | POST | /api/auth/register | Khách hàng |
| UC02 | Đăng nhập | POST | /api/auth/token | Khách hàng / Nhân viên |
| UC03 | Xem danh sách món ăn | GET | /api/foods | Khách hàng |
| UC04 | Xem chi tiết món ăn | GET | /api/foods/{foodId} | Khách hàng |
| UC05 | Tìm kiếm món ăn | GET | /api/foods/search?q={keyword} | Khách hàng |
| UC06 | Thêm món ăn | POST | /api/foods | Admin |
| UC07 | Cập nhật món ăn | PUT | /api/foods/{foodId} | Admin |
| UC08 | Xóa món ăn | DELETE | /api/foods/{foodId} | Admin |
| UC09 | Xem giỏ hàng | GET | /api/cart | Khách hàng |
| UC10 | Thêm món vào giỏ hàng | POST | /api/cart/items | Khách hàng |
| UC11 | Cập nhật số lượng món | PUT | /api/cart/items/{cartItemId} | Khách hàng |
| UC12 | Xóa món khỏi giỏ hàng | DELETE | /api/cart/items/{cartItemId} | Khách hàng |
| UC13 | Tạo đơn hàng | POST | /api/orders | Khách hàng |
| UC14 | Xem danh sách đơn hàng | GET | /api/orders | Khách hàng |
| UC14 | Xem chi tiết đơn hàng | GET | /api/orders/{orderId} | Khách hàng |
| UC15 | Hủy đơn hàng | DELETE | /api/orders/{orderId} | Khách hàng |
| UC16 | Xác nhận đơn hàng | POST | /api/orders/{orderId}/confirm | Nhân viên |
| UC17 | Cập nhật trạng thái đơn | PUT | /api/orders/{orderId}/status | Nhân viên |
| UC18 | Xem đơn cần xử lý | GET | /api/orders?status=PENDING | Nhân viên |

