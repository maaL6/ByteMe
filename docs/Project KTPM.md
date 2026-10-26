# ByteMe — Use case cho một nhà hàng

Ứng dụng phục vụ **một nhà hàng duy nhất**. Khách hàng dùng cùng tài khoản, thực đơn và giỏ hàng để đặt món ăn tại chỗ (`DINE_IN`) hoặc giao về nhà (`DELIVERY`). Không có lựa chọn nhà hàng; dữ liệu món không có `restaurantId` và database không cần bảng `restaurants`.

Tài liệu này giữ UC01–UC18 và ba vai trò: `CUSTOMER` đăng ký, xem thực đơn, quản lý giỏ và đơn của mình; `EMPLOYEE` xác nhận, xử lý đơn; `ADMIN` quản lý món ăn. Cả hai cách đặt món đều yêu cầu khách hàng đăng nhập. Chức năng đặt bàn, khách vãng lai và tích hợp cổng thanh toán chưa nằm trong phạm vi các use case này.

Quy tắc đơn hàng dùng chung:

- API dùng `orderType`, database dùng `order_type`, với hai giá trị `DINE_IN` và `DELIVERY`.
- Giới hạn độ dài (`@Size`) được kiểm tra trên giá trị gốc, **trước** khi bỏ khoảng trắng: `tableNumber` ≤ 20, `recipientName` ≤ 150, `shippingPhone` ≤ 20, `shippingAddress` ≤ 500, `note` ≤ 500 ký tự. Sau đó service trim các trường bắt buộc và từ chối giá trị rỗng.
- `DINE_IN` bắt buộc `tableNumber` không rỗng; `recipientName`, `shippingPhone`, `shippingAddress` phải là `null` hoặc không gửi. `tableNumber` là nhãn bàn được lưu tại thời điểm đặt; giai đoạn này chưa quản lý danh mục hay tình trạng bàn.
- `DELIVERY` bắt buộc `recipientName`, `shippingPhone`, `shippingAddress` không rỗng (không kiểm tra định dạng số điện thoại); `tableNumber` phải là `null` hoặc không gửi. Thông tin nhận hàng được lưu tại thời điểm đặt để giữ lịch sử.
- `paymentMethod` gồm `CASH` (chỉ `DINE_IN`), `COD` (chỉ `DELIVERY`) và `BANK_TRANSFER` (cả hai). Trường này ghi nhận cách thanh toán, chưa xác nhận thanh toán qua một cổng bên ngoài.
- Cả hai loại đơn đi qua `PENDING → CONFIRMED → PROCESSING`. Đơn giao hàng tiếp tục `SHIPPING → COMPLETED`; đơn tại chỗ đi trực tiếp từ `PROCESSING → COMPLETED`. Đơn `DINE_IN` không được mang trạng thái `SHIPPING`.
- Khách hàng chỉ hủy đơn của mình ở `PENDING`. Quy tắc được chọn cho nhân viên là được hủy đơn ở `PENDING`, `CONFIRMED`, `PROCESSING`; không hủy đơn `SHIPPING`, `COMPLETED` hoặc `CANCELLED`. Hủy phải phục hồi tồn kho đã trừ đúng một lần, trong cùng giao dịch với cập nhật trạng thái.
- Checkout đọc giỏ đã lưu, kiểm tra tồn kho, tính giá bằng dữ liệu hiện tại từ server và lưu snapshot tên/giá món; client không quyết định giá hoặc tổng tiền. Lưu đơn, lưu chi tiết, trừ tồn kho và dọn giỏ là một giao dịch đồng bộ.
- Tổng tiền đơn được suy ra từ các snapshot trong `order_items` (tổng `unit_price * quantity`); không lưu một giá trị tổng riêng trên `orders`. `order_type` bắt buộc ghi rõ khi tạo đơn, không có giá trị mặc định ở schema hiện hành.
- **Phase 1 / phase 2:** phase 1 chỉ triển khai chức năng cơ bản, không khóa hàng hay khóa lạc quan. Các yêu cầu về tính đúng khi chạy **đồng thời** (không bán vượt kho khi nhiều checkout tranh chấp, một giỏ chỉ tạo một đơn, hủy đồng thời chỉ hoàn kho một lần, sửa tồn kho của Admin không ghi đè checkout) vẫn là quy tắc nghiệp vụ nhưng được đánh dấu **(phase 2)** bên dưới; khi chạy tuần tự, phase 1 vẫn tuân thủ đầy đủ. Ở phase 1, hai checkout đồng thời có thể cùng đọc một mức tồn kho và cùng ghi `stock - qty` (lost update) nên có thể bán vượt kho; `stock_quantity` không âm vì service chỉ ghi sau khi kiểm tra `stock >= qty`.
- Tầng nghiệp vụ không import framework web/thư viện DB (dùng Spring `@Service`/`@Transactional`, repository là Spring Data); lỗi nghiệp vụ của Food/Cart/Order được `GlobalErrors` chuyển thành HTTP status (Auth dùng `AuthErrors`). API chạy HTTP thường (cổng 8080), chưa cấu hình HTTPS.

Hợp đồng HTTP/JSON chi tiết theo mã nguồn: [openapi.yaml](../src/main/resources/static/openapi.yaml).
Role không kế thừa: ADMIN không dùng Cart/Order; EMPLOYEE xem được chi tiết mọi đơn,
nhưng dùng route cập nhật status để hủy, không dùng DELETE dành cho CUSTOMER.
ID trên path Food có tên `id`; ID món trong body thêm giỏ vẫn là `foodId`.
ID path/query không parse được thành int64 trả 400; ID đã parse nhưng không tồn tại,
kể cả ≤ 0, trả 404. Riêng ID trong body Food/Cart phải dương, sai trả 400.
Mọi giới hạn password còn chịu trần 72 byte UTF-8 và password không được trim.
Các thao tác ghi có thể trả 409 `CONFLICT` khi vi phạm ràng buộc DB hoặc
`CONCURRENT_UPDATE` khi deadlock/hết thời gian chờ khóa (phase 1 đã có ánh xạ lỗi này).

Sơ đồ kiến trúc và sequence: [architecture-single-restaurant.md](architecture-single-restaurant.md).

### **A. Quản lý tài khoản**

