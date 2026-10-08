"""Mock local UC03–18; JWT local được xác minh, nghiệp vụ/dữ liệu vẫn giả lập."""

import re
from copy import deepcopy
from typing import Annotated, Literal

from fastapi import Depends, FastAPI, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import HTTPBearer
from pydantic import BaseModel, ConfigDict, Field
from starlette.exceptions import HTTPException as StarletteHTTPException
from jwt_auth import MODE, verify

app = FastAPI(
    title="SA — Mock Food / Cart / Order",
    version="0.1.0",
    description="Mock học tập UC03–18; dữ liệu in-memory, schema minh họa chưa Approved. "
    "Mặc định xác minh JWT RS256 từ issuer bên ngoài theo public key được cấu hình. "
    "Fixture token chỉ dùng khi MOCK_AUTH_MODE=fixture. Khởi động lại server để reset dữ liệu.",
)
bearer = HTTPBearer(auto_error=False, description="Nhập JWT local do Java cấp; không dùng token production.")
protected = [Depends(bearer)]  # Hiển thị nút Authorize; middleware kiểm tra token/role.
TOKENS = {
    "mock-customer-a": {"userId": "customer-a", "role": "CUSTOMER"},
    "mock-customer-b": {"userId": "customer-b", "role": "CUSTOMER"},
    "mock-staff": {"userId": "staff-a", "role": "EMPLOYEE"},
    "mock-admin": {"userId": "admin-a", "role": "ADMIN"},
}
RULES = [
    ("POST", r"/api/foods", {"ADMIN"}),
    ("PUT", r"/api/foods/[^/]+", {"ADMIN"}),
    ("DELETE", r"/api/foods/[^/]+", {"ADMIN"}),
    ("GET", r"/api/cart", {"CUSTOMER"}),
    ("POST", r"/api/cart/items", {"CUSTOMER"}),
    ("PUT", r"/api/cart/items/[^/]+", {"CUSTOMER"}),
    ("DELETE", r"/api/cart/items/[^/]+", {"CUSTOMER"}),
    ("POST", r"/api/orders", {"CUSTOMER"}),
    ("GET", r"/api/orders", {"CUSTOMER", "EMPLOYEE"}),
    ("GET", r"/api/orders/[^/]+", {"CUSTOMER"}),
    ("DELETE", r"/api/orders/[^/]+", {"CUSTOMER"}),
    ("POST", r"/api/orders/[^/]+/confirm", {"EMPLOYEE"}),
    ("PUT", r"/api/orders/[^/]+/status", {"EMPLOYEE"}),
]
OrderStatus = Literal["PENDING", "CONFIRMED", "PROCESSING", "SHIPPING", "COMPLETED", "CANCELLED"]
TRANSITIONS = {
    "PENDING": {"CONFIRMED", "CANCELLED"},
    "CONFIRMED": {"PROCESSING", "CANCELLED"},
    "PROCESSING": {"SHIPPING"},
    "SHIPPING": {"COMPLETED"},
    "COMPLETED": set(), "CANCELLED": set(),
}


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class FoodInput(Input):
    name: str = Field(min_length=1, max_length=100)
    description: str = Field(default="", max_length=1000)
    price: int = Field(gt=0, strict=True, description="Giá nguyên VND — giả định riêng của mock.")
    imageUrl: str | None = None
    restaurantId: str = Field(default="restaurant-demo", min_length=1)
    categoryId: str = Field(default="category-demo", min_length=1)
    status: Literal["ACTIVE", "INACTIVE"] = "ACTIVE"


class AddItem(Input):
    foodId: str = Field(min_length=1)
    quantity: int = Field(gt=0, le=100, strict=True)


class QuantityInput(Input):
    quantity: int = Field(gt=0, le=100, strict=True)


class OrderInput(Input):
    deliveryAddress: str = Field(min_length=1, max_length=300)
    phone: str = Field(min_length=1, max_length=30)
    paymentMethod: Literal["COD"] = "COD"


class StatusInput(Input):
    status: OrderStatus


