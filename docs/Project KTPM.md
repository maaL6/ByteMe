# ByteMe — Use case cho một nhà hàng

Ứng dụng phục vụ **một nhà hàng duy nhất**. Khách hàng dùng cùng tài khoản, thực đơn và giỏ hàng để đặt món ăn tại chỗ (`DINE_IN`) hoặc giao về nhà (`DELIVERY`). Không có lựa chọn nhà hàng; dữ liệu món không có `restaurantId` và database không cần bảng `restaurants`.

Tài liệu này giữ UC01–UC18 và ba vai trò: `CUSTOMER` đăng ký, xem thực đơn, quản lý giỏ và đơn của mình; `EMPLOYEE` xác nhận, xử lý đơn; `ADMIN` quản lý món ăn. Cả hai cách đặt món đều yêu cầu khách hàng đăng nhập. Chức năng đặt bàn, khách vãng lai và tích hợp cổng thanh toán chưa nằm trong phạm vi các use case này.

Quy tắc đơn hàng dùng chung:

- API dùng `orderType`, database dùng `order_type`, với hai giá trị `DINE_IN` và `DELIVERY`.
- `DINE_IN` bắt buộc `tableNumber` không rỗng, tối đa 20 ký tự sau khi bỏ khoảng trắng; `recipientName`, `shippingPhone`, `shippingAddress` phải là `null` hoặc không gửi. `tableNumber` là nhãn bàn được lưu tại thời điểm đặt; giai đoạn này chưa quản lý danh mục hay tình trạng bàn.
- `DELIVERY` bắt buộc `recipientName`, `shippingPhone`, `shippingAddress` hợp lệ; `tableNumber` phải là `null` hoặc không gửi. Thông tin nhận hàng được lưu tại thời điểm đặt để giữ lịch sử.
- `paymentMethod` gồm `CASH` (chỉ `DINE_IN`), `COD` (chỉ `DELIVERY`) và `BANK_TRANSFER` (cả hai). Trường này ghi nhận cách thanh toán, chưa xác nhận thanh toán qua một cổng bên ngoài.
- Cả hai loại đơn đi qua `PENDING → CONFIRMED → PROCESSING`. Đơn giao hàng tiếp tục `SHIPPING → COMPLETED`; đơn tại chỗ đi trực tiếp từ `PROCESSING → COMPLETED`. Đơn `DINE_IN` không được mang trạng thái `SHIPPING`.
- Khách hàng chỉ hủy đơn của mình ở `PENDING`. Quy tắc được chọn cho nhân viên là được hủy đơn ở `PENDING`, `CONFIRMED`, `PROCESSING`; không hủy đơn `SHIPPING`, `COMPLETED` hoặc `CANCELLED`. Hủy phải phục hồi tồn kho đã trừ đúng một lần, trong cùng giao dịch với cập nhật trạng thái.
- Checkout đọc giỏ đã lưu, kiểm tra tồn kho, tính giá bằng dữ liệu hiện tại từ server và lưu snapshot tên/giá món; client không quyết định giá hoặc tổng tiền. Lưu đơn, lưu chi tiết, trừ tồn kho và dọn giỏ là một giao dịch đồng bộ.
- Tổng tiền đơn được suy ra từ các snapshot trong `order_items` qua view `order_totals`; không lưu một giá trị tổng riêng trên `orders`. `order_type` bắt buộc ghi rõ khi tạo đơn, không có giá trị mặc định ở schema hiện hành.

Hai PNG ban đầu là bản tham khảo; [sơ đồ kiến trúc và sequence đã chỉnh](architecture-single-restaurant.md) cùng tài liệu này là bản đặc tả hiện hành.

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
| UC08 | Xóa món ăn | Admin | Kiểm tra trạng thái → xóa/ngừng bán |

### **UC01 \- Đăng ký tài khoản**

| Requirement ID | UC01 |
| ----- | ----- |
| **Requirement Name** | Đăng ký tài khoản  |
| **Actor** | Khách hàng  |
| **Requirement Description** | Cho phép người dùng mới tạo tài khoản khách hàng trên hệ thống. Hệ thống kiểm tra tính hợp lệ của thông tin, kiểm tra trùng lặp tài khoản, mã hóa mật khẩu và tạo mới người dùng. |
| **Input** | fullName, email, phone, password, confirmPassword |
| **Preconditions** | 1\. Khách hàng chưa đăng nhập vào hệ thống. 2\. Thiết bị có kết nối mạng và truy cập được vào ứng dụng/API. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu đăng ký qua API. 2\. Hệ thống kiểm tra tính hợp lệ của dữ liệu đầu vào (định dạng email, số điện thoại, mật khẩu tối thiểu 8 ký tự và confirmPassword trùng khớp). 3\. Kiểm tra xem email/SĐT đã tồn tại hay chưa. 4\. Nếu hợp lệ, hệ thống thực hiện băm/mã hóa mật khẩu bằng thuật toán an toàn (BCrypt). 5\. Gán vai trò và trạng thái kích hoạt tài khoản. 6\. Lưu thông tin người dùng mới. 7\. Trả về thông tin tài khoản được tạo cùng thông báo thành công. |
| **Nonfunctional Requirement** | 1\. Mật khẩu phải được băm một chiều bằng thuật toán an toàn (BCrypt). 2\. Endpoint sử dụng HTTPS. 3\. Thời gian phản hồi ≤ 2 giây trong điều kiện tải thông thường. 4\. Tuân thủ thiết kế REST API, trả về JSON. |
| **Error Handling** | 400 Bad Request: dữ liệu đầu vào không đúng định dạng hoặc password và confirm password không khớp. 409 Conflict: email hoặc số điện thoại đã tồn tại trên hệ thống. 500 Internal Server Error: lỗi hệ thống khi tạo tài khoản. |