| ID | Use Case | Actor | Nghiệp vụ chính |
| ----- | ----- | ----- | ----- |
| UC01 | Đăng ký tài khoản | Khách hàng | Kiểm tra thông tin → tạo tài khoản |
| UC02 | Đăng nhập | Khách hàng/Nhân viên/Admin | Kiểm tra tài khoản → xác thực → cấp token |

### **B. Quản lý món ăn**

| ID | Use Case | Actor | Nghiệp vụ chính |
| ----- | ----- | ----- | ----- |
| UC03 | Xem danh sách món ăn | Khách hàng | Lấy danh sách món đang bán |
| UC04 | Xem chi tiết món ăn | Khách hàng | Lấy thông tin món |
| UC05 | Tìm kiếm món ăn | Khách hàng | Xử lý điều kiện tìm kiếm → truy vấn món |
| UC06 | Thêm món ăn | Admin | Kiểm tra dữ liệu → tạo món |
| UC07 | Cập nhật món ăn | Admin | Kiểm tra → cập nhật món |
| UC08 | Xóa món ăn | Admin | Kiểm tra món tồn tại → chuyển INACTIVE (ngừng bán) |

### **UC01 \- Đăng ký tài khoản**

| Requirement ID | UC01 |
| ----- | ----- |
| **Requirement Name** | Đăng ký tài khoản  |
| **Actor** | Khách hàng  |
| **Requirement Description** | Cho phép người dùng mới tạo tài khoản khách hàng trên hệ thống. Hệ thống kiểm tra tính hợp lệ của thông tin, kiểm tra trùng lặp tài khoản, mã hóa mật khẩu và tạo mới người dùng. |
| **Input** | fullName, email, phone, password, confirmPassword |
| **Preconditions** | 1\. Khách hàng chưa đăng nhập vào hệ thống. 2\. Thiết bị có kết nối mạng và truy cập được vào ứng dụng/API. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu đăng ký qua POST /api/auth/register. 2\. Hệ thống kiểm tra dữ liệu đầu vào: fullName không rỗng (≤ 150 ký tự), email đúng định dạng, phone là số nội địa 10 chữ số bắt đầu bằng 0 hoặc số quốc tế dạng +…, password và confirmPassword dài 8–72 ký tự và trùng khớp. 3\. Kiểm tra email/SĐT đã tồn tại hay chưa. 4\. Băm mật khẩu bằng BCrypt. 5\. Gán vai trò CUSTOMER và trạng thái ACTIVE. 6\. Lưu thông tin người dùng mới. 7\. Trả 201 Created với thông tin tài khoản (id, fullName, email, phone, role, status); không trả mật khẩu. |
| **Nonfunctional Requirement** | 1\. Mật khẩu phải được băm một chiều bằng BCrypt. 2\. Tuân thủ thiết kế REST API, trả về JSON. |
| **Error Handling** | 400 Bad Request (`VALIDATION_ERROR`): dữ liệu thiếu/sai định dạng; (`PASSWORD_MISMATCH`): password và confirmPassword không khớp. 409 Conflict (`ACCOUNT_CONFLICT`): email hoặc số điện thoại đã tồn tại. 500 Internal Server Error: lỗi hệ thống khi tạo tài khoản. |

### **API: POST /api/auth/register**

# UC02 \- Đăng nhập

| Requirement ID | UC02 |
| ----- | ----- |
| **Requirement Name** | Đăng nhập |
| **Actor** | Khách hàng/Nhân viên/Admin |
| **Requirement Description** | Cho phép người dùng có tài khoản hợp lệ đăng nhập vào hệ thống. Hệ thống xác thực thông tin, kiểm tra trạng thái tài khoản và cấp access token JWT kèm thông tin người dùng. |
| **Input** | email, password |
| **Preconditions** | 1\. Tài khoản đã tồn tại trên hệ thống. 2\. Tài khoản đang ở trạng thái hoạt động. 3\. Thiết bị có kết nối mạng và truy cập được vào ứng dụng/API. |
| **Functional Requirement** | 1\. Người dùng gửi yêu cầu đăng nhập qua POST /api/auth/token. 2\. Hệ thống kiểm tra dữ liệu đầu vào bắt buộc và định dạng email; áp dụng giới hạn số lần đăng nhập theo email và theo IP. 3\. Tìm tài khoản theo email thông qua Account Repository. 4\. So khớp mật khẩu với giá trị đã băm bằng BCrypt. 5\. Kiểm tra tài khoản ở trạng thái ACTIVE. 6\. Tạo access token JWT có thời hạn, chứa định danh và vai trò. 7\. Trả 200 với accessToken, tokenType (Bearer), expiresIn và thông tin cơ bản của người dùng; không trả về mật khẩu. |
| **Nonfunctional Requirement** | 1\. Endpoint trả về JSON. 2\. Không ghi mật khẩu hoặc token đầy đủ vào log. 3\. JWT được ký bằng khóa RSA cấu hình và có thời hạn sử dụng. 4\. Tầng nghiệp vụ không truy cập trực tiếp Database. |
| **Error Handling** | 400 Bad Request (`VALIDATION_ERROR`): thiếu email/password hoặc dữ liệu sai định dạng. 401 Unauthorized (`INVALID_CREDENTIALS`): email hoặc mật khẩu không chính xác. 403 Forbidden (`ACCOUNT_NOT_ACTIVE`): tài khoản không ở trạng thái hoạt động. 429 Too Many Requests (`LOGIN_RATE_LIMITED`, kèm Retry-After): vượt giới hạn số lần đăng nhập trong khoảng thời gian quy định. 500 Internal Server Error: lỗi hệ thống khi xác thực hoặc tạo token. |

### API: POST /api/auth/token

# UC03 \- Xem danh sách món ăn

| Requirement ID | UC03 |
| ----- | ----- |
| **Requirement Name** | Xem danh sách món ăn |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng xem danh sách các món ăn đang được bán của nhà hàng, có thể lọc theo danh mục. |
| **Input** | categoryId (tùy chọn) |
| **Preconditions** | 1\. Thiết bị có kết nối mạng và truy cập được vào ứng dụng/API. 2\. Khách hàng không bắt buộc đăng nhập. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu qua GET /api/foods. 2\. Nếu có categoryId, kiểm tra danh mục tồn tại. 3\. Truy vấn Food Repository để lấy các món có trạng thái ACTIVE, lọc theo categoryId nếu có, sắp xếp theo id. 4\. Chuyển dữ liệu sang DTO (id, categoryId, name, description, price, imageUrl, stockQuantity, status). 5\. Trả 200 với danh sách món ăn dạng JSON (không phân trang). |
| **Nonfunctional Requirement** | 1\. API tuân thủ REST và trả về JSON. 2\. Tầng nghiệp vụ không truy cập trực tiếp Database. |
| **Error Handling** | 400 Bad Request (`VALIDATION_ERROR`): categoryId không phải số. 404 Not Found (`CATEGORY_NOT_FOUND`): categoryId được chỉ định không tồn tại. 200 OK với danh sách rỗng: không có món phù hợp. 500 Internal Server Error: lỗi truy xuất dữ liệu. |