foods = {
    "food-1": {"foodId": "food-1", **FoodInput(name="Burger bò", price=45000).model_dump()},
    "food-2": {"foodId": "food-2", **FoodInput(name="Khoai tây chiên", price=25000).model_dump()},
    "food-3": {"foodId": "food-3", **FoodInput(name="Món ngừng bán", price=30000, status="INACTIVE").model_dump()},
}
carts = {
    "customer-a": [{"cartItemId": "item-a-1", "foodId": "food-1", "quantity": 1}],
    "customer-b": [{"cartItemId": "item-b-1", "foodId": "food-2", "quantity": 2}],
}
orders = {
    "order-a-1": {"orderId": "order-a-1", "customerId": "customer-a", "status": "PENDING",
                  "items": [{"foodId": "food-1", "name": "Burger bò", "unitPrice": 45000, "quantity": 1}],
                  "total": 45000, "deliveryAddress": "Địa chỉ tổng hợp A", "phone": "+84900000001", "paymentMethod": "COD"},
    "order-b-1": {"orderId": "order-b-1", "customerId": "customer-b", "status": "PENDING",
                  "items": [{"foodId": "food-2", "name": "Khoai tây chiên", "unitPrice": 25000, "quantity": 2}],
                  "total": 50000, "deliveryAddress": "Địa chỉ tổng hợp B", "phone": "+84900000002", "paymentMethod": "COD"},
}
counter = 0


def new_id(prefix):
    global counter
    counter += 1
    return f"{prefix}-mock-{counter}"


def error_response(request, status, code, message, field_errors=None, headers=None):
    return JSONResponse(status_code=status, headers=headers, content={
        "status": status, "code": code, "message": message,
        "path": request.url.path, "fieldErrors": field_errors or [],
    })


def fail(status, code, message):
    raise HTTPException(status, {"code": code, "message": message})


@app.middleware("http")
async def mock_security(request: Request, call_next):
    path = request.url.path.rstrip("/") or "/"
    roles = next((roles for method, pattern, roles in RULES
                  if method == request.method and re.fullmatch(pattern, path)), None)
    if roles is not None:
        scheme, _, token = request.headers.get("Authorization", "").partition(" ")
        principal = None
        if scheme.lower() == "bearer":
            principal = TOKENS.get(token) if MODE == "fixture" else verify(token)
        if principal is None:
            response = error_response(request, 401, "UNAUTHENTICATED", "Cần Bearer token hợp lệ theo chế độ đang chạy.",
                                      headers={"WWW-Authenticate": "Bearer"})
            response.headers["X-Mock-API"] = "true"
            return response
        if principal["role"] not in roles:
            response = error_response(request, 403, "FORBIDDEN", "Role không có quyền gọi API này.")
            response.headers["X-Mock-API"] = "true"
            return response
        request.state.principal = principal
    response = await call_next(request)
    response.headers["X-Mock-API"] = "true"
    response.headers["Cache-Control"] = "no-store"
    return response


@app.exception_handler(StarletteHTTPException)
async def http_error(request, exc):
    detail = exc.detail if isinstance(exc.detail, dict) else {"code": "HTTP_ERROR", "message": str(exc.detail)}
    return error_response(request, exc.status_code, detail["code"], detail["message"], headers=exc.headers)


@app.exception_handler(RequestValidationError)
async def invalid_input(request, exc):
    fields = [{"field": ".".join(map(str, e["loc"])), "message": e["msg"]} for e in exc.errors()]
    return error_response(request, 400, "VALIDATION_ERROR", "Dữ liệu không hợp lệ.", fields)


def active_food(food_id):
    food = foods.get(food_id)
    if food is None or food["status"] != "ACTIVE":
        fail(404, "FOOD_NOT_FOUND", "Không tìm thấy món đang bán.")
    return food


def cart_view(user_id):
    items = [{**item, "name": foods[item["foodId"]]["name"], "unitPrice": foods[item["foodId"]]["price"],
              "foodStatus": foods[item["foodId"]]["status"]} for item in carts.setdefault(user_id, [])]
    return {"customerId": user_id, "items": items,
            "total": sum(i["quantity"] * i["unitPrice"] for i in items)}


def owned_item(user_id, item_id):
    for owner, items in carts.items():
        for item in items:
            if item["cartItemId"] == item_id:
                if owner != user_id:
                    fail(403, "NOT_OWNER", "CartItem không thuộc người gọi.")
                return item
    fail(404, "CART_ITEM_NOT_FOUND", "Không tìm thấy dòng giỏ.")