### **API: POST /api/auth/register**

# UC02 \- Đăng nhập

| Requirement ID | UC02 |
| ----- | ----- |
| **Requirement Name** | Đăng nhập |
| **Actor** | Khách hàng/Nhân viên/Admin |
| **Requirement Description** | Cho phép người dùng có tài khoản hợp lệ đăng nhập vào hệ thống. Hệ thống xác thực thông tin, kiểm tra trạng thái tài khoản và cấp access token JWT kèm thông tin người dùng. |
| **Input** | email, password |
| **Preconditions** | 1\. Tài khoản đã tồn tại trên hệ thống. 2\. Tài khoản đang ở trạng thái hoạt động. 3\. Thiết bị có kết nối mạng và truy cập được vào ứng dụng/API. |
| **Functional Requirement** | 1\. Người dùng gửi yêu cầu đăng nhập qua POST /api/auth/login. 2\. Hệ thống kiểm tra dữ liệu đầu vào bắt buộc và định dạng email. 3\. Tìm tài khoản theo email thông qua User Repository. 4\. So khớp mật khẩu với giá trị đã băm bằng BCrypt. 5\. Kiểm tra trạng thái tài khoản và quyền của người dùng. 6\. Tạo access token JWT có thời hạn, chứa định danh và vai trò cần thiết. 7\. Trả về token cùng thông tin cơ bản của người dùng; không trả về mật khẩu. |
| **Nonfunctional Requirement** | 1\. Endpoint sử dụng HTTPS và trả về JSON. 2\. Không ghi mật khẩu hoặc token đầy đủ vào log. 3\. JWT phải được ký bằng khóa cấu hình an toàn và có thời hạn sử dụng. 4\. Thời gian phản hồi \<= 2 giây trong điều kiện tải thông thường. 5\. Tầng nghiệp vụ không truy cập trực tiếp Database. |
| **Error Handling** | 400 Bad Request: thiếu email/password hoặc dữ liệu sai định dạng. 401 Unauthorized: email hoặc mật khẩu không chính xác. 403 Forbidden: tài khoản bị khóa hoặc chưa được kích hoạt. 429 Too Many Requests: vượt giới hạn số lần đăng nhập trong khoảng thời gian quy định. 500 Internal Server Error: lỗi hệ thống khi xác thực hoặc tạo token. |

### API: POST /api/auth/login

# UC03 \- Xem danh sách món ăn

| Requirement ID | UC03 |
| ----- | ----- |
| **Requirement Name** | Xem danh sách món ăn |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng xem danh sách các món ăn đang được bán của nhà hàng. Hệ thống hỗ trợ phân trang, sắp xếp và lọc theo danh mục. |
| **Input** | page, size, sort; categoryId (tùy chọn) |
| **Preconditions** | 1\. Thiết bị có kết nối mạng và truy cập được vào ứng dụng/API. 2\. Dữ liệu món ăn đã tồn tại trên hệ thống. 3\. Khách hàng không bắt buộc đăng nhập. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu qua GET /api/foods. 2\. Hệ thống kiểm tra tham số phân trang, sắp xếp và bộ lọc. 3\. Truy vấn Food Repository để lấy các món có trạng thái ACTIVE. 4\. Áp dụng bộ lọc theo categoryId nếu được cung cấp. 5\. Sắp xếp và phân trang kết quả. 6\. Chuyển dữ liệu sang DTO, bao gồm trạng thái khả dụng theo tồn kho. 7\. Trả về danh sách món ăn và thông tin phân trang dưới dạng JSON. |
| **Nonfunctional Requirement** | 1\. API tuân thủ REST và trả về JSON. 2\. Thời gian phản hồi \<= 2 giây trong điều kiện tải thông thường. 3\. Truy vấn phải hỗ trợ phân trang, không tải toàn bộ dữ liệu vào bộ nhớ. 4\. Tầng nghiệp vụ không truy cập trực tiếp Database. |
| **Error Handling** | 400 Bad Request: tham số page, size, sort hoặc bộ lọc không hợp lệ. 404 Not Found: categoryId được chỉ định không tồn tại. 200 OK với danh sách rỗng: không có món phù hợp. 500 Internal Server Error: lỗi truy xuất dữ liệu. |

### API: GET /api/foods

# UC04 \- Xem chi tiết món ăn