### API: GET /api/foods

# UC04 \- Xem chi tiết món ăn

| Requirement ID | UC04 |
| ----- | ----- |
| **Requirement Name** | Xem chi tiết món ăn |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng xem thông tin chi tiết của một món ăn đang được bán, bao gồm tên, mô tả, giá, hình ảnh, categoryId, tồn kho và trạng thái. |
| **Input** | id |
| **Preconditions** | 1\. Thiết bị có kết nối mạng và truy cập được vào ứng dụng/API. 2\. Khách hàng không bắt buộc đăng nhập. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu qua GET /api/foods/{id}. 2\. Hệ thống kiểm tra định dạng id. 3\. Truy vấn món ăn theo id thông qua Food Repository. 4\. Kiểm tra món ăn tồn tại và có trạng thái ACTIVE; món INACTIVE được xử lý như không tồn tại. 5\. Chuyển dữ liệu sang DTO (cùng các trường như UC03). 6\. Trả 200 với chi tiết món ăn dưới dạng JSON. |
| **Nonfunctional Requirement** | 1\. API tuân thủ REST và trả về JSON. 2\. Tầng nghiệp vụ không truy cập trực tiếp Database. |
| **Error Handling** | 400 Bad Request (`VALIDATION_ERROR`): id không đúng định dạng. 404 Not Found (`FOOD_NOT_FOUND`): món ăn không tồn tại hoặc đang INACTIVE. 500 Internal Server Error: lỗi truy xuất dữ liệu. |

### API: GET /api/foods/{id}

### UC05 \- Tìm kiếm món ăn

| Requirement ID | UC05 |
| ----- | ----- |
| **Requirement Name** | Tìm kiếm món ăn |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng tìm món ăn theo từ khóa, có thể lọc theo danh mục. Hệ thống chỉ trả về những món đang được bán. |
| **Input** | q (bắt buộc); categoryId (tùy chọn) |
| **Preconditions** | 1\. Thiết bị có kết nối mạng và truy cập được vào ứng dụng/API. 2\. Khách hàng không bắt buộc đăng nhập. 3\. Từ khóa q sau khi loại bỏ khoảng trắng phải có từ 1 đến 100 ký tự. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu qua GET /api/foods/search?q={keyword}. 2\. Hệ thống trim từ khóa, kiểm tra độ dài và kiểm tra categoryId tồn tại nếu có. 3\. Truy vấn Food Repository theo tên hoặc mô tả chứa từ khóa, không phân biệt hoa thường; ký tự `%`, `_` trong từ khóa được hiểu theo nghĩa đen. 4\. Chỉ lấy món có trạng thái ACTIVE, lọc theo categoryId nếu có, sắp xếp theo id. 5\. Trả 200 với danh sách món phù hợp dạng JSON (không phân trang). |
| **Nonfunctional Requirement** | 1\. API tuân thủ REST và trả về JSON. 2\. Truy vấn tìm kiếm phải dùng tham số hóa để tránh injection. 3\. (Phase 2) Các trường tìm kiếm/lọc cần có chỉ mục phù hợp với khối lượng dữ liệu; phase 1 chưa thêm index tối ưu. 4\. Tầng nghiệp vụ không truy cập trực tiếp Database. |
| **Error Handling** | 400 Bad Request (`VALIDATION_ERROR`): thiếu q, q rỗng/quá 100 ký tự sau khi trim, hoặc categoryId không phải số. 404 Not Found (`CATEGORY_NOT_FOUND`): categoryId được chỉ định không tồn tại. 200 OK với danh sách rỗng: không tìm thấy món phù hợp. 500 Internal Server Error: lỗi truy vấn dữ liệu. |

### API: GET /api/foods/search?q={keyword}

### UC06 \- Thêm món ăn

| Requirement ID | UC06 |
| ----- | ----- |
| **Requirement Name** | Thêm món ăn |
| **Actor** | Admin |
| **Requirement Description** | Cho phép Admin tạo một món ăn mới thuộc danh mục hợp lệ trong thực đơn của nhà hàng. Hệ thống kiểm tra dữ liệu, quyền truy cập và tính trùng lặp trước khi lưu. |
| **Input** | categoryId, name, price (bắt buộc); description, imageUrl, stockQuantity (mặc định 0), status (mặc định ACTIVE) |
| **Preconditions** | 1\. Admin đã đăng nhập và có quyền quản lý món ăn. 2\. Danh mục được tham chiếu đã tồn tại. 3\. Kết nối đến kho dữ liệu đang hoạt động. |
| **Functional Requirement** | 1\. Admin gửi yêu cầu qua POST /api/foods. 2\. Hệ thống xác thực JWT và kiểm tra quyền Admin. 3\. Kiểm tra dữ liệu: categoryId là số dương; name không rỗng, ≤ 150 ký tự; description ≤ 16000 ký tự; price \> 0, tối đa 13 chữ số phần nguyên và 2 chữ số thập phân; imageUrl ≤ 2048 ký tự; stockQuantity là số nguyên không âm; status là ACTIVE hoặc INACTIVE. Giới hạn độ dài được kiểm tra trước khi trim. 4\. Kiểm tra categoryId tồn tại. 5\. Trim tên và kiểm tra không trùng trong toàn bộ thực đơn (không phân biệt hoa thường). 6\. Tạo món ăn, dùng mặc định stockQuantity = 0 và status = ACTIVE nếu không gửi hoặc gửi null. 7\. Lưu món ăn thông qua Food Repository trong một giao dịch. 8\. Trả về món ăn vừa tạo dưới dạng JSON với mã 201 Created. |
| **Nonfunctional Requirement** | 1\. Endpoint yêu cầu xác thực và phân quyền Admin. 2\. API tuân thủ REST và trả về JSON. 3\. Thao tác tạo phải bảo đảm tính toàn vẹn và tính nguyên tử của dữ liệu. 4\. Tầng nghiệp vụ không truy cập trực tiếp Database. |
| **Error Handling** | 400 Bad Request (`VALIDATION_ERROR`): dữ liệu thiếu/sai định dạng, quá dài, price \<= 0, stockQuantity không phải số nguyên không âm hoặc status không hợp lệ. 401 Unauthorized: chưa đăng nhập hoặc token không hợp lệ/hết hạn. 403 Forbidden: người dùng không có quyền Admin. 404 Not Found (`CATEGORY_NOT_FOUND`): danh mục không tồn tại. 409 Conflict (`FOOD_NAME_CONFLICT`): tên món ăn bị trùng trong thực đơn. 500 Internal Server Error: lỗi hệ thống khi lưu món ăn. |

