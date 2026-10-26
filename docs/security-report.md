# ByteMe — Báo cáo đánh giá bảo mật backend

**Ngày lập:** 26/10/2026  
**Ngày kiểm thử:** 25/10/2026  
**Phạm vi báo cáo:** Hai đợt ZAP backend và một đợt kiểm tra trực tiếp quyền sở hữu dữ liệu (BOLA). Không bao gồm Kaggle/hiệu năng.

## 1. Tóm tắt

ByteMe đã được kiểm tra API công khai, API sau đăng nhập và quyền truy cập dữ liệu theo vai trò/chủ sở hữu (BOLA). Cả ba đợt ghi nhận hoàn tất, chi tiết thao tác từng track được lưu trong `RUN.json`.

Các cảnh báo đáng chú ý liên quan đến thư viện JavaScript và cấu hình bảo vệ Swagger UI, cùng dữ liệu đầu vào được phản chiếu trong JSON. Kết quả hiện có là report bởi tool (ZAP), chưa xác nhận khai thác thành công các cảnh báo này. Các thông báo nhận diện đăng nhập/token là thông tin của công cụ (ZAP), không phải bằng chứng rò rỉ thông tin đăng nhập.

Kiểm tra quyền sở hữu không ghi nhận sai lệch trong những tình huống đã thực hiện. Kết luận này dựa trên quyền kỳ vọng lấy từ README, OpenAPI và mã nguồn.

**Kết luận:** Đã có kết quả đánh giá backend trong phạm vi đã thực hiện; còn các mục cần xác minh tác động và tăng cường bảo vệ. Chưa đủ cơ sở dùng kết quả này để chứng nhận toàn bộ sản phẩm an toàn.

**Ưu tiên tiếp theo:** xác minh cảnh báo thư viện; kiểm tra nơi giao diện sử dụng dữ liệu được phản chiếu; xử lý CSP cho Swagger nếu trang này tiếp tục được cung cấp; xác nhận chính sách phân quyền với người phụ trách sản phẩm.

## 2. Căn cứ, môi trường và phạm vi thực hiện

### 2.1. Phiên bản và môi trường được kiểm

- Sản phẩm: backend REST/JSON ByteMe, nghiệp vụ đặt món cho một nhà hàng.
- Source HEAD được ghi trong cả ba đợt: `e163fbbf74ee92413b99eda54a33a06d933d12ec`; source được ghi nhận không có thay đổi chưa commit tại thời điểm chạy.
- Môi trường: Docker Compose; địa chỉ phía máy chạy là `http://127.0.0.1:8081`, phía scanner là `http://172.20.0.3:8080`.
- Hai đợt ZAP ghi nhận ZAP 2.17.0. Đợt anonymous dùng MCP runtime 0.16.0; đợt authenticated ghi nhận candidate JAR với schema hỗ trợ method/postData/authSessionId đã được kiểm tra, không ghi số phiên bản MCP cụ thể.
- Runtime OpenAPI được ghi nhận khớp source. 
- Thời gian bên dưới dùng giờ UTC+7. Kết luận áp dụng cho môi trường và lần chạy đã lưu.

### 2.2. Backend chưa đăng nhập — BE anonymous

Kiểm tra 5 thao tác nghiệp vụ công khai: đăng ký, cấp token, danh sách món, tìm kiếm món và chi tiết món; đồng thời kiểm tra từ chối truy cập với 13 thao tác cần đăng nhập.

Kỹ thuật gồm ZAP active scan theo đầu vào phù hợp, quan sát passive, thay đổi đầu vào và đối chiếu hành vi/kết quả. Báo cáo chạy ghi nhận 16 active scans kết thúc và 310 request ứng dụng được ghi log. Các con số này mô tả lượt chạy, không phải số lỗ hổng hoặc số test case độc lập.

**Trạng thái theo nguồn:** hoàn tất.

### 2.3. Backend đã đăng nhập — BE authenticated

Kiểm tra 13 thao tác được bảo vệ, 15 tổ hợp thao tác–vai trò được phép với CUSTOMER, EMPLOYEE, ADMIN; bao gồm nhánh đặt tại chỗ (`DINE_IN`) và giao hàng (`DELIVERY`).