| Requirement ID | UC04 |
| ----- | ----- |
| **Requirement Name** | Xem chi tiết món ăn |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng xem thông tin chi tiết của một món ăn đang được bán, bao gồm tên, mô tả, giá, hình ảnh, danh mục và trạng thái khả dụng theo tồn kho. |
| **Input** | foodId |
| **Preconditions** | 1\. Thiết bị có kết nối mạng và truy cập được vào ứng dụng/API. 2\. Khách hàng không bắt buộc đăng nhập. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu qua GET /api/foods/{foodId}. 2\. Hệ thống kiểm tra định dạng foodId. 3\. Truy vấn món ăn theo foodId thông qua Food Repository. 4\. Kiểm tra món ăn tồn tại và có trạng thái ACTIVE. 5\. Lấy danh mục và trạng thái khả dụng theo tồn kho. 6\. Chuyển dữ liệu sang DTO; không lộ trường nội bộ. 7\. Trả về chi tiết món ăn dưới dạng JSON. |
| **Nonfunctional Requirement** | 1\. API tuân thủ REST và trả về JSON. 2\. Thời gian phản hồi  \<= 2 giây trong điều kiện tải thông thường. 3\. Không phát sinh truy vấn dư thừa khi lấy thông tin liên quan. 4\. Tầng nghiệp vụ không truy cập trực tiếp Database. |
| **Error Handling** | 400 Bad Request: foodId không đúng định dạng. 404 Not Found: món ăn không tồn tại hoặc không còn được bán. 500 Internal Server Error: lỗi truy xuất dữ liệu. |

### API: GET /api/foods/{foodId}

### UC05 \- Tìm kiếm món ăn

| Requirement ID | UC05 |
| ----- | ----- |
| **Requirement Name** | Tìm kiếm món ăn |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng tìm món ăn theo từ khóa và kết hợp các bộ lọc cơ bản. Hệ thống chỉ trả về những món đang được bán và hỗ trợ phân trang. |
| **Input** | q; categoryId, minPrice, maxPrice, page, size, sort (tùy chọn) |
| **Preconditions** | 1\. Thiết bị có kết nối mạng và truy cập được vào ứng dụng/API. 2\. Khách hàng không bắt buộc đăng nhập. 3\. Từ khóa q sau khi loại bỏ khoảng trắng phải có từ 1 đến 100 ký tự. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu qua GET /api/foods/search?q={keyword}. 2\. Hệ thống chuẩn hóa từ khóa và kiểm tra các tham số lọc/phân trang. 3\. Truy vấn Food Repository theo tên hoặc mô tả, không phân biệt hoa thường. 4\. Chỉ lấy món có trạng thái ACTIVE và áp dụng các bộ lọc được cung cấp. 5\. Sắp xếp, phân trang và chuyển kết quả sang DTO. 6\. Trả về danh sách phù hợp cùng thông tin phân trang dưới dạng JSON. |
| **Nonfunctional Requirement** | 1\. API tuân thủ REST và trả về JSON. 2\. Truy vấn tìm kiếm phải dùng tham số hóa để tránh injection. 3\. Các trường tìm kiếm/lọc cần có chỉ mục phù hợp với khối lượng dữ liệu. 4\. Thời gian phản hồi \<= 2 giây trong điều kiện tải thông thường. 5\. Tầng nghiệp vụ không truy cập trực tiếp Database. |
| **Error Handling** | 400 Bad Request: q rỗng/quá dài, khoảng giá không hợp lệ hoặc tham số phân trang sai. 404 Not Found: categoryId được chỉ định không tồn tại. 200 OK với danh sách rỗng: không tìm thấy món phù hợp. 500 Internal Server Error: lỗi truy vấn dữ liệu. |

### API: GET /api/foods/search?q={keyword}

### UC06 \- Thêm món ăn