def get_order(order_id):
    if order_id not in orders:
        fail(404, "ORDER_NOT_FOUND", "Không tìm thấy đơn.")
    return orders[order_id]


def owned_order(request, order_id):
    order = get_order(order_id)
    if order["customerId"] != request.state.principal["userId"]:
        fail(403, "NOT_OWNER", "Đơn không thuộc người gọi.")
    return order


def change_status(order, status):
    if status not in TRANSITIONS[order["status"]]:
        fail(409, "INVALID_TRANSITION", "Không thể chuyển trạng thái đơn theo yêu cầu.")
    order["status"] = status
    return deepcopy(order)


@app.get("/api/foods", tags=["Food"], summary="UC03 — Xem món đang bán")
async def list_foods():
    items = [f for f in foods.values() if f["status"] == "ACTIVE"]
    return {"items": items, "totalElements": len(items)}


@app.get("/api/foods/search", tags=["Food"], summary="UC05 — Tìm món")
async def search_foods(q: Annotated[str, Query(min_length=1, max_length=100)]):
    items = [f for f in foods.values() if f["status"] == "ACTIVE" and q.strip().casefold() in f["name"].casefold()]
    return {"items": items, "totalElements": len(items)}


@app.get("/api/foods/{foodId}", tags=["Food"], summary="UC04 — Chi tiết món")
async def food_detail(foodId: str):
    return active_food(foodId)


@app.post("/api/foods", status_code=201, tags=["Food"], dependencies=protected, summary="UC06 — Thêm món")
async def create_food(body: FoodInput):
    if any(f["name"].casefold() == body.name.casefold() and f["restaurantId"] == body.restaurantId for f in foods.values()):
        fail(409, "FOOD_CONFLICT", "Tên món trùng trong nhà hàng mock.")
    food_id = new_id("food")
    foods[food_id] = {"foodId": food_id, **body.model_dump()}
    return foods[food_id]


@app.put("/api/foods/{foodId}", tags=["Food"], dependencies=protected, summary="UC07 — Thay thông tin món")
async def update_food(foodId: str, body: FoodInput):
    if foodId not in foods:
        fail(404, "FOOD_NOT_FOUND", "Không tìm thấy món.")
    if any(f["foodId"] != foodId and f["name"].casefold() == body.name.casefold()
           and f["restaurantId"] == body.restaurantId for f in foods.values()):
        fail(409, "FOOD_CONFLICT", "Tên món trùng trong nhà hàng mock.")
    foods[foodId] = {"foodId": foodId, **body.model_dump()}
    return foods[foodId]


@app.delete("/api/foods/{foodId}", tags=["Food"], dependencies=protected, summary="UC08 — Ngừng bán món")
async def delete_food(foodId: str):
    if foodId not in foods:
        fail(404, "FOOD_NOT_FOUND", "Không tìm thấy món.")
    foods[foodId]["status"] = "INACTIVE"
    return foods[foodId]


@app.get("/api/cart", tags=["Cart"], dependencies=protected, summary="UC09 — Giỏ của người gọi")
async def get_cart(request: Request):
    return cart_view(request.state.principal["userId"])


@app.post("/api/cart/items", tags=["Cart"], dependencies=protected, summary="UC10 — Thêm/gộp món vào giỏ")
async def add_item(request: Request, body: AddItem):
    active_food(body.foodId)
    user_id = request.state.principal["userId"]
    item = next((i for i in carts.setdefault(user_id, []) if i["foodId"] == body.foodId), None)
    if item:
        if item["quantity"] + body.quantity > 100:
            fail(400, "INVALID_QUANTITY", "Mock giới hạn 100 món mỗi dòng.")
        item["quantity"] += body.quantity
    else:
        carts[user_id].append({"cartItemId": new_id("item"), **body.model_dump()})
    return cart_view(user_id)


@app.put("/api/cart/items/{cartItemId}", tags=["Cart"], dependencies=protected, summary="UC11 — Đặt số lượng mới")
async def update_quantity(request: Request, cartItemId: str, body: QuantityInput):
    user_id = request.state.principal["userId"]
    item = owned_item(user_id, cartItemId)
    active_food(item["foodId"])
    item["quantity"] = body.quantity
    return cart_view(user_id)


