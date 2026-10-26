# Kết quả kiểm thử tải phase 1 (baseline)

Chạy ngày 26/10/2026 trên Kaggle CPU (notebook `maal06/byteme`, phiên bản 3, dataset `maal06/byteme-server` version 2).
Jar build từ commit `ed8be73`, schema phase 1 (không có cột `version`, CHECK, view và index tối ưu).

## Cấu hình

- Kaggle CPU: 4 vCPU, 30 GB RAM, Internet ON. MySQL 8 cùng máy với ứng dụng.
- JVM: `-Xms2g -Xmx6g`; Hikari pool tối đa 50; Tomcat tối đa 200 thread.
- k6 chạy 4 stage: 30s lên 50 VUs, 1m lên 200 VUs, 1m lên 500 VUs, 30s về 0.
- Mỗi vòng lặp gọi: `GET /api/foods`, `GET /api/foods/search`, `GET /api/cart` (có token) và `POST /api/auth/token`.
- Ngưỡng: `p(95) < 500ms`, `p(99) < 1200ms`, `http_req_failed < 1%`.

## Kết quả

| Chỉ số | Giá trị |
|---|---|
| Tổng requests | 18.029 |
| Request lỗi | 0% |
| Checks đạt | 100% (18.028/18.028) |
| Throughput trung bình | khoảng 99 RPS (đỉnh 162 RPS) |
| P50 | 1,16 s |
| P90 | 7,33 s |
| P95 | 8,56 s (ngưỡng 500 ms, không đạt) |
| P99 | 10,02 s (ngưỡng 1.200 ms, không đạt) |
| Max | 11,85 s |
| Server khởi động | 15,9 s |

Log server không có dòng ERROR hay exception nào.

## Nhận xét

- Độ trễ tăng gần tuyến tính theo số VUs: khoảng 100 ms lúc đầu, vượt 500 ms khi tải bắt đầu tăng, 8–11,8 s ở giai đoạn 500 VUs.
- Throughput bão hòa quanh 100–140 RPS rồi giảm về 40–60 RPS ở cuối, nên tăng thêm VUs chỉ làm request xếp hàng lâu hơn.
- Server chậm nhưng không lỗi. Chưa xác định được nút thắt cụ thể (query, pool kết nối hay CPU) vì mới chỉ đo từ phía k6.

Đây là mốc "trước cải tiến" để so sánh với phase 2.

## File

- `load_test_report.png`: biểu đồ latency và RPS theo thời gian.
- `k6-summary.txt`: bản tóm tắt cuối của k6.
- `server.log`: log Spring Boot trong lần chạy.
- File thô `k6_results.csv` không lưu ở đây; lấy ở tab Output của notebook trên Kaggle nếu cần.