| Requirement ID | UC06 |
| ----- | ----- |
| **Requirement Name** | Thêm món ăn |
| **Actor** | Admin |
| **Requirement Description** | Cho phép Admin tạo một món ăn mới thuộc danh mục hợp lệ trong thực đơn của nhà hàng. Hệ thống kiểm tra dữ liệu, quyền truy cập và tính trùng lặp trước khi lưu. |
| **Input** | name, description, price, imageUrl, categoryId, status, stockQuantity |
| **Preconditions** | 1\. Admin đã đăng nhập và có quyền quản lý món ăn. 2\. Danh mục được tham chiếu đã tồn tại. 3\. Kết nối đến kho dữ liệu đang hoạt động. |
| **Functional Requirement** | 1\. Admin gửi yêu cầu qua POST /api/foods. 2\. Hệ thống xác thực JWT và kiểm tra quyền Admin. 3\. Kiểm tra dữ liệu bắt buộc; name không rỗng, price \> 0, stockQuantity là số nguyên không âm và status thuộc tập giá trị cho phép. 4\. Kiểm tra categoryId tồn tại. 5\. Kiểm tra tên món ăn không bị trùng trong toàn bộ thực đơn. 6\. Tạo thực thể món ăn với trạng thái ACTIVE hoặc INACTIVE và tồn kho hợp lệ. 7\. Lưu món ăn thông qua Food Repository trong một giao dịch. 8\. Trả về món ăn vừa tạo dưới dạng JSON với mã 201 Created. |
| **Nonfunctional Requirement** | 1\. Endpoint sử dụng HTTPS, yêu cầu xác thực và phân quyền Admin. 2\. API tuân thủ REST và trả về JSON. 3\. Thao tác tạo phải bảo đảm tính toàn vẹn và tính nguyên tử của dữ liệu. 4\. Tầng nghiệp vụ không truy cập trực tiếp Database. 5\. Thời gian phản hồi  \<=  2 giây trong điều kiện tải thông thường. |
| **Error Handling** | 400 Bad Request: dữ liệu thiếu/sai định dạng, price \<= 0, stockQuantity không phải số nguyên không âm hoặc status không hợp lệ. 401 Unauthorized: chưa đăng nhập hoặc token không hợp lệ/hết hạn. 403 Forbidden: người dùng không có quyền Admin. 404 Not Found: danh mục không tồn tại. 409 Conflict: tên món ăn bị trùng trong thực đơn. 500 Internal Server Error: lỗi hệ thống khi lưu món ăn. |

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
| **Requirement Description** | Cho phép Admin cập nhật thông tin món ăn.  Hệ thống kiểm tra món ăn tồn tại, kiểm tra và cập nhật dữ liệu mới.  |
| **Input** | foodId; các thông tin cần cập nhật (name, description, price, imageUrl, categoryId, status, stockQuantity). |
| **Preconditions** | Admin đã đăng nhập và có quyền quản lý món ăn. Món ăn cần thao tác phải tồn tại trong hệ thống.  |
| **Functional Requirement** | 1\. Admin gửi yêu cầu cập nhật món ăn thông qua API. 2\. Hệ thống xác thực người dùng và kiểm tra quyền Admin qua middleware/filter/interceptor. 3\. Lấy thông tin món ăn hiện tại thông qua Food Repository. 4\. Kiểm tra món ăn có tồn tại. 5\. Kiểm tra dữ liệu cập nhật hợp lệ: tên không rỗng/không trùng toàn thực đơn, price \> 0, categoryId tồn tại, status hợp lệ, stockQuantity là số nguyên không âm. 6\. Cập nhật thông tin món ăn theo dữ liệu mới; cập nhật tồn kho phải dùng cùng cơ chế khóa với checkout để tránh ghi đè khi khách đang đặt món. 7\. Lưu thay đổi thông qua Repository/Data Access Layer trong một giao dịch. 8\. Trả về thông tin món ăn sau khi cập nhật dưới dạng JSON. |
| **Nonfunctional Requirement** | 1\. API sử dụng REST/JSON 2\. Chỉ Admin có quyền cập nhật món ăn 3\. Tầng nghiệp vụ không truy cập trực tiếp Database 4\. Dữ liệu được thao tác thông qua Repository/Data Access Layer 5\. Thời gian phản hồi ≤ 2 giây trong điều kiện tải thông thường. |
| **Error Handling** | 401 nếu chưa đăng nhập; 400 nếu foodId, giá, tồn kho hoặc dữ liệu cập nhật không hợp lệ; 404 nếu món ăn hoặc danh mục không tồn tại; 403 nếu người dùng không có quyền Admin; 409 nếu tên món trùng hoặc có xung đột cập nhật; 500 nếu lỗi truy xuất dữ liệu. |

### **API:** PUT /api/foods/{foodId}

# **UC08 — Xoá món ăn**

| Requirement ID | UC08 |
| ----- | ----- |
| **Requirement Name** | Xoá món ăn |
| **Actor** | Admin |
| **Requirement Description** | Cho phép Admin xóa món ăn khỏi hệ thống hoặc ngừng bán món ăn khi món ăn không còn được cung cấp.  Hệ thống kiểm tra trạng thái món ăn trước khi thực hiện thao tác. |
| **Input** | foodId |
| **Preconditions** | Admin đã đăng nhập và có quyền quản lý món ăn. Món ăn cần thao tác phải tồn tại trong hệ thống.  |
| **Functional Requirement** | 1\. Admin gửi yêu cầu xóa/ngừng bán qua DELETE /api/foods/{foodId}. 2\. Hệ thống xác thực người dùng và kiểm tra quyền Admin qua middleware/filter/interceptor. 3\. Lấy món ăn qua Food Repository. 4\. Kiểm tra món tồn tại. 5\. Kiểm tra món có được tham chiếu bởi đơn hàng hoặc dữ liệu liên quan không. 6\. Nếu có lịch sử đơn, chuyển trạng thái thành INACTIVE; chỉ xóa vật lý khi không còn tham chiếu. 7\. Lưu thay đổi qua Repository/Data Access Layer. 8\. Trả kết quả thao tác dưới dạng JSON. |
| **Nonfunctional Requirement** | 1\. Endpoint DELETE yêu cầu xác thực và phân quyền Admin 2\. Thao tác xóa phải đảm bảo tính toàn vẹn dữ liệu. 3\. Không xóa trực tiếp dữ liệu đang được tham chiếu bởi đơn hàng  4\. Tầng nghiệp vụ độc lập với Database. |
| **Error Handling** | 401 nếu chưa đăng nhập; 400 nếu foodId không hợp lệ; 404 nếu món ăn không tồn tại; 403 nếu người dùng không có quyền Admin; 409 nếu món ăn đang được sử dụng và không thể xóa; 500 nếu lỗi cập nhật trạng thái hoặc xóa dữ liệu. |