Kỹ thuật gồm ZAP active/passive với phiên đăng nhập thật, thay đổi đầu vào và đọc lại dữ liệu sau thao tác. Báo cáo chạy ghi nhận 19 native active scans kết thúc; đã đánh giá 46 trách nhiệm kiểm tra đầu vào và 34 câu hỏi kỹ thuật. Đây là đơn vị đối soát riêng của đợt chạy, không quy đổi thành số API hay số lỗ hổng.

**Trạng thái theo nguồn:** hoàn tất.

### 2.4. Vai trò và quyền sở hữu — BOLA ownership

BOLA là trường hợp một người truy cập hoặc thay đổi đối tượng thuộc người khác khi không có quyền, ví dụ khách A hủy đơn của khách B bằng cách thay ID đơn.

Đợt này đối soát 18 thao tác API × 4 trạng thái/vai trò (anonymous, CUSTOMER, EMPLOYEE, ADMIN), tương ứng 72 tổ hợp thao tác–vai trò. Bao gồm từ chối theo vai trò, thay chủ sở hữu/ID, can thiệp trường chủ sở hữu, kiểm tra danh sách có phân trang/bộ lọc và đọc lại trạng thái liên quan.

**Kỹ thuật:** gửi request kiểm tra trực tiếp và đối chiếu kết quả; không chạy ZAP scan trong đợt này. Không phải cả 72 tổ hợp đều là phép thử truy cập chéo chủ sở hữu; các tổ hợp còn có kiểm tra quyền chức năng và API công khai.

**Trạng thái theo nguồn:** hoàn tất phạm vi; không ghi nhận finding về sai lệch quyền trong các trường hợp đã thực hiện.

## 3. Cách đọc kết quả

- **Severity:** mức cảnh báo do ZAP gán. Báo cáo giữ nguyên phân loại trong nguồn.
- **Confidence:** độ tin cậy công cụ gán cho cảnh báo; không đồng nghĩa mức ảnh hưởng kinh doanh.
- **Nhóm cảnh báo:** nhóm theo quy tắc của từng đợt. Đợt anonymous có xét cả confidence; đợt authenticated nhóm theo plugin/tên/mức rủi ro. Không cộng hai số nhóm thành số lỗ hổng độc lập.
- **Instance:** một lần ghi nhận cảnh báo. Nhiều instance có thể cùng một nguyên nhân.
- **Ưu tiên xử lý:** đề xuất của tester dựa trên bối cảnh hiện có; chưa phải hạn xử lý hay quyết định chấp nhận rủi ro đã được sản phẩm phê duyệt.

Số liệu giữ riêng theo đợt để tránh đếm trùng:

- **Anonymous:** 9 nhóm, 105 instances; 2 nhóm Medium, 3 Low, 4 Informational. Theo instances: 3 Medium, 68 Low, 34 Informational.
- **Authenticated:** 3 nhóm, 13 instances; 1 nhóm Low với 8 instances và 2 nhóm Informational với tổng 5 instances.
- **BOLA trực tiếp:** 0 nhóm finding, 0 instances.

Không có nhóm High/Critical được ghi nhận trong các file kết quả này. Điều đó không chứng minh hệ thống không tồn tại lỗi ở các mức trên.

## 4. Đánh giá các cảnh báo cần xem xét

### SEC-01 — Thư viện JavaScript trong Swagger bị ZAP cảnh báo

- **Nguồn:** anonymous, plugin `10003` — Vulnerable JS Library; 1 instance.
- **Phân loại gốc:** Medium; confidence Medium.
- **Vị trí:** `GET /swagger-ui/swagger-ui-bundle.js`.
- **Quan sát đã lưu:** dấu vết `DOMPurify.version="3.3.2"` trong bundle được cung cấp.

**Đánh giá:** Đã có nhận diện phiên bản thư viện. Việc ánh xạ advisory do scanner cung cấp chưa được kiểm chứng độc lập; chưa kiểm tra điều kiện khai thác có tồn tại trong cách ByteMe sử dụng Swagger hay không. Không kết luận có CVE khai thác được chỉ từ dấu vết phiên bản.

**Ảnh hưởng cần xác minh:** Nếu phiên bản thực tế thuộc advisory có liên quan và Swagger dùng chức năng bị ảnh hưởng với dữ liệu có thể bị kiểm soát, người truy cập trang tài liệu có thể gặp rủi ro tương ứng. Dữ liệu hiện tại chưa xác định được tác động cụ thể.