### API: POST /api/foods

### **C. Quản lý giỏ hàng**

| ID | Use Case | Actor | Nghiệp vụ chính |
| ----- | ----- | ----- | ----- |
| UC09 | Xem giỏ hàng | Khách hàng | Lấy giỏ hàng của user |
| UC10 | Thêm món vào giỏ hàng | Khách hàng | Kiểm tra món → thêm/cập nhật item |
| UC11 | Cập nhật số lượng món | Khách hàng | Kiểm tra số lượng → cập nhật |
| UC12 | Xóa món khỏi giỏ hàng | Khách hàng | Xóa item khỏi giỏ |

# **UC07 — Cập nhật món ăn**

| Requirement ID | UC07 |
| ----- | ----- |
| **Requirement Name** | Cập nhật món ăn |
| **Actor** | Admin |
| **Requirement Description** | Cho phép Admin cập nhật thông tin món ăn. Hệ thống kiểm tra món ăn tồn tại, kiểm tra và thay thế dữ liệu bằng dữ liệu mới.  |
| **Input** | id; body giống UC06: categoryId, name, price bắt buộc; description, imageUrl được thay thế (không gửi = xóa giá trị); stockQuantity, status tùy chọn (không gửi hoặc null = giữ nguyên). |
| **Preconditions** | Admin đã đăng nhập và có quyền quản lý món ăn. Món ăn cần thao tác phải tồn tại trong hệ thống (kể cả món INACTIVE).  |
| **Functional Requirement** | 1\. Admin gửi yêu cầu qua PUT /api/foods/{id}. 2\. Hệ thống xác thực người dùng và kiểm tra quyền Admin qua Spring Security filter. 3\. Kiểm tra dữ liệu như UC06. 4\. Lấy món ăn hiện tại thông qua Food Repository và kiểm tra tồn tại. 5\. Kiểm tra categoryId tồn tại và tên (sau khi trim) không trùng với món khác (không phân biệt hoa thường). 6\. Thay thế categoryId, name, description, price, imageUrl; chỉ cập nhật stockQuantity/status khi giá trị khác null. (Phase 2) Cập nhật tồn kho dùng cùng cơ chế chống tranh chấp với checkout để tránh ghi đè khi khách đang đặt món. 7\. Lưu thay đổi trong một giao dịch. 8\. Trả 200 với thông tin món ăn sau khi cập nhật dưới dạng JSON. |
| **Nonfunctional Requirement** | 1\. API sử dụng REST/JSON 2\. Chỉ Admin có quyền cập nhật món ăn 3\. Tầng nghiệp vụ không truy cập trực tiếp Database 4\. Dữ liệu được thao tác thông qua Repository/Data Access Layer. |
| **Error Handling** | 401 nếu chưa đăng nhập; 400 (`VALIDATION_ERROR`) nếu id, giá, tồn kho hoặc dữ liệu cập nhật không hợp lệ; 404 (`FOOD_NOT_FOUND`/`CATEGORY_NOT_FOUND`) nếu món ăn hoặc danh mục không tồn tại; 403 nếu người dùng không có quyền Admin; 409 (`FOOD_NAME_CONFLICT`) nếu tên món trùng; `CONFLICT` nếu vi phạm ràng buộc DB; `CONCURRENT_UPDATE` nếu deadlock/hết thời gian chờ khóa; 500 nếu lỗi truy xuất dữ liệu. |

### **API:** PUT /api/foods/{id}

# **UC08 — Xoá món ăn**

| Requirement ID | UC08 |
| ----- | ----- |
| **Requirement Name** | Xoá món ăn |
| **Actor** | Admin |
| **Requirement Description** | Cho phép Admin ngừng bán món ăn khi món ăn không còn được cung cấp. Thao tác luôn là xóa mềm: món chuyển sang INACTIVE, không xóa vật lý, để đơn cũ giữ snapshot và hủy đơn vẫn hoàn kho được. |
| **Input** | id |
| **Preconditions** | Admin đã đăng nhập và có quyền quản lý món ăn. Món ăn cần thao tác phải tồn tại trong hệ thống.  |
| **Functional Requirement** | 1\. Admin gửi yêu cầu qua DELETE /api/foods/{id}. 2\. Hệ thống xác thực người dùng và kiểm tra quyền Admin qua Spring Security filter. 3\. Lấy món ăn qua Food Repository và kiểm tra tồn tại. 4\. Chuyển trạng thái thành INACTIVE (gọi lại với món đã INACTIVE vẫn thành công). 5\. Lưu thay đổi trong giao dịch. 6\. Trả 204 No Content, không có body. |
| **Nonfunctional Requirement** | 1\. Endpoint DELETE yêu cầu xác thực và phân quyền Admin 2\. Không xóa vật lý món ăn, nên không phá vỡ dữ liệu đơn hàng và giỏ hàng tham chiếu tới món 3\. Tầng nghiệp vụ không truy cập trực tiếp Database. |
| **Error Handling** | 401 nếu chưa đăng nhập; 400 (`VALIDATION_ERROR`) nếu id không parse được thành int64; 404 (`FOOD_NOT_FOUND`) nếu món ăn không tồn tại (kể cả id ≤ 0); 403 nếu người dùng không có quyền Admin; 500 nếu lỗi cập nhật trạng thái. |

### **API:** DELETE /api/foods/{id}

# **UC09 — Xem giỏ hàng**