### **API:** DELETE /api/foods/{foodId}

# **UC09 — Xem giỏ hàng**

| Requirement ID | UC09 |
| ----- | ----- |
| **Requirement Name** | Xem giỏ hàng |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng xem các món ăn hiện có trong giỏ hàng của mình.  Hệ thống lấy thông tin giỏ hàng theo người dùng đang đăng nhập và trả về danh sách món ăn cùng thông tin chi tiết. |
| **Input** | customerId lấy từ thông tin đăng nhập tùy chọn các tham số phân trang/lọc nếu có. |
| **Preconditions** | Khách hàng đã đăng nhập. Giỏ hàng của khách hàng tồn tại trong hệ thống. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu xem giỏ hàng thông qua API.  2\. Hệ thống xác thực người dùng qua middleware/filter/interceptor.  3\. Lấy thông tin giỏ hàng của khách hàng thông qua Cart Repository. 4\. Lấy danh sách các món ăn và thông tin chi tiết trong giỏ hàng.  5\. Kiểm tra trạng thái tồn tại của các món ăn trong giỏ hàng.  6\. Trả về thông tin giỏ hàng dưới dạng JSON. |
| **Nonfunctional Requirement** | 1\. Endpoint GET yêu cầu xác thực người dùng. 2\. API tuân thủ REST/JSON 3\. Khách hàng chỉ được phép xem giỏ hàng của chính mình. 4\. Dữ liệu được truy cập thông qua Repository/Data Access Layer 5\. Thời gian phản hồi ≤ 2 giây trong điều kiện tải thông thường. |
| **Error Handling** | 401 nếu chưa đăng nhập 404 nếu giỏ hàng không tồn tại 404 nếu món ăn trong giỏ hàng không tồn tại 500 nếu lỗi truy xuất dữ liệu. |

### **API:** GET /api/cart

# **UC10 — Thêm món vào giỏ** 

| Requirement ID | UC10 |
| ----- | ----- |
| **Requirement Name** | Thêm món vào giỏ hàng |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng thêm món ăn vào giỏ hàng của mình.  Hệ thống kiểm tra món ăn tồn tại, kiểm tra trạng thái bán và thêm mới hoặc cập nhật số lượng món ăn trong giỏ hàng. |
| **Input** | customerId lấy từ thông tin đăng nhập foodId số lượng món ăn cần thêm. |
| **Preconditions** | Khách hàng đã đăng nhập. Món ăn tồn tại trong hệ thống. Món ăn đang ở trạng thái cho phép bán. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu thêm món vào giỏ hàng thông qua API.  2\. Hệ thống xác thực người dùng qua middleware/filter/interceptor.  3\. Kiểm tra món ăn tồn tại và trạng thái bán thông qua Food Repository.  4\. Lấy giỏ hàng hiện tại của khách hàng thông qua Cart Repository.  5\. Kiểm tra món ăn đã tồn tại trong giỏ hàng hay chưa.  6\. Nếu món ăn đã tồn tại, cập nhật số lượng; nếu chưa tồn tại, tạo mới Cart Item.  7\. Lưu thay đổi thông qua Repository/Data Access Layer.  8\. Trả về thông tin giỏ hàng sau khi cập nhật dưới dạng JSON. |
| **Nonfunctional Requirement** | 1\. API sử dụng REST/JSON. 2\. Chỉ khách hàng đã xác thực mới được thao tác giỏ hàng. 3\. Thao tác thêm/cập nhật món phải đảm bảo tính nhất quán dữ liệu. 4\. Tầng nghiệp vụ không truy cập trực tiếp Database 5\. Dữ liệu được thao tác thông qua Repository. |
| **Error Handling** | 401 nếu khách hàng chưa đăng nhập 400 nếu số lượng món không hợp lệ 404 nếu món ăn không tồn tại 409 nếu món ăn không còn được bán 500 nếu lỗi lưu dữ liệu giỏ hàng. |

### **API:** POST /api/cart/items

# **UC11 — Cập nhật số lượng món**

