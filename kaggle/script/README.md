# 📦 Cấu Trúc Kiểm Thử Tải ByteMe Trên Kaggle

Dự án đã được phân chia rõ ràng thành 2 thư mục:

```
kaggle/
├── dataset/             <--- Thư mục dùng để upload tạo Kaggle Dataset (không commit, tạo ở bước 0)
│   ├── application.jar  (Spring Boot Java 21 build artifact)
│   ├── schema.sql       (bản sao src/main/resources/db/schema.sql)
│   └── seed.sql         (bản sao src/main/resources/db/seed.sql)
│
└── script/              <--- Các kịch bản test & notebook
    ├── byteme_kaggle_benchmark.ipynb  (Notebook: cài MySQL/Java/k6, chạy server, chạy k6, vẽ biểu đồ)
    └── README.md                      (File hướng dẫn này)
```

---

## 🚀 Hướng Dẫn Sử Dụng Nhanh

### 0. Chuẩn bị thư mục dataset
Các file trong `kaggle/dataset/` không được commit. Build jar từ code hiện tại rồi copy cùng schema/seed:

```sh
./mvnw -DskipTests package
mkdir -p kaggle/dataset
cp target/application.jar src/main/resources/db/schema.sql src/main/resources/db/seed.sql kaggle/dataset/
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