| Requirement ID | UC09 |
| ----- | ----- |
| **Requirement Name** | Xem giỏ hàng |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng xem các món ăn hiện có trong giỏ hàng của mình.  Hệ thống lấy thông tin giỏ hàng theo người dùng đang đăng nhập và trả về danh sách món ăn cùng thông tin chi tiết. |
| **Input** | customerId lấy từ JWT; không có tham số khác. |
| **Preconditions** | Khách hàng đã đăng nhập. Nếu khách chưa có giỏ, hệ thống trả giỏ rỗng và không tạo giỏ mới. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu qua GET /api/cart.  2\. Hệ thống xác thực người dùng và kiểm tra quyền CUSTOMER qua Spring Security filter.  3\. Lấy giỏ hàng của khách hàng thông qua Cart Repository. 4\. Lấy thông tin các món trong giỏ qua FoodService, kể cả món đã INACTIVE (trả kèm status để client nhận biết). 5\. Trả 200 với giỏ hàng dạng JSON: id, userId, items (id, food, quantity); không tính tổng tiền giỏ. Chưa có giỏ: id = null, items rỗng. |
| **Nonfunctional Requirement** | 1\. Endpoint GET yêu cầu xác thực người dùng. 2\. API tuân thủ REST/JSON 3\. Khách hàng chỉ được phép xem giỏ hàng của chính mình. 4\. Dữ liệu được truy cập thông qua Repository/Data Access Layer. |
| **Error Handling** | 401 nếu chưa đăng nhập; 403 nếu không có quyền khách hàng. Chưa có giỏ: 200 với danh sách món rỗng. 500 nếu lỗi truy xuất dữ liệu. |

### **API:** GET /api/cart

# **UC10 — Thêm món vào giỏ** 

| Requirement ID | UC10 |
| ----- | ----- |
| **Requirement Name** | Thêm món vào giỏ hàng |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng thêm món ăn vào giỏ hàng của mình.  Hệ thống kiểm tra món ăn tồn tại, kiểm tra trạng thái bán và thêm mới hoặc cập nhật số lượng món ăn trong giỏ hàng. |
| **Input** | customerId lấy từ JWT; foodId (số dương), quantity (số nguyên ≥ 1). |
| **Preconditions** | Khách hàng đã đăng nhập. Món ăn tồn tại trong hệ thống. Món ăn đang ở trạng thái ACTIVE. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu qua POST /api/cart/items.  2\. Hệ thống xác thực người dùng và kiểm tra quyền CUSTOMER qua Spring Security filter.  3\. Lấy giỏ hàng của khách hàng thông qua Cart Repository; tạo giỏ mới nếu chưa có.  4\. Kiểm tra món ăn tồn tại và đang ACTIVE qua FoodService (chưa kiểm tra tồn kho ở bước này).  5\. Nếu món đã có trong giỏ, cộng thêm số lượng; nếu chưa có, tạo mới Cart Item.  6\. Lưu thay đổi trong giao dịch.  7\. Trả 200 với giỏ hàng sau khi cập nhật dưới dạng JSON. |
| **Nonfunctional Requirement** | 1\. API sử dụng REST/JSON. 2\. Chỉ khách hàng đã xác thực mới được thao tác giỏ hàng. 3\. Thao tác thêm/cập nhật món phải đảm bảo tính nhất quán dữ liệu. 4\. Tầng nghiệp vụ không truy cập trực tiếp Database 5\. Dữ liệu được thao tác thông qua Repository. |
| **Error Handling** | 401 nếu khách hàng chưa đăng nhập; 403 nếu không có quyền khách hàng; 400 (`VALIDATION_ERROR`) nếu foodId/số lượng thiếu hoặc không hợp lệ, hoặc tổng số lượng bị tràn số; 404 (`FOOD_NOT_FOUND`) nếu món ăn không tồn tại; 409 (`FOOD_UNAVAILABLE`) nếu món ăn đã INACTIVE; 500 nếu lỗi lưu dữ liệu giỏ hàng. |

### **API:** POST /api/cart/items

# **UC11 — Cập nhật số lượng món**

| Requirement ID | UC11 |
| ----- | ----- |
| **Requirement Name** | Cập nhật số lượng  |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng cập nhật số lượng của món ăn trong giỏ hàng. Hệ thống kiểm tra món ăn tồn tại trong giỏ hàng, kiểm tra số lượng hợp lệ và cập nhật lại thông tin Cart Item. |
| **Input** | customerId lấy từ JWT; cartItemId; quantity mới (số nguyên ≥ 1). |
| **Preconditions** | Khách hàng đã đăng nhập; Cart Item cần cập nhật nằm trong giỏ của chính khách hàng. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu qua PUT /api/cart/items/{cartItemId}.  2\. Hệ thống xác thực người dùng và kiểm tra quyền CUSTOMER qua Spring Security filter; kiểm tra quantity hợp lệ.  3\. Lấy giỏ hàng của khách hàng thông qua Cart Repository.  4\. Tìm Cart Item theo cartItemId **trong giỏ của khách hàng**; item của người khác được xử lý như không tồn tại. 5\. Ghi đè số lượng của Cart Item (không kiểm tra lại trạng thái món hay tồn kho).  6\. Lưu thay đổi trong giao dịch.  7\. Trả 200 với giỏ hàng sau khi cập nhật dưới dạng JSON. |
| **Nonfunctional Requirement** | 1\. Endpoint PUT yêu cầu xác thực người dùng 2\. Khách hàng chỉ được phép cập nhật giỏ hàng của chính mình 3\. Thao tác cập nhật phải đảm bảo tính nhất quán dữ liệu 4\. Tầng nghiệp vụ không truy cập trực tiếp Database 5\. Dữ liệu được thao tác thông qua Repository/Data Access Layer. |
| **Error Handling** | 401 nếu khách hàng chưa đăng nhập; 403 nếu không có quyền khách hàng; 400 (`VALIDATION_ERROR`) nếu cartItemId hoặc số lượng không hợp lệ; 404 (`CART_ITEM_NOT_FOUND`) nếu chưa có giỏ, Cart Item không tồn tại hoặc thuộc giỏ của người khác; 500 nếu lỗi cập nhật dữ liệu. |

### **API:** PUT /api/cart/items/{cartItemId}

# **UC12 — Xoá món khỏi giỏ hàng**