| Requirement ID | UC11 |
| ----- | ----- |
| **Requirement Name** | Cập nhật số lượng  |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng cập nhật số lượng của món ăn trong giỏ hàng. Hệ thống kiểm tra món ăn tồn tại trong giỏ hàng, kiểm tra số lượng hợp lệ và cập nhật lại thông tin Cart Item. |
| **Input** | customerId lấy từ thông tin đăng nhập cartItemId  số lượng mới cần cập nhật. |
| **Preconditions** | Khách hàng đã đăng nhập Giỏ hàng tồn tại Món ăn cần cập nhật đã tồn tại trong giỏ hàng. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu cập nhật số lượng món trong giỏ hàng thông qua API.  2\. Hệ thống xác thực người dùng qua middleware/filter/interceptor.  3\. Lấy giỏ hàng của khách hàng thông qua Cart Repository.  4\. Kiểm tra món ăn cần cập nhật có tồn tại trong giỏ hàng hay không. 5\. Kiểm tra số lượng mới có hợp lệ.  6\. Cập nhật số lượng của Cart Item.  7\. Lưu thay đổi thông qua Repository/Data Access Layer.  8\. Trả về thông tin giỏ hàng sau khi cập nhật dưới dạng JSON. |
| **Nonfunctional Requirement** | 1\. Endpoint PUT yêu cầu xác thực người dùng 2\. Khách hàng chỉ được phép cập nhật giỏ hàng của chính mình 3\. Thao tác cập nhật phải đảm bảo tính nhất quán dữ liệu 4\. Tầng nghiệp vụ không truy cập trực tiếp Database 5\. Dữ liệu được thao tác thông qua Repository/Data Access Layer. |
| **Error Handling** | 401 nếu khách hàng chưa đăng nhập 400 nếu số lượng cập nhật không hợp lệ 404 nếu món ăn hoặc Cart Item không tồn tại 403 nếu khách hàng truy cập giỏ hàng không thuộc quyền sở hữu 500 nếu lỗi cập nhật dữ liệu. |

### **API:** PUT /api/cart/items/{cartItemId}

# **UC12 — Xoá món khỏi giỏ hàng**

| Requirement ID | UC12 |
| ----- | ----- |
| **Requirement Name** | Xoá món khỏi giỏ  |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng xóa một món ăn khỏi giỏ hàng của mình.  Hệ thống kiểm tra món ăn tồn tại trong giỏ hàng và thực hiện xóa Cart Item tương ứng. |
| **Input** | customerId lấy từ thông tin đăng nhập cartItemId |
| **Preconditions** | Khách hàng đã đăng nhập Giỏ hàng tồn tại Món ăn cần  đã tồn tại trong giỏ hàng. |
| **Functional Requirement** | 1\. Khách hàng gửi yêu cầu xóa món khỏi giỏ hàng thông qua API.  2\. Hệ thống xác thực người dùng qua middleware/filter/interceptor.  3\. Lấy thông tin giỏ hàng của khách hàng thông qua Cart Repository. 4\. Kiểm tra Cart Item cần xóa có tồn tại và thuộc về khách hàng hiện tại.  5\. Xóa Cart Item khỏi giỏ hàng.  6\. Lưu thay đổi thông qua Repository/Data Access Layer.  7\. Cập nhật lại tổng tiền hoặc thông tin liên quan của giỏ hàng nếu cần.  8\. Trả về thông tin giỏ hàng sau khi xóa dưới dạng JSON. |
| **Nonfunctional Requirement** | 1\. Endpoint DELETE yêu cầu xác thực 2\. Khách hàng không được phép xóa món trong giỏ hàng của người dùng khác 3\. Thao tác xóa phải đảm bảo tính toàn vẹn dữ liệu 4\. Tầng nghiệp vụ độc lập với Database 5\. Dữ liệu được truy cập thông qua Repository/Data Access Layer. |
| **Error Handling** | 401 nếu khách hàng chưa đăng nhập. 404 nếu Cart Item không tồn tại. 403 nếu Cart Item không thuộc quyền sở hữu của khách hàng. 409 nếu không thể xóa do trạng thái dữ liệu không hợp lệ. 500 nếu lỗi xóa dữ liệu. |

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
| **Functional Requirement** | 1\. Nhận yêu cầu qua POST /api/orders và xác thực khách hàng. 2\. Kiểm tra orderType và paymentMethod; DINE_IN yêu cầu tableNumber và không có trường giao hàng, DELIVERY yêu cầu đủ thông tin nhận hàng và không có tableNumber. 3\. Bắt đầu giao dịch qua UnitOfWork; lấy và khóa giỏ của khách hàng thông qua Cart Repository. 4\. Kiểm tra giỏ không rỗng; lấy và khóa các món theo thứ tự foodId thông qua Food Repository, kiểm tra trạng thái bán và tồn kho. 5\. Tính tổng tiền bằng giá trên server; tạo snapshot tên/giá món và thông tin bàn hoặc giao hàng. 6\. Tạo đơn trạng thái PENDING; lưu đơn và chi tiết qua Order Repository. 7\. Trừ tồn kho qua Food Repository, xóa các Cart Item qua Cart Repository theo cartId; commit khi tất cả thành công, rollback toàn bộ khi có lỗi. 8\. Trả 201 Created với orderType, thông tin bàn/giao hàng, chi tiết món, tổng tiền và trạng thái. |
| **Nonfunctional Requirement** | REST API sử dụng JSON; thời gian phản hồi trong điều kiện tải thông thường ≤ 2 giây; tầng nghiệp vụ không phụ thuộc framework web hoặc thư viện DB; dữ liệu được truy cập thông qua Repository/Data Access Layer. Tạo đơn, chi tiết đơn, trừ tồn kho và dọn giỏ phải nguyên tử và chống checkout đồng thời gây bán vượt tồn kho hoặc tạo hai đơn từ cùng giỏ. |
| **Error Handling** | 401 nếu chưa đăng nhập; 403 nếu không có quyền khách hàng; 400 nếu orderType/paymentMethod sai, thông tin bàn/giao hàng không hợp lệ hoặc giỏ rỗng; 404 nếu món/giỏ không tồn tại; 409 nếu món ngừng bán, không đủ tồn kho hoặc có xung đột checkout; 500 nếu lỗi hệ thống, đồng thời rollback giao dịch. |

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
| **Input** | customerId từ JWT; tùy trường hợp có orderId; page, size, status, orderType (tùy chọn). |
| **Preconditions** | Khách hàng đã đăng nhập. |
| **Functional Requirement** | 1\. Nhận GET /api/orders để lấy danh sách hoặc GET /api/orders/{orderId} để xem chi tiết. 2\. Xác thực người dùng bằng middleware/filter/interceptor. 3\. Chỉ trả các đơn thuộc khách hàng đang đăng nhập. 4\. Lấy dữ liệu thông qua Order Repository và áp dụng phân trang/bộ lọc. 5\. Trả JSON bao gồm orderType, tableNumber cho DINE_IN hoặc recipientName/shippingPhone/shippingAddress cho DELIVERY, paymentMethod, tổng tiền và trạng thái; chi tiết đơn có các snapshot tên/giá món. Các trường không phù hợp với orderType là null. |
| **Nonfunctional Requirement** | Endpoint GET yêu cầu xác thực; API tuân thủ REST; thời gian phản hồi ≤ 2 giây trong điều kiện tải thông thường; không cho phép khách hàng truy cập đơn hàng của khách hàng khác. |
| **Error Handling** | 401 nếu chưa đăng nhập; 400 nếu orderId không hợp lệ; 404 nếu đơn hàng không tồn tại; 403 nếu khách hàng cố truy cập đơn hàng không thuộc quyền sở hữu; 500 nếu lỗi truy xuất dữ liệu. |