**Đề xuất — xác minh sớm:** Đối chiếu phiên bản bundle/dependency và advisory chính thức; kiểm tra phạm vi ảnh hưởng, khả năng truy cập Swagger ở môi trường dự kiến triển khai; cập nhật dependency phân phối Swagger nếu xác nhận cần thiết.

**Điều kiện kiểm thử lại:** Ghi nhận phiên bản thực sự được phục vụ, chạy lại kiểm tra thư viện trên bundle mới và kiểm tra Swagger vẫn hoạt động. Nếu giữ phiên bản cũ, ghi rõ căn cứ advisory không áp dụng hoặc quyết định chấp nhận rủi ro.

**Trạng thái:** Chờ xác minh tác động; chưa có kết quả sửa/kiểm thử lại trong nguồn.

### SEC-02 — Swagger UI thiếu Content Security Policy

- **Nguồn:** anonymous, plugin `10038`; 2 instances.
- **Phân loại gốc:** Medium; confidence High.
- **Vị trí:** `GET /swagger-ui/index.html` và `GET /swagger-ui/oauth2-redirect.html`.
- **Quan sát đã lưu:** response HTML không có header Content-Security-Policy (CSP).

**Đánh giá:** Đã ghi nhận thiếu lớp bảo vệ CSP trên trang tài liệu. CSP giới hạn những nguồn nội dung mà trình duyệt được phép tải/chạy. Thiếu CSP không tự chứng minh có XSS hoặc đã mất dữ liệu; finding này áp dụng cho Swagger HTML, không suy rộng thành mọi API JSON đều cần cùng một chính sách.

**Đề xuất — tăng cường bảo vệ:** Nếu tiếp tục cung cấp Swagger, xây dựng CSP phù hợp với tài nguyên và chức năng trang. Người phụ trách triển khai cần xác định Swagger có được công khai trong môi trường thực tế hay không.

**Điều kiện kiểm thử lại:** Hai response HTML có CSP phù hợp; trang tải bình thường, hiển thị tài liệu và thao tác gọi API được phép vẫn hoạt động; kiểm tra các vi phạm CSP trên trình duyệt.

**Trạng thái:** Chờ xử lý cấu hình; chưa có kết quả sửa/kiểm thử lại trong nguồn.

### SEC-03 — Đầu vào được phản chiếu trong JSON, chưa xác nhận XSS

- **Nguồn:** plugin `40012` — Cross Site Scripting Weakness (Reflected in JSON Response).
- **Phân loại gốc:** Low; confidence Low ở cả hai đợt.
- **Số ghi nhận:** 1 instance anonymous; 8 instances authenticated. Đây là cùng loại cảnh báo ở nhiều trường/vị trí, không phải 9 XSS độc lập đã xác nhận.
- **Vị trí anonymous:** `POST /api/auth/register`, trường `fullName`.
- **Vị trí authenticated:** `POST/PUT /api/foods[/{id}]`, trường `description`, `imageUrl`; `POST /api/orders`, trường `note`, `recipientName`, `shippingAddress`. Các bản ghi cụ thể có trong ALERTS tương ứng.

**Đánh giá:** ZAP ghi nhận dữ liệu kiểm thử được phản chiếu trong JSON. Đợt kiểm backend không chạy trình duyệt để chứng minh JavaScript thực thi, cũng chưa xác minh stored/DOM XSS. Việc trả lại chuỗi dưới dạng dữ liệu JSON có thể là hành vi hợp lệ.

**Ảnh hưởng cần xác minh:** Nếu một giao diện dùng dữ liệu này để tạo HTML hoặc URL không an toàn, cảnh báo có thể dẫn tới XSS ở nơi hiển thị. Chưa xác nhận điều kiện đó trong đợt chạy hiện có.

**Đề xuất — kiểm tra tiếp nơi sử dụng dữ liệu:** Xác định màn hình hiển thị tên khách, thông tin món và thông tin giao hàng/ghi chú; kiểm tra cách đưa dữ liệu vào text, HTML, thuộc tính và URL. Xử lý an toàn theo đúng ngữ cảnh; không mặc định xóa mọi ký tự đặc biệt ở API vì có thể làm hỏng dữ liệu hợp lệ.

**Điều kiện kiểm thử lại:** Trên các màn hình thực sự dùng trường liên quan, dữ liệu kiểm thử hiển thị như dữ liệu và không thực thi mã; liên kết/ảnh tuân thủ chính sách URL của sản phẩm. Nếu không có nơi hiển thị nguy hiểm, ghi rõ căn cứ kết luận chưa xác nhận XSS thay vì yêu cầu scanner bắt buộc hết cảnh báo phản chiếu.

