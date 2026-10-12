import http from 'k6/http';
import { check, sleep, group } from 'k6';

// Cấu hình các giai đoạn tải
export const options = {
  stages: [
    { duration: '30s', target: 50 },   // Warm-up lên 50 user đồng thời
    { duration: '1m',  target: 200 },  // Duy trì 200 user
    { duration: '1m',  target: 500 },  // Đẩy đỉnh tải lên 500 user
    { duration: '30s', target: 0 },    // Hạ tải về 0
  ],
  thresholds: {
    // 95% request phải phản hồi dưới 500ms, 99% dưới 1200ms
    http_req_duration: ['p(95)<500', 'p(99)<1200'],
    // Tỉ lệ lỗi tổng thể dưới 1%
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = 'http://localhost:8080';

// Chuẩn bị token đăng nhập trước cho các Virtual Users (Setup function chạy 1 lần ở đầu)
export function setup() {
  const loginPayload = JSON.stringify({
    email: 'an.customer@byteme.example',
    password: 'ByteMeDemo!2026',
  });

  const res = http.post(`${BASE_URL}/api/auth/token`, loginPayload, {
    headers: { 'Content-Type': 'application/json' },
  });

  let token = null;
  if (res.status === 200) {
    const data = JSON.parse(res.body);
    token = data.accessToken;
    console.log('Setup successfully retrieved auth token!');
  } else {
    console.error(`Setup login failed with status: ${res.status}`);
  }

  return { token: token };
}

export default function (data) {
  // 1. Nhóm API Public: Tra cứu món ăn & Danh mục (Không cần Auth)
  group('Public Endpoints (Catalog & Search)', function () {
    // Lấy danh sách thực đơn
    const resFoods = http.get(`${BASE_URL}/api/foods`);
    check(resFoods, {
      'GET /api/foods status is 200': (r) => r.status === 200,
      'foods list is non-empty': (r) => {
        try {
          return JSON.parse(r.body).length > 0;
        } catch (_) {
          return false;
        }
      },
    });

    // Tìm kiếm món ăn
    const resSearch = http.get(`${BASE_URL}/api/foods/search?q=ph%E1%BB%9F`);
    check(resSearch, {
      'GET /api/foods/search status is 200': (r) => r.status === 200,
    });
  });

  // 2. Nhóm API Authenticated: Giỏ hàng (Cần Bearer Token)
  if (data.token) {
    group('Protected Endpoints (Cart)', function () {
      const authHeaders = {
        headers: {
          'Authorization': `Bearer ${data.token}`,
          'Content-Type': 'application/json',
        },
      };

      const resCart = http.get(`${BASE_URL}/api/cart`, authHeaders);
      check(resCart, {
        'GET /api/cart status is 200': (r) => r.status === 200,
      });
    });
  }

  // 3. Nhóm Authentication: Đăng nhập cấp Token
  group('Auth Token Generation', function () {
    const loginPayload = JSON.stringify({
      email: 'binh.customer@byteme.example',
      password: 'ByteMeDemo!2026',
    });

    const resToken = http.post(`${BASE_URL}/api/auth/token`, loginPayload, {
      headers: { 'Content-Type': 'application/json' },
    });

    check(resToken, {
      'POST /api/auth/token status is 200': (r) => r.status === 200,
    });
  });

  sleep(0.1); // Giả lập độ trễ thao tác giữa các request của người dùng (100ms)
}
