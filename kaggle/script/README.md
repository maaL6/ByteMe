# 📦 Cấu Trúc Kiểm Thử Tải ByteMe Trên Kaggle

Dự án đã được phân chia rõ ràng thành 2 thư mục:

```
kaggle/
├── dataset/             <--- Thư mục dùng để upload tạo Kaggle Dataset
│   ├── application.jar  (Spring Boot Java 21 build artifact)
│   ├── schema.sql       (Cấu trúc bảng DB)
│   └── seed.sql         (Dữ liệu mẫu: món ăn, tài khoản test)
│
└── script/              <--- Các kịch bản test & notebook
    ├── byteme_kaggle_benchmark.ipynb  (Jupyter Notebook chạy trực tiếp trên Kaggle)
    ├── setup_and_run.sh               (Bash script tự động cài DB, java, k6, start server)
    ├── load_test_k6.js                (Kịch bản test tải k6)
    └── README.md                      (File hướng dẫn này)
```

---

## 🚀 Hướng Dẫn Sử Dụng Nhanh

### 0. Build `application.jar`
File jar không được commit vào git. Build từ code hiện tại rồi copy vào thư mục dataset:

```sh
./mvnw -DskipTests package
cp target/application.jar kaggle/dataset/application.jar
```

### 1. Upload Dataset lên Kaggle
1. Vào [Kaggle Datasets](https://www.kaggle.com/datasets) ➔ Nhấn **New Dataset**.
2. Đặt tên Dataset: `byteme-dataset`.
3. Kéo thả toàn bộ các file trong thư mục `kaggle/dataset` (hoặc nén thư mục `kaggle/dataset` lại rồi kéo thả lên).
4. Nhấn **Create**.

### 2. Tạo Notebook và Chạy
1. Vào Kaggle Notebooks ➔ Nhấn **New Notebook** (hoặc chọn **File ➔ Upload Notebook** và chọn file `kaggle/script/byteme_kaggle_benchmark.ipynb`).
2. Ở cột **Notebook Settings** (bên phải):
   * **Accelerator**: Chọn **None** (CPU để có 4 vCPUs và 30 GB RAM).
   * **Internet**: Bật **ON** (bắt buộc).
3. Nhấn **+ Add Input** ở góc trên bên phải ➔ Chọn tab **Your Datasets** ➔ Add dataset `byteme-dataset` vừa tạo.
4. Bấm **Run All** để chạy toàn bộ quá trình kiểm thử tải và xem biểu đồ kết quả.