### **API:** GET /api/orders		| 	GET /api/orders/{orderId}

### **UC15 — Hủy đơn hàng**

| Requirement ID | UC15 |
| ----- | ----- |
| **Requirement Name** | Hủy đơn hàng |
| **Actor** | Khách hàng |
| **Requirement Description** | Cho phép khách hàng hủy đơn DINE_IN hoặc DELIVERY của mình khi trạng thái còn PENDING. |
| **Input** | orderId; customerId lấy từ thông tin đăng nhập. |
| **Preconditions** | Khách hàng đã đăng nhập; đơn hàng tồn tại; đơn hàng thuộc về khách hàng đang đăng nhập. |
| **Functional Requirement** | 1\. Nhận yêu cầu hủy qua DELETE /api/orders/{orderId}. 2\. Xác thực khách hàng. 3\. Bắt đầu giao dịch và khóa đơn thông qua Order Repository; kiểm tra đơn tồn tại và thuộc khách hàng. 4\. Chỉ cho phép chuyển PENDING sang CANCELLED. 5\. Phục hồi tồn kho đã trừ đúng một lần qua Food Repository; khóa món theo thứ tự foodId. 6\. Lưu trạng thái qua Repository và commit cùng cập nhật tồn kho; rollback khi lỗi. 7\. Trả kết quả hủy dưới dạng JSON, giữ lại đơn và chi tiết làm lịch sử. |
| **Nonfunctional Requirement** | Endpoint DELETE yêu cầu xác thực; thao tác hủy phải đảm bảo dữ liệu đơn hàng không bị cập nhật sai trạng thái; tầng nghiệp vụ không truy cập trực tiếp Database. |
| **Error Handling** | 401 nếu chưa đăng nhập; 404 nếu đơn không tồn tại; 403 nếu đơn không thuộc khách hàng; 409 nếu trạng thái khác PENDING, bao gồm đơn đã hủy; 500 nếu lỗi cập nhật dữ liệu và rollback giao dịch. |

### **API:** DELETE /api/orders/{orderId}

# **UC16 — Xác nhận đơn hàng**

| Requirement ID | UC16 |
| ----- | ----- |
| **Requirement Name** | Xác nhận đơn hàng |
| **Actor** | Nhân viên |
| **Requirement Description** | Cho phép nhân viên kiểm tra và xác nhận các đơn DINE_IN/DELIVERY đang chờ xử lý; hiển thị đúng số bàn hoặc thông tin nhận hàng của từng loại đơn. |
| **Input** | orderId; employeeId lấy từ thông tin đăng nhập. |
| **Preconditions** | Nhân viên đã đăng nhập và có quyền xử lý đơn hàng; đơn hàng tồn tại; đơn hàng đang ở trạng thái PENDING. |
| **Functional Requirement** | 1\. Nhân viên gửi yêu cầu xác nhận đơn hàng. 2\. Hệ thống xác thực và kiểm tra quyền nhân viên qua middleware/filter/interceptor. 3\. Lấy đơn hàng từ Repository. 4\. Kiểm tra trạng thái đơn hàng. 5\. Kiểm tra các điều kiện cần thiết để xác nhận đơn. 6\. Chuyển trạng thái đơn hàng từ PENDING sang CONFIRMED. 7\. Lưu thay đổi thông qua Repository. 8\. Trả thông tin đơn hàng sau khi xác nhận. |
| **Nonfunctional Requirement** | API sử dụng REST/JSON; thao tác xác nhận phải đảm bảo tính nhất quán dữ liệu; tầng nghiệp vụ độc lập với framework web và Database. |
| **Error Handling** | 401 nếu chưa đăng nhập; 403 nếu người dùng không có quyền nhân viên; 404 nếu đơn hàng không tồn tại; 409 nếu đơn hàng không ở trạng thái PENDING; 500 nếu lỗi cập nhật. |