| Requirement ID | UC12 |
| ----- | ----- |
| **Requirement Name** | Xoá món khỏi giỏ  |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng xóa một món ăn khỏi giỏ hàng của mình.  Hệ thống kiểm tra món ăn tồn tại trong giỏ hàng và thực hiện xóa Cart Item tương ứng. |
| **Input** | customerId lấy từ JWT; cartItemId |
| **Preconditions** | Khách hàng đã đăng nhập; Cart Item cần xóa nằm trong giỏ của chính khách hàng. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu qua DELETE /api/cart/items/{cartItemId}.  2\. Hệ thống xác thực người dùng và kiểm tra quyền CUSTOMER qua Spring Security filter.  3\. Lấy giỏ hàng của khách hàng thông qua Cart Repository. 4\. Tìm Cart Item theo cartItemId trong giỏ của khách hàng; item của người khác được xử lý như không tồn tại.  5\. Xóa Cart Item khỏi giỏ hàng và lưu thay đổi trong giao dịch.  6\. Trả 200 với giỏ hàng sau khi xóa dưới dạng JSON. |
| **Nonfunctional Requirement** | 1\. Endpoint DELETE yêu cầu xác thực 2\. Khách hàng không được phép xóa món trong giỏ hàng của người dùng khác 3\. Thao tác xóa phải đảm bảo tính toàn vẹn dữ liệu 4\. Tầng nghiệp vụ không truy cập trực tiếp Database 5\. Dữ liệu được truy cập thông qua Repository/Data Access Layer. |
| **Error Handling** | 401 nếu khách hàng chưa đăng nhập; 403 nếu không có quyền khách hàng; 400 (`VALIDATION_ERROR`) nếu cartItemId không hợp lệ; 404 (`CART_ITEM_NOT_FOUND`) nếu chưa có giỏ, Cart Item không tồn tại hoặc thuộc giỏ của người khác; 500 nếu lỗi xóa dữ liệu. |

### **API:** DELETE /api/cart/items/{cartItemId}

### **D. Quản lý đơn hàng**

| ID | Use Case | Actor | Nghiệp vụ chính |
| ----- | ----- | ----- | ----- |
| UC13 | Tạo đơn hàng | Khách hàng | Chọn ăn tại chỗ/giao hàng → kiểm tra giỏ và tồn kho → tính tiền → tạo đơn |
| UC14 | Xem đơn hàng | Khách hàng | Lấy danh sách/chi tiết đơn |
| UC15 | Hủy đơn hàng | Khách hàng | Kiểm tra trạng thái → hủy đơn |
| UC16 | Xác nhận đơn hàng  | Nhân viên | Kiểm tra đơn → xác nhận |
| UC17 | Cập nhật trạng thái đơn | Nhân viên | Thay đổi trạng thái đơn |
| UC18 | Xem danh sách đơn cần xử lý | Nhân viên | Lấy các đơn đang chờ xử lý |

**UC13 — Tạo đơn hàng**

| Requirement ID | UC13 |
| ----- | ----- |
| **Requirement Name** | Tạo đơn hàng |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng tạo đơn ăn tại chỗ hoặc giao về nhà từ các món trong giỏ hàng đã lưu. Hệ thống kiểm tra hình thức phục vụ, thông tin tương ứng, giỏ hàng, trạng thái món và tồn kho, tính tổng tiền và lưu đơn hàng. |
| **Input** | customerId lấy từ JWT; orderType (DINE_IN/DELIVERY), paymentMethod, note (tùy chọn); tableNumber cho DINE_IN; recipientName, shippingPhone, shippingAddress cho DELIVERY. Danh sách món và số lượng lấy từ giỏ trên server. |
| **Preconditions** | Khách hàng đã đăng nhập cho cả DINE_IN và DELIVERY; giỏ hàng tồn tại và không rỗng; các món trong giỏ tồn tại, đang ACTIVE và còn đủ số lượng. |
| **Functional Requirement** | 1\. Nhận yêu cầu qua POST /api/orders và xác thực khách hàng. 2\. Kiểm tra orderType và paymentMethod; DINE_IN yêu cầu tableNumber và không có trường giao hàng, DELIVERY yêu cầu đủ thông tin nhận hàng và không có tableNumber. 3\. Bắt đầu một giao dịch (`@Transactional` ở OrderService); đọc các món trong giỏ của khách hàng qua CartService (Cart Repository). 4\. Kiểm tra giỏ tồn tại và không rỗng; với từng món, FoodService đọc món qua Food Repository, kiểm tra trạng thái bán và tồn kho rồi trừ kho. 5\. Tính tổng tiền bằng giá trên server; tạo snapshot tên/giá món và thông tin bàn hoặc giao hàng. 6\. Tạo đơn trạng thái PENDING; lưu đơn và chi tiết qua Order Repository. 7\. Dọn các Cart Item qua CartService (Cart Repository) trong cùng giao dịch; commit khi tất cả thành công, rollback toàn bộ khi có lỗi. 8\. Trả 201 Created (header Location /api/orders/{id}) với orderType, thông tin bàn/giao hàng, chi tiết món, totalAmount và trạng thái. |
| **Nonfunctional Requirement** | REST API sử dụng JSON; tầng nghiệp vụ không import framework web/thư viện DB (dùng Spring @Service/@Transactional, repository là Spring Data); dữ liệu được truy cập thông qua Repository/Data Access Layer. Tạo đơn, chi tiết đơn, trừ tồn kho và dọn giỏ phải nguyên tử. (Phase 2) Chống checkout đồng thời gây bán vượt tồn kho (lost update; tồn kho không âm) hoặc tạo hai đơn từ cùng giỏ; phase 1 chưa khóa hàng nên chưa bảo đảm điều này. |
| **Error Handling** | 401 nếu chưa đăng nhập; 403 nếu không có quyền khách hàng; 400 (`VALIDATION_ERROR`) nếu orderType/paymentMethod sai, thông tin bàn/giao hàng thiếu, quá dài hoặc không đúng loại đơn; 400 (`EMPTY_CART`) nếu chưa có giỏ hoặc giỏ rỗng; 404 (`FOOD_NOT_FOUND`) nếu món không tồn tại; 409 (`FOOD_UNAVAILABLE`/`INSUFFICIENT_STOCK`/`CONFLICT`) nếu món ngừng bán, không đủ tồn kho hoặc vi phạm ràng buộc dữ liệu; 500 nếu lỗi hệ thống, đồng thời rollback giao dịch. |

**API:** POST /api/orders

Ví dụ ăn tại chỗ:

```json
{
  "orderType": "DINE_IN",
  "tableNumber": "B05",
  "paymentMethod": "CASH",
  "note": "Ít cay"
}
```