@app.delete("/api/cart/items/{cartItemId}", tags=["Cart"], dependencies=protected, summary="UC12 — Xóa dòng giỏ")
async def delete_item(request: Request, cartItemId: str):
    user_id = request.state.principal["userId"]
    item = owned_item(user_id, cartItemId)
    carts[user_id].remove(item)
    return cart_view(user_id)


@app.post("/api/orders", status_code=201, tags=["Order"], dependencies=protected, summary="UC13 — Tạo đơn từ giỏ")
async def create_order(request: Request, body: OrderInput):
    user_id = request.state.principal["userId"]
    selected = deepcopy(carts.setdefault(user_id, []))
    if not selected:
        fail(400, "EMPTY_CART", "Giỏ hàng rỗng.")
    items = []
    for item in selected:
        food = active_food(item["foodId"])
        items.append({"foodId": food["foodId"], "name": food["name"], "unitPrice": food["price"], "quantity": item["quantity"]})
    order_id = new_id("order")
    orders[order_id] = {"orderId": order_id, "customerId": user_id, "status": "PENDING", "items": items,
                        "total": sum(i["quantity"] * i["unitPrice"] for i in items), **body.model_dump()}
    purchased_ids = {i["cartItemId"] for i in selected}
    carts[user_id] = [i for i in carts[user_id] if i["cartItemId"] not in purchased_ids]
    return orders[order_id]


@app.get("/api/orders", tags=["Order"], dependencies=protected, summary="UC14/18 — Danh sách đơn theo role")
async def list_orders(request: Request, status: OrderStatus | None = None):
    principal = request.state.principal
    items = [o for o in orders.values() if
             (principal["role"] == "EMPLOYEE" or o["customerId"] == principal["userId"])
             and (status is None or o["status"] == status)]
    return {"items": items, "totalElements": len(items)}


@app.get("/api/orders/{orderId}", tags=["Order"], dependencies=protected, summary="UC14 — Chi tiết đơn của khách")
async def order_detail(request: Request, orderId: str):
    return owned_order(request, orderId)


@app.delete("/api/orders/{orderId}", tags=["Order"], dependencies=protected, summary="UC15 — Hủy đơn, giữ lịch sử")
async def cancel_order(request: Request, orderId: str):
    return change_status(owned_order(request, orderId), "CANCELLED")


@app.post("/api/orders/{orderId}/confirm", tags=["Order"], dependencies=protected, summary="UC16 — Xác nhận đơn")
async def confirm_order(orderId: str):
    return change_status(get_order(orderId), "CONFIRMED")


@app.put("/api/orders/{orderId}/status", tags=["Order"], dependencies=protected, summary="UC17 — Chuyển trạng thái đơn")
async def update_status(orderId: str, body: StatusInput):
    if body.status == "CANCELLED":
        fail(403, "POLICY_TBD", "Quyền EMPLOYEE hủy đơn chưa chốt; mock không cấp quyền này.")
    return change_status(get_order(orderId), body.status)


def mock_openapi():
    from fastapi.openapi.utils import get_openapi
    if app.openapi_schema:
        return app.openapi_schema
    schema = get_openapi(title=app.title, version=app.version, description=app.description, routes=app.routes)
    for path in schema["paths"].values():
        for operation in path.values():
            responses = operation.get("responses", {})
            responses.pop("422", None)
            responses["400"] = {"description": "Dữ liệu không hợp lệ, envelope status/code/message/path/fieldErrors."}
            responses["404"] = {"description": "Không tìm thấy tài nguyên mock."}
            if operation.get("security"):
                responses["401"] = {"description": "Thiếu/sai/hết hạn Bearer token theo chế độ JWT hoặc fixture."}
                responses["403"] = {"description": "Sai role, ownership hoặc chính sách chưa chốt."}
                responses["409"] = {"description": "Xung đột dữ liệu/trạng thái trong mock."}
    schema["x-document-status"] = "Mock / Not an approved production contract"
    app.openapi_schema = schema
    return schema


app.openapi = mock_openapi