### **API:** POST /api/orders/{orderId}/confirm

# **UC17 — Cập nhật trạng thái đơn**

| Requirement ID | UC17 |
| ----- | ----- |
| **Requirement Name** | Cập nhật trạng thái đơn |
| **Actor** | Nhân viên |
| **Requirement Description** | Cho phép nhân viên thay đổi trạng thái theo quy trình riêng của DINE_IN hoặc DELIVERY và hủy đơn trong các trạng thái được phép. |
| **Input** | orderId; status (CONFIRMED, PROCESSING, SHIPPING, COMPLETED, CANCELLED); employeeId từ JWT. |
| **Preconditions** | Nhân viên đã đăng nhập và có quyền xử lý đơn hàng; đơn hàng tồn tại. |
| **Functional Requirement** | 1\. Nhân viên gửi yêu cầu cập nhật trạng thái. 2\. Middleware/filter/interceptor xác thực và kiểm tra quyền. 3\. Khóa và lấy đơn hiện tại qua Order Repository trong giao dịch. 4\. Kiểm tra orderType và chuyển trạng thái: PENDING → CONFIRMED → PROCESSING cho cả hai loại; DELIVERY tiếp tục PROCESSING → SHIPPING → COMPLETED; DINE_IN chỉ PROCESSING → COMPLETED, không cho SHIPPING. 5\. Cho phép nhân viên hủy từ PENDING, CONFIRMED hoặc PROCESSING sang CANCELLED; không hủy từ SHIPPING, COMPLETED hoặc CANCELLED. Nếu hủy, phục hồi tồn kho đúng một lần qua Food Repository trong cùng giao dịch. 6\. Lưu trạng thái và commit; rollback nếu có lỗi. 7\. Trả thông tin đơn hàng sau cập nhật. |
| **Nonfunctional Requirement** | Chỉ người dùng có quyền nhân viên mới được cập nhật trạng thái; việc chuyển trạng thái phải tuân thủ Business Rule; API sử dụng JSON và REST; thao tác cập nhật phải đảm bảo tính nhất quán dữ liệu. |
| **Error Handling** | 401 nếu chưa đăng nhập; 403 nếu không có quyền; 404 nếu đơn không tồn tại; 400 nếu status không thuộc tập giá trị hợp lệ; 409 nếu bước chuyển không hợp lệ, yêu cầu SHIPPING cho DINE_IN hoặc hủy ngoài trạng thái cho phép; 500 nếu lỗi cập nhật dữ liệu và rollback giao dịch. |

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
| **Input** | employeeId từ JWT; status, orderType (tùy chọn), page, size. |
| **Preconditions** | Nhân viên đã đăng nhập và có quyền xử lý đơn hàng. |
| **Functional Requirement** | 1\. Nhân viên gửi yêu cầu lấy danh sách đơn cần xử lý. 2\. Middleware/filter/interceptor xác thực và kiểm tra quyền. 3\. Truy vấn các đơn cần xử lý qua Order Repository; nếu không gửi status thì mặc định PENDING, CONFIRMED, PROCESSING và SHIPPING. 4\. Lọc thêm theo status và orderType; tổ hợp DINE_IN + SHIPPING trả danh sách rỗng vì loại đơn này không có trạng thái đó. 5\. Trả JSON gồm orderType, số bàn hoặc thông tin giao hàng, paymentMethod, tổng tiền và trạng thái để nhân viên xử lý đúng loại đơn. 6\. Hỗ trợ phân trang. Nhân viên có quyền xem GET /api/orders/{orderId} với cùng thông tin và các chi tiết món. |
| **Nonfunctional Requirement** | Endpoint GET yêu cầu xác thực; dữ liệu trả về chỉ chứa thông tin cần thiết cho nhân viên xử lý; thời gian phản hồi ≤ 2 giây trong điều kiện tải thông thường; truy cập Database thông qua Repository/Data Access Layer. |
| **Error Handling** | 401 nếu chưa đăng nhập; 403 nếu không có quyền nhân viên; 400 nếu tham số lọc không hợp lệ; trả danh sách rỗng nếu không có đơn cần xử lý; 500 nếu lỗi truy xuất dữ liệu. |

### **API:** GET /api/orders?status=PENDING&orderType=DINE_IN

GET /api/orders được giới hạn theo vai trò: CUSTOMER chỉ xem đơn của mình (UC14), EMPLOYEE xem các đơn cần xử lý theo bộ lọc (UC18). Quyền được lấy từ JWT, không từ tham số customerId/employeeId do client tự truyền.