Ví dụ giao về nhà:

```json
{
  "orderType": "DELIVERY",
  "recipientName": "Nguyễn Minh An",
  "shippingPhone": "0901234567",
  "shippingAddress": "12 Nguyễn Văn Cừ, Phường Cầu Ông Lãnh, TP. Hồ Chí Minh",
  "paymentMethod": "COD",
  "note": "Gọi trước khi giao"
}
```

`customerId`, giá món và tổng tiền không lấy từ body; hệ thống xác định khách hàng từ JWT và đọc giỏ hiện tại. Số bàn là snapshot văn bản, không phải mã đặt chỗ.

# **UC14 — Xem đơn hàng**

| Requirement ID | UC14 |
| ----- | ----- |
| **Requirement Name** | Xem đơn hàng |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng xem danh sách và chi tiết các đơn ăn tại chỗ/giao hàng của mình, bao gồm hình thức phục vụ, thông tin bàn hoặc nhận hàng và trạng thái xử lý. |
| **Input** | customerId từ JWT; tùy trường hợp có orderId; status, orderType, page (mặc định 0), size (mặc định 20, tối đa 100) (tùy chọn). |
| **Preconditions** | Khách hàng đã đăng nhập. |
| **Functional Requirement** | 1\. Nhận GET /api/orders để lấy danh sách hoặc GET /api/orders/{orderId} để xem chi tiết. 2\. Xác thực người dùng qua Spring Security filter. 3\. Chỉ trả các đơn thuộc khách hàng đang đăng nhập; không gửi status thì lấy mọi trạng thái. 4\. Lấy dữ liệu thông qua Order Repository, lọc theo status/orderType, sắp xếp mới nhất trước và phân trang. 5\. Trả JSON bao gồm orderType, tableNumber cho DINE_IN hoặc recipientName/shippingPhone/shippingAddress cho DELIVERY, paymentMethod, totalAmount, trạng thái và các snapshot tên/giá món. Các trường không phù hợp với orderType là null. Danh sách trả dạng trang: content, page, size, totalElements. |
| **Nonfunctional Requirement** | Endpoint GET yêu cầu xác thực; API tuân thủ REST; không cho phép khách hàng truy cập đơn hàng của khách hàng khác. |
| **Error Handling** | 401 nếu chưa đăng nhập; 400 (`VALIDATION_ERROR`) nếu orderId, status, orderType, page hoặc size không hợp lệ; 404 (`ORDER_NOT_FOUND`) nếu đơn hàng không tồn tại; 403 (`FORBIDDEN`) nếu khách hàng cố truy cập đơn hàng không thuộc quyền sở hữu; 500 nếu lỗi truy xuất dữ liệu. |

### **API:** GET /api/orders		| 	GET /api/orders/{orderId}

### **UC15 — Hủy đơn hàng**

| Requirement ID | UC15 |
| ----- | ----- |
| **Requirement Name** | Hủy đơn hàng |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng hủy đơn DINE_IN hoặc DELIVERY của mình khi trạng thái còn PENDING. |
| **Input** | orderId; customerId lấy từ thông tin đăng nhập. |
| **Preconditions** | Khách hàng đã đăng nhập; đơn hàng tồn tại; đơn hàng thuộc về khách hàng đang đăng nhập. |
| **Functional Requirement** | 1\. Nhận yêu cầu hủy qua DELETE /api/orders/{orderId}. 2\. Xác thực khách hàng. 3\. Bắt đầu giao dịch và lấy đơn thông qua Order Repository; kiểm tra đơn tồn tại và thuộc khách hàng. 4\. Chỉ cho phép chuyển PENDING sang CANCELLED. 5\. Phục hồi tồn kho đã trừ qua Food Repository; hủy lại đơn đã hủy bị từ chối nên không hoàn kho lần hai. (Phase 2) Khóa đơn/món để hai yêu cầu hủy đồng thời chỉ hoàn kho một lần. 6\. Lưu trạng thái qua Repository và commit cùng cập nhật tồn kho; rollback khi lỗi. 7\. Trả 200 với đơn hàng sau khi hủy dưới dạng JSON, giữ lại đơn và chi tiết làm lịch sử. |
| **Nonfunctional Requirement** | Endpoint DELETE yêu cầu xác thực; thao tác hủy phải đảm bảo dữ liệu đơn hàng không bị cập nhật sai trạng thái; tầng nghiệp vụ không truy cập trực tiếp Database. |
| **Error Handling** | 401 nếu chưa đăng nhập; 403 nếu không có quyền khách hàng; 400 (`VALIDATION_ERROR`) nếu orderId không hợp lệ; 404 (`ORDER_NOT_FOUND`) nếu đơn không tồn tại; 403 (`FORBIDDEN`) nếu đơn không thuộc khách hàng; 409 (`INVALID_ORDER_TRANSITION`) nếu trạng thái khác PENDING, bao gồm đơn đã hủy; 500 nếu lỗi cập nhật dữ liệu và rollback giao dịch. |

### **API:** DELETE /api/orders/{orderId}

# **UC16 — Xác nhận đơn hàng**

| Requirement ID | UC16 |
| ----- | ----- |
| **Requirement Name** | Xác nhận đơn hàng |
| **Actor** | Nhân viên |
| **Requirement Description** | Cho phép nhân viên kiểm tra và xác nhận các đơn DINE_IN/DELIVERY đang chờ xử lý; hiển thị đúng số bàn hoặc thông tin nhận hàng của từng loại đơn. |
| **Input** | orderId; employeeId lấy từ thông tin đăng nhập. |
| **Preconditions** | Nhân viên đã đăng nhập và có quyền xử lý đơn hàng; đơn hàng tồn tại; đơn hàng đang ở trạng thái PENDING. |
| **Functional Requirement** | 1\. Nhân viên gửi yêu cầu qua POST /api/orders/{orderId}/confirm. 2\. Hệ thống xác thực và kiểm tra quyền EMPLOYEE qua Spring Security filter. 3\. Lấy đơn hàng từ Order Repository. 4\. Kiểm tra đơn đang ở PENDING. 5\. Chuyển trạng thái sang CONFIRMED, ghi confirmedBy (nhân viên) và confirmedAt. 6\. Lưu thay đổi trong giao dịch. 7\. Trả 200 với thông tin đơn hàng sau khi xác nhận. |
| **Nonfunctional Requirement** | API sử dụng REST/JSON; thao tác xác nhận phải đảm bảo tính nhất quán dữ liệu; tầng nghiệp vụ không import framework web/thư viện DB (dùng Spring @Service/@Transactional, repository là Spring Data). |
| **Error Handling** | 401 nếu chưa đăng nhập; 403 nếu người dùng không có quyền nhân viên; 400 (`VALIDATION_ERROR`) nếu orderId không hợp lệ; 404 (`ORDER_NOT_FOUND`) nếu đơn hàng không tồn tại; 409 (`INVALID_ORDER_TRANSITION`) nếu đơn hàng không ở trạng thái PENDING; 500 nếu lỗi cập nhật. |