**Trạng thái:** Chờ kiểm tra giao diện/consumer; chưa xác nhận khai thác.

### SEC-04 — Các chuỗi số trong Swagger bị nhận diện là timestamp

- **Nguồn:** anonymous, plugin `10096` — Timestamp Disclosure - Unix; 66 instances.
- **Phân loại gốc:** Low; confidence Low.
- **Vị trí:** các file JavaScript bundle/preset của Swagger.

**Đánh giá:** Các số khớp mẫu timestamp nằm trong JavaScript. Chưa có bằng chứng chúng là thời gian nhạy cảm của tài khoản/phiên hoặc tiết lộ hoạt động người dùng. Đây là phần lớn số instance Low của đợt anonymous, nhưng số lượng lớn không làm tăng mức xác nhận tác động.

**Đề xuất — ưu tiên thấp:** Kiểm tra ngữ cảnh các giá trị trước khi mở việc sửa. Nếu là hằng số của thư viện không nhạy cảm, ghi nhận cảnh báo không có tác động bảo mật theo ngữ cảnh; không sửa/minify lại bundle chỉ để giảm số cảnh báo.

**Điều kiện đóng mục:** Có căn cứ phân biệt hằng số vô hại với timestamp nhạy cảm; nếu phát hiện dữ liệu nhạy cảm thật thì xử lý nguồn phát sinh và kiểm tra lại.

**Trạng thái:** Chưa xác nhận tiết lộ dữ liệu nhạy cảm; chờ phân loại cuối.

### SEC-05 — Response lỗi chung bị ZAP gắn cảnh báo disclosure

- **Nguồn:** anonymous, plugin `90022` — Application Error Disclosure; 1 instance.
- **Phân loại gốc:** Low; confidence Medium.
- **Vị trí:** `GET /error`.
- **Quan sát đã lưu:** HTTP 500, JSON chung có `status: 999`, `error: None`; phần khớp cảnh báo là dòng HTTP status. Phần response đã kiểm tra không chứa stack trace, đường dẫn file hoặc secret.

**Đánh giá:** Chưa xác nhận rò rỉ thông tin nhạy cảm. Response lỗi có biểu diễn cần làm rõ, nhưng không nên coi riêng HTTP 500 là bằng chứng lộ chi tiết nội bộ.

**Đề xuất — ưu tiên thấp:** Rà soát cách chuẩn hóa HTTP status và body lỗi; bảo đảm lỗi ứng dụng trả thông báo phù hợp cho client và không đưa chi tiết nội bộ vào response.

**Điều kiện kiểm thử lại:** HTTP status/body tuân thủ hợp đồng lỗi của sản phẩm; các tình huống lỗi đại diện không trả stack trace, đường dẫn hoặc secret.

**Trạng thái:** Chưa xác nhận disclosure nhạy cảm; chờ rà soát xử lý lỗi.

## 5. Các thông báo Informational và kết quả phân quyền

### 5.1. Thông báo Informational

Các nhóm sau được giữ trong nguồn nhưng không được xem là lỗ hổng đã xác nhận:

- `10104` — User Agent Fuzzer: anonymous, 24 instances; quan sát trên các route tài liệu/lỗi, chưa xác lập tác động bảo mật.
- `10109` — Modern Web Application: anonymous, 2 instances; nhận diện Swagger UI.
- `10111` — Authentication Request Identified: anonymous 5 instances, authenticated 3; nhận diện request đăng nhập.
- `10112` — Session Management Response Identified: anonymous 3 instances, authenticated 2; nhận diện trường token trong response.

Token được trả về khi đăng nhập bằng thông tin hợp lệ là hành vi mong đợi. Những thông báo này không tự chứng minh token bị lộ cho người không có quyền và không cần mở ticket sửa chỉ để xóa cảnh báo.

### 5.2. Kết quả BOLA ownership

Đợt kiểm tra trực tiếp không ghi nhận sai lệch so với chính sách đang được mô tả. Đây là kết quả tích cực trong phạm vi đã kiểm, không phải một finding để sửa.

Giá trị sử dụng của JWT chỉ tồn tại trong TTL cấp phép đã được xác minh thủ công.

## 6. Việc cần làm và cách theo dõi ngay trong báo cáo

Danh sách này là đề xuất xử lý sau đánh giá, chưa có người nhận việc hoặc hạn hoàn thành được thống nhất:

1. **SEC-01:** Backend/ người quản lý dependency xác minh advisory và phiên bản Swagger được phục vụ; cập nhật khi có căn cứ. Trạng thái: chờ xác minh.
2. **SEC-03:** Người phụ trách giao diện phối hợp tester kiểm tra các nơi dùng dữ liệu liên quan, ưu tiên màn hình nhân viên đọc đơn. Trạng thái: chờ kiểm tra consumer.
3. **SEC-02:** Backend/ người triển khai xác định chính sách cung cấp Swagger và cấu hình CSP phù hợp. Trạng thái: chờ xử lý.
4. **Chính sách quyền:** Người phụ trách sản phẩm xác nhận quyền kỳ vọng cho ba vai trò; tester đối chiếu lại khi có thay đổi. Trạng thái: chờ xác nhận nghiệp vụ.
5. **SEC-04, SEC-05:** Backend/tester rà soát ngữ cảnh và chuẩn hóa lỗi nếu cần. Trạng thái: chờ phân loại cuối.

Khi xử lý một mục, cập nhật tại chính mục đó: người phụ trách, thay đổi hoặc căn cứ không cần sửa, phiên bản được kiểm lại, ngày kiểm lại, kết quả thực tế và rủi ro còn lại. Chỉ chuyển sang **đã xác minh** khi có kiểm thử lại hoặc căn cứ đóng mục rõ ràng. Hiện chưa có mục nào được báo cáo này xác nhận là đã sửa.

## 7. Giới hạn và điều kiện sử dụng kết luận

- Đây là đánh giá hữu hạn theo API/đầu vào/trạng thái và kỹ thuật được chọn; không bao phủ mọi payload hoặc mọi đường khai thác.
- Các probe bị cơ chế giới hạn phạm vi chặn không được tính là đã tới ứng dụng. Đầu vào số/enum bị parser/validation từ chối không được coi là đã đi tới truy vấn DB hoặc xử lý chuyển trạng thái.
- Không có kiểm thử thực thi trên trình duyệt, xác nhận stored/DOM XSS hoặc đánh giá đầy đủ vòng đời hết hạn/thu hồi token. Không có kết luận về hiệu năng trong báo cáo này.
- Chính sách quyền dựa trên README/OpenAPI/source, chưa được nghiệp vụ xác nhận độc lập. Kiểm tra danh sách đơn của nhân viên đối chiếu các trang trả về và dữ liệu thử của hai chủ sở hữu; không đối chiếu độc lập toàn bộ đơn lịch sử trong DB.
- Không có xác nhận độc lập binary–source chính xác; không tự áp dụng kết quả của commit đã kiểm cho HEAD mới.
- Có dữ liệu thử được giữ lại: đợt anonymous tạo 41 tài khoản; đợt authenticated giữ các đơn ở CANCELLED và món ở INACTIVE theo thông tin cleanup. Không xem môi trường đã được khôi phục về trạng thái trước kiểm thử. Nếu tái sử dụng, người vận hành cần tính đến dữ liệu này.
- Các bước kiểm thử lại ở trên là đề xuất tương lai. Việc cả ba RUN ghi `complete` phản ánh phạm vi chạy đã hoàn tất, không có nghĩa mọi cảnh báo đã được xử lý.

## 8. Nguồn kết quả và hướng dẫn tra cứu

Mỗi cặp file có vai trò khác nhau: **ALERTS** chứa cảnh báo và diễn giải; **RUN** chứa phạm vi, thao tác, đối soát và giới hạn.

- Anonymous: [ALERTS.json](../zap/be-anonymous/ALERTS.json), [RUN.json](../zap/be-anonymous/RUN.json).
- Authenticated: [ALERTS.json](../zap/be-authenticated/ALERTS.json), [RUN.json](../zap/be-authenticated/RUN.json).
- BOLA ownership: [ALERTS.json](../zap/bola-ownership/ALERTS.json), [RUN.json](../zap/bola-ownership/RUN.json).

Trong ALERTS, tìm theo `pluginId` của từng mục SEC. Trong RUN, xem `scope`, `coverage`, `operations`, `limitations` và `taskCompletion`. File RUN lớn có thể cần mở bằng Raw/Download trên GitHub.