### **API:** POST /api/orders/{orderId}/confirm

# **UC17 — Cập nhật trạng thái đơn**

| Requirement ID | UC17 |
| ----- | ----- |
| **Requirement Name** | Cập nhật trạng thái đơn |
| **Actor** | Nhân viên |
| **Requirement Description** | Cho phép nhân viên thay đổi trạng thái theo quy trình riêng của DINE_IN hoặc DELIVERY và hủy đơn trong các trạng thái được phép. |
| **Input** | orderId; status (CONFIRMED, PROCESSING, SHIPPING, COMPLETED, CANCELLED); employeeId từ JWT. |
| **Preconditions** | Nhân viên đã đăng nhập và có quyền xử lý đơn hàng; đơn hàng tồn tại. |
| **Functional Requirement** | 1\. Nhân viên gửi yêu cầu qua PUT /api/orders/{orderId}/status. 2\. Spring Security filter xác thực và kiểm tra quyền EMPLOYEE. 3\. Lấy đơn hiện tại qua Order Repository trong giao dịch; khóa đơn để chống cập nhật đồng thời là yêu cầu phase 2. 4\. Kiểm tra orderType và chuyển trạng thái: PENDING → CONFIRMED → PROCESSING cho cả hai loại; DELIVERY tiếp tục PROCESSING → SHIPPING → COMPLETED; DINE_IN chỉ PROCESSING → COMPLETED, không cho SHIPPING. 5\. Cho phép nhân viên hủy từ PENDING, CONFIRMED hoặc PROCESSING sang CANCELLED; không hủy từ SHIPPING, COMPLETED hoặc CANCELLED. Nếu hủy, phục hồi tồn kho đúng một lần qua Food Repository trong cùng giao dịch. 6\. Lưu trạng thái và commit; rollback nếu có lỗi. 7\. Trả 200 với thông tin đơn hàng sau cập nhật. |
| **Nonfunctional Requirement** | Chỉ người dùng có quyền nhân viên mới được cập nhật trạng thái; việc chuyển trạng thái phải tuân thủ Business Rule; API sử dụng JSON và REST; thao tác cập nhật phải đảm bảo tính nhất quán dữ liệu. |
| **Error Handling** | 401 nếu chưa đăng nhập; 403 nếu không có quyền; 404 (`ORDER_NOT_FOUND`) nếu đơn không tồn tại; 400 (`VALIDATION_ERROR`) nếu status thiếu, không thuộc tập giá trị hợp lệ hoặc là PENDING; 409 (`INVALID_ORDER_TRANSITION`) nếu bước chuyển không hợp lệ, yêu cầu SHIPPING cho DINE_IN hoặc hủy ngoài trạng thái cho phép; 500 nếu lỗi cập nhật dữ liệu và rollback giao dịch. |

### **API:** PUT /api/orders/{orderId}/status

Ví dụ chuyển đơn DELIVERY từ PROCESSING sang SHIPPING:

```json
{
  "status": "SHIPPING"
}
```

Đơn DINE_IN ở PROCESSING dùng `{"status":"COMPLETED"}`; yêu cầu SHIPPING trả 409 Conflict.

# **UC18 — Xem danh sách đơn cần xử lý**

| Requirement ID | UC18 |
| ----- | ----- |
| **Requirement Name** | Xem danh sách đơn cần xử lý |
| **Actor** | Nhân viên |
| **Requirement Description** | Cho phép nhân viên xem các đơn DINE_IN/DELIVERY cần xử lý ở PENDING, CONFIRMED, PROCESSING; đơn DELIVERY đang SHIPPING cũng được theo dõi tới hoàn thành. |
| **Input** | employeeId từ JWT; status, orderType, page (mặc định 0), size (mặc định 20, tối đa 100) (tùy chọn). |
| **Preconditions** | Nhân viên đã đăng nhập và có quyền xử lý đơn hàng. |
| **Functional Requirement** | 1\. Nhân viên gửi yêu cầu qua GET /api/orders. 2\. Spring Security filter xác thực và kiểm tra quyền. 3\. Truy vấn các đơn cần xử lý qua Order Repository; nếu không gửi status thì mặc định PENDING, CONFIRMED, PROCESSING và SHIPPING. 4\. Khi truyền status, lọc đúng trạng thái đó, kể cả COMPLETED/CANCELLED; kết hợp orderType bằng AND; tổ hợp DINE_IN + SHIPPING trả danh sách rỗng vì loại đơn này không có trạng thái đó. 5\. Sắp xếp mới nhất trước, phân trang và trả JSON cùng cấu trúc với UC14 (content, page, size, totalElements; mỗi đơn có orderType, số bàn hoặc thông tin giao hàng, paymentMethod, totalAmount, trạng thái và chi tiết món). 6\. Nhân viên có quyền xem GET /api/orders/{orderId} của mọi đơn. |
| **Nonfunctional Requirement** | Endpoint GET yêu cầu xác thực; truy cập Database thông qua Repository/Data Access Layer. |
| **Error Handling** | 401 nếu chưa đăng nhập; 403 nếu không có quyền nhân viên; 400 (`VALIDATION_ERROR`) nếu status, orderType, page hoặc size không hợp lệ; trả danh sách rỗng nếu không có đơn cần xử lý; 500 nếu lỗi truy xuất dữ liệu. |

### **API:** GET /api/orders?status=PENDING&orderType=DINE_IN

GET /api/orders được giới hạn theo vai trò: CUSTOMER chỉ xem đơn của mình (UC14), EMPLOYEE xem các đơn cần xử lý theo bộ lọc (UC18). Quyền được lấy từ JWT, không từ tham số customerId/employeeId do client tự truyền.
