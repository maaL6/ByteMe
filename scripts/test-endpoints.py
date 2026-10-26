"""Exercise the running local API with disposable fixtures; never print or save JWTs.

Run: python3 -X utf8 scripts/test-endpoints.py
Requires the Docker Compose stack (docker compose up --build) on localhost:8080.
Set BYTEME_BASE_URL for a different backend address; SQL still uses Compose mysql.
SQL is used only for fixture categories/roles, verification and targeted cleanup.
"""
import datetime
import json
import os
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("BYTEME_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
RUN = "api_test_" + secrets.token_hex(6)
PASSWORD = secrets.token_urlsafe(18)
RESULTS = []
GROUP = "Infrastructure"
CATEGORIES = []
USERS = {}
TOKENS = {}


def sql(statement):
    result = subprocess.run(
        ["docker", "compose",
         "exec", "-T", "mysql", "sh", "-c",
         'MYSQL_PWD="$MYSQL_PASSWORD" mysql --user="$MYSQL_USER" '
         '--database="$MYSQL_DATABASE" --batch --skip-column-names --default-character-set=utf8mb4'],
        input=statement, capture_output=True, text=True, encoding="utf-8", cwd=ROOT,
        timeout=30,
    )
    if result.returncode:
        raise RuntimeError("Fixture SQL failed (database details withheld)")
    return result.stdout.strip()


def request(method, path, body=None, token=None, headers=None, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    outgoing = {"Content-Type": "application/json"}
    if token:
        outgoing["Authorization"] = "Bearer " + token
    outgoing.update(headers or {})
    started = time.monotonic()
    try:
        response = urllib.request.urlopen(urllib.request.Request(BASE + path, data, outgoing, method=method), timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        payload = response.read().decode("utf-8")
        try:
            parsed = json.loads(payload)
        except ValueError:
            parsed = None
        return response.status, parsed, dict(response.headers), round((time.monotonic() - started) * 1000)


def check(name, condition, expected, actual):
    RESULTS.append({"group": GROUP, "case": name, "pass": bool(condition), "expected": str(expected), "actual": str(actual)})
    print(("PASS " if condition else "FAIL ") + GROUP + " / " + name, flush=True)


def http(name, method, path, expected, body=None, token=None, code=None, **kwargs):
    status, value, headers, elapsed = request(method, path, body, token, **kwargs)
    actual_code = value.get("code") if isinstance(value, dict) else None
    check(name, status == expected and (code is None or code == actual_code),
          f"HTTP {expected}" + (f" {code}" if code else ""),
          f"HTTP {status}" + (f" {actual_code}" if actual_code else ""))
    return value, headers


def state():
    tables = ["users", "categories", "foods", "carts", "cart_items", "orders", "order_items"]
    return {table: int(sql(f"SELECT COUNT(*) FROM {table}")) for table in tables} | {
        "stock_total": int(sql("SELECT COALESCE(SUM(stock_quantity), 0) FROM foods"))}


def food_body(label, **changes):
    return {"categoryId": CATEGORIES[0], "name": RUN + " " + label, "description": "Disposable API test fixture",
            "price": 25000, "stockQuantity": 20, "status": "ACTIVE"} | changes


def food(name, **changes):
    body = food_body(name, **changes)
    result, _ = http("Create " + name, "POST", "/api/foods", 201, body, TOKENS["admin"])
    if not result or "id" not in result:
        raise RuntimeError("Unable to create food fixture")
    return result["id"], body


def add(who, food_id, quantity=1, expected=200):
    value, _ = http(f"Add {quantity} item(s) to {who} cart", "POST", "/api/cart/items", expected,
                    {"foodId": food_id, "quantity": quantity}, TOKENS[who])
    return value


def clear(who):
    status, cart, _, _ = request("GET", "/api/cart", token=TOKENS[who])
    if status == 200:
        for item in cart["items"]:
            http("Clear fixture cart", "DELETE", f"/api/cart/items/{item['id']}", 200, token=TOKENS[who])


DINE = {"orderType": "DINE_IN", "tableNumber": "TEST", "paymentMethod": "CASH"}
DELIVERY = {"orderType": "DELIVERY", "recipientName": "API Test", "shippingPhone": "0900000000",
            "shippingAddress": "Disposable test address", "paymentMethod": "COD"}


def order(body=DINE, who="a"):
    value, headers = http("Create " + body["orderType"] + " order", "POST", "/api/orders", 201, body, TOKENS[who])
    if not value or "id" not in value:
        raise RuntimeError("Unable to create order fixture")
    check("Location points at created order", headers.get("Location", "").endswith(f"/api/orders/{value['id']}"),
          "Location ends with order ID", headers.get("Location"))
    return value


def stock(food_id):
    return int(sql(f"SELECT stock_quantity FROM foods WHERE id = {int(food_id)}"))


def run():
    global GROUP
    http("Backend health", "GET", "/actuator/health", 200)
    http("Swagger", "GET", "/swagger-ui/index.html", 200)
    http("OpenAPI", "GET", "/openapi.yaml", 200)
    GROUP = "Auth"
    for index, who in enumerate(["a", "b", "employee", "admin", "inactive", "quota"]):
        payload = {"fullName": "API Test " + who, "email": f"{RUN}_{who}@example.test",
                   "phone": "0" + str(secrets.randbelow(10**9)).zfill(9), "password": PASSWORD,
                   "confirmPassword": PASSWORD}
        account, _ = http("Register " + who, "POST", "/api/auth/register", 201, payload)
        if not account or "id" not in account:
            raise RuntimeError("Unable to register fixture account")
        USERS[who] = int(account["id"])
        check("Registration creates CUSTOMER/ACTIVE", account["role"] == "CUSTOMER" and account["status"] == "ACTIVE",
              "CUSTOMER/ACTIVE", f"{account['role']}/{account['status']}")
        if who == "a":
            original = payload
    http("Duplicate email", "POST", "/api/auth/register", 409, original, code="ACCOUNT_CONFLICT")
    http("Duplicate phone", "POST", "/api/auth/register", 409,
         original | {"email": f"{RUN}_duplicate@example.test"}, code="ACCOUNT_CONFLICT")
    for label, change in [("Invalid email", {"email": "invalid"}), ("Invalid phone", {"phone": "abc"}),
                          ("Password mismatch", {"confirmPassword": "DifferentPassword123"}),
                          ("Short password", {"password": "abc", "confirmPassword": "abc"}),
                          ("Role escalation in registration", {"role": "ADMIN"})]:
        http(label, "POST", "/api/auth/register", 400, original | change)
    for who, role in [("employee", "EMPLOYEE"), ("admin", "ADMIN")]:
        sql(f"UPDATE users SET role = '{role}' WHERE id = {int(USERS[who])}")
    sql(f"UPDATE users SET status = 'INACTIVE' WHERE id = {int(USERS['inactive'])}")
    for who in ["a", "b", "employee", "admin"]:
        value, headers = http("Login " + who, "POST", "/api/auth/token", 200,
                              {"email": f"{RUN}_{who}@example.test", "password": PASSWORD})
        TOKENS[who] = value["accessToken"]
        check("Token response disables caching", headers.get("Cache-Control") == "no-store", "no-store", headers.get("Cache-Control"))
    http("Wrong password", "POST", "/api/auth/token", 401,
         {"email": f"{RUN}_a@example.test", "password": "WrongPassword123"}, code="INVALID_CREDENTIALS")
    http("Inactive account", "POST", "/api/auth/token", 403,
         {"email": f"{RUN}_inactive@example.test", "password": PASSWORD}, code="ACCOUNT_NOT_ACTIVE")
    http("Unknown account", "POST", "/api/auth/token", 401,
         {"email": f"{RUN}_missing@example.test", "password": PASSWORD}, code="INVALID_CREDENTIALS")
    http("Malformed auth JSON", "POST", "/api/auth/token", 400, raw=b"{")
    http("Auth rejects unknown fields", "POST", "/api/auth/token", 400,
         {"email": f"{RUN}_a@example.test", "password": PASSWORD, "role": "ADMIN"})
    GROUP = "Food"
    for suffix in ["one", "two"]:
        CATEGORIES.append(int(sql(f"INSERT INTO categories (name) VALUES ('{RUN}_{suffix}'); SELECT LAST_INSERT_ID();")))
    primary, primary_body = food("primary")
    secondary, secondary_body = food("secondary", price=10000)
    inactive, _ = food("inactive", status="INACTIVE")
    permission_food, permission_body = food("permission")
    other_category, _ = food("other category", categoryId=CATEGORIES[1])
    items, _ = http("Public active menu", "GET", f"/api/foods?categoryId={CATEGORIES[0]}", 200)
    check("Menu excludes inactive food", inactive not in [item["id"] for item in items], "inactive excluded", "inactive present" if inactive in [item["id"] for item in items] else "excluded")
    http("Public detail", "GET", f"/api/foods/{primary}", 200)
    http("Inactive detail", "GET", f"/api/foods/{inactive}", 404)
    http("Missing food", "GET", "/api/foods/9223372036854775807", 404)
    http("Invalid food ID", "GET", "/api/foods/not-a-number", 400)
    http("Missing category", "GET", "/api/foods?categoryId=9223372036854775807", 404)
    matches, _ = http("Search by name", "GET", "/api/foods/search?q=" + urllib.parse.quote(RUN + " primary"), 200)
    check("Search finds fixture", isinstance(matches, list) and any(item["id"] == primary for item in matches), "primary included", "found" if isinstance(matches, list) and matches else "not found")
    # UC05 returns all matching ACTIVE foods as an array, ordered by ID.
    # Price filters and pagination are not part of the current contract.
    matches, _ = http("Search without category", "GET", f"/api/foods/search?q={RUN}", 200)
    expected_ids = sorted([primary, secondary, permission_food, other_category])
    actual_ids = [item["id"] for item in matches] if isinstance(matches, list) else None
    check("Search returns all matching active foods in ID order", actual_ids == expected_ids,
          expected_ids, actual_ids)
    matches, _ = http("Search with category", "GET", f"/api/foods/search?q={RUN}&categoryId={CATEGORIES[0]}", 200)
    expected_ids = sorted([primary, secondary, permission_food])
    actual_ids = [item["id"] for item in matches] if isinstance(matches, list) else None
    check("Search applies category filter and returns an unpaginated array", actual_ids == expected_ids,
          expected_ids, actual_ids)
    matches, _ = http("Search without matches", "GET", f"/api/foods/search?q={RUN}_missing", 200)
    check("Search without matches returns an empty array", matches == [], [], matches)
    http("Blank search", "GET", "/api/foods/search?q=%20", 400)
    http("Long search", "GET", "/api/foods/search?q=" + "x" * 101, 400)
    for method, path, body in [("POST", "/api/foods", food_body("anonymous")),
                               ("PUT", f"/api/foods/{permission_food}", permission_body),
                               ("DELETE", f"/api/foods/{permission_food}", None)]:
        http("Food write without JWT " + method, method, path, 401, body)
    for who in ["a", "employee"]:
        for method, path, body in [("POST", "/api/foods", food_body("forbidden " + who)),
                                   ("PUT", f"/api/foods/{permission_food}", permission_body | {"price": 12000}),
                                   ("DELETE", f"/api/foods/{permission_food}", None)]:
            http("Non-admin Food write " + who + " " + method, method, path, 403, body, TOKENS[who])
    http("Duplicate name same category", "POST", "/api/foods", 409, primary_body, TOKENS["admin"])
    http("Duplicate name different category", "POST", "/api/foods", 409,
         primary_body | {"categoryId": CATEGORIES[1]}, TOKENS["admin"])
    http("Duplicate trimmed name", "POST", "/api/foods", 409,
         primary_body | {"name": " " + primary_body["name"] + " "}, TOKENS["admin"])
    for label, change in [("Zero price", {"price": 0}), ("Negative stock", {"stockQuantity": -1}),
                          ("Fractional stock", {"stockQuantity": 1.5}), ("Blank name", {"name": " "}),
                          ("Name exceeds column limit", {"name": "x" * 151}), ("Unknown status", {"status": "UNKNOWN"})]:
        http(label, "POST", "/api/foods", 400, food_body(label, **change), TOKENS["admin"])
    http("Price between zero and one", "POST", "/api/foods", 201,
         food_body("fractional price", price=0.5), TOKENS["admin"])
    http("Admin updates food", "PUT", f"/api/foods/{secondary}", 200, secondary_body, TOKENS["admin"])
    without_stock = {key: value for key, value in secondary_body.items() if key != "stockQuantity"}
    value, _ = http("Update without optional stock", "PUT", f"/api/foods/{secondary}", 200, without_stock, TOKENS["admin"])
    check("Omitted stock preserves current stock", value.get("stockQuantity") == 20, 20, value.get("stockQuantity"))
    http("Restore isolated food fixture stock", "PUT", f"/api/foods/{secondary}", 200, secondary_body, TOKENS["admin"])
    GROUP = "Cart"
    http("Cart requires JWT", "GET", "/api/cart", 401)
    http("Invalid JWT", "GET", "/api/cart", 401, token="invalid")
    for who in ["employee", "admin"]:
        http(who + " cannot access cart", "GET", "/api/cart", 403, token=TOKENS[who])
    cart, _ = http("Cart without saved cart", "GET", "/api/cart", 200, token=TOKENS["a"])
    check("Missing cart reads as empty", isinstance(cart, dict) and cart.get("items") == [], [], cart)
    carts = int(sql(f"SELECT COUNT(*) FROM carts WHERE user_id = {int(USERS['a'])}"))
    check("Reading cart does not create one", carts == 0, 0, carts)
    http("Checkout without saved cart", "POST", "/api/orders", 400, DINE, TOKENS["a"], code="EMPTY_CART")
    for body in [{"foodId": primary, "quantity": 0}, {"foodId": primary, "quantity": -1},
                 {"foodId": 0, "quantity": 1}, {"foodId": primary}, {"foodId": primary, "quantity": 1.5}]:
        http("Reject invalid cart line " + json.dumps(body), "POST", "/api/cart/items", 400, body, TOKENS["a"])
    clear("a")
    add("a", inactive, expected=409)
    add("a", 9223372036854775807, expected=404)
    cart = add("a", primary, 2)
    item_id = cart["items"][0]["id"]
    cart = add("a", primary, 1)
    check("Repeated addition merges quantity", len(cart["items"]) == 1 and cart["items"][0]["quantity"] == 3,
          "one line, quantity 3", f"{len(cart['items'])} line(s), quantity {cart['items'][0]['quantity']}")
    add("b", secondary)
    cart, _ = http("X-User-Id cannot spoof owner", "GET", "/api/cart", 200, token=TOKENS["a"], headers={"X-User-Id": str(USERS["b"])})
    check("Cart belongs to JWT subject", cart["userId"] == USERS["a"], USERS["a"], cart["userId"])
    for method, body in [("PUT", {"quantity": 7}), ("DELETE", None)]:
        http("Other owner cannot modify item " + method, method, f"/api/cart/items/{item_id}", 404, body, TOKENS["b"])
    cart, _ = http("Set quantity", "PUT", f"/api/cart/items/{item_id}", 200, {"quantity": 2}, TOKENS["a"])
    check("PUT replaces quantity", cart["items"][0]["quantity"] == 2, 2, cart["items"][0]["quantity"])
    http("Reject zero update", "PUT", f"/api/cart/items/{item_id}", 400, {"quantity": 0}, TOKENS["a"])
    http("Missing cart item", "DELETE", "/api/cart/items/9223372036854775807", 404, token=TOKENS["a"])
    GROUP = "Order"
    for who in ["employee", "admin"]:
        http(who + " cannot create order", "POST", "/api/orders", 403, DINE, TOKENS[who])
    for label, body in [("Missing serving information", {}), ("DINE_IN without table", DINE | {"tableNumber": None}),
                        ("DINE_IN cannot use COD", DINE | {"paymentMethod": "COD"}),
                        ("DELIVERY cannot use CASH", DELIVERY | {"paymentMethod": "CASH"}),
                        ("DELIVERY cannot include table", DELIVERY | {"tableNumber": "T1"}),
                        ("Unknown status enum", DINE | {"orderType": 0})]:
        http(label, "POST", "/api/orders", 400, body, TOKENS["a"])
    first = order(DINE | {"items": [{"foodId": secondary, "price": 1}], "userId": USERS["b"], "totalAmount": 1})
    order_id = first["id"]
    check("Client cannot override order owner", first["userId"] == USERS["a"], USERS["a"], first["userId"])
    check("Client cannot override cart items", len(first["items"]) == 1 and first["items"][0]["foodId"] == primary,
          primary, [item["foodId"] for item in first["items"]])
    check("Server computes total", first["totalAmount"] == 50000, 50000, first["totalAmount"])
    check("Checkout decreases stock", stock(primary) == 18, 18, stock(primary))
    cart, _ = http("Cart after checkout", "GET", "/api/cart", 200, token=TOKENS["a"])
    check("Checkout clears cart", cart["items"] == [], [], len(cart["items"]))
    http("Empty cart prevents another order", "POST", "/api/orders", 400, DINE, TOKENS["a"], code="EMPTY_CART")
    http("Owner reads order", "GET", f"/api/orders/{order_id}", 200, token=TOKENS["a"])
    for method in ["GET", "DELETE"]:
        http("Other owner blocked " + method, method, f"/api/orders/{order_id}", 403, token=TOKENS["b"], code="FORBIDDEN")
    http("Admin cannot read orders", "GET", "/api/orders", 403, token=TOKENS["admin"])
    http("Customer cannot confirm", "POST", f"/api/orders/{order_id}/confirm", 403, token=TOKENS["a"])
    http("Customer cannot update status", "PUT", f"/api/orders/{order_id}/status", 403, {"status": "CONFIRMED"}, TOKENS["a"])
    for query in ["page=-1", "size=0", "size=101", "status=UNKNOWN", "orderType=0"]:
        http("Reject invalid order filter " + query, "GET", "/api/orders?" + query, 400, token=TOKENS["a"])
    http("Unknown order", "GET", "/api/orders/9223372036854775807", 404, token=TOKENS["a"], code="ORDER_NOT_FOUND")
    page, _ = http("Customer order page", "GET", "/api/orders?page=0&size=1", 200, token=TOKENS["a"])
    check("List scoped to owner", all(value["userId"] == USERS["a"] for value in page["content"]), "owner only", len(page["content"]))
    page, _ = http("Employee default order page", "GET", "/api/orders", 200, token=TOKENS["employee"])
    check("Employee defaults to active statuses", all(value["status"] in ["PENDING", "CONFIRMED", "PROCESSING", "SHIPPING"] for value in page["content"]),
          "only active statuses", sorted(set(value["status"] for value in page["content"])))
    http("Change menu price after checkout", "PUT", f"/api/foods/{primary}", 200,
         primary_body | {"price": 30000, "stockQuantity": 18}, TOKENS["admin"])
    snapshot, _ = http("Read historical snapshot", "GET", f"/api/orders/{order_id}", 200, token=TOKENS["a"])
    check("Order price remains unchanged", snapshot["totalAmount"] == 50000, 50000, snapshot["totalAmount"])
    http("Cancel pending order", "DELETE", f"/api/orders/{order_id}", 200, token=TOKENS["a"])
    check("Cancellation restores stock", stock(primary) == 20, 20, stock(primary))
    http("Repeated cancellation rejected", "DELETE", f"/api/orders/{order_id}", 409, token=TOKENS["a"], code="INVALID_ORDER_TRANSITION")
    check("Cancellation cannot restore twice", stock(primary) == 20, 20, stock(primary))
    for serving in [DINE, DELIVERY]:
        clear("a")
        add("a", secondary)
        created = order(serving)
        current_id = created["id"]
        http("Reject skipped PENDING transition", "PUT", f"/api/orders/{current_id}/status", 409,
             {"status": "COMPLETED"}, TOKENS["employee"])
        confirmed, _ = http("Employee confirms", "POST", f"/api/orders/{current_id}/confirm", 200, token=TOKENS["employee"])
        check("Confirmation records actor/time", confirmed["confirmedBy"] == USERS["employee"] and bool(confirmed["confirmedAt"]), "employee ID and timestamp", confirmed["confirmedBy"])
        http("Customer cannot cancel confirmed order", "DELETE", f"/api/orders/{current_id}", 409, token=TOKENS["a"])
        http("Employee starts processing", "PUT", f"/api/orders/{current_id}/status", 200, {"status": "PROCESSING"}, TOKENS["employee"])
        if serving["orderType"] == "DELIVERY":
            http("Delivery cannot skip SHIPPING", "PUT", f"/api/orders/{current_id}/status", 409,
                 {"status": "COMPLETED"}, TOKENS["employee"])
            http("Delivery ships", "PUT", f"/api/orders/{current_id}/status", 200, {"status": "SHIPPING"}, TOKENS["employee"])
        else:
            http("Dine-in cannot enter SHIPPING", "PUT", f"/api/orders/{current_id}/status", 409,
                 {"status": "SHIPPING"}, TOKENS["employee"])
        completed, _ = http("Employee completes " + serving["orderType"], "PUT", f"/api/orders/{current_id}/status", 200,
                            {"status": "COMPLETED"}, TOKENS["employee"])
        check("Completion has timestamp", bool(completed["completedAt"]), "completedAt present", bool(completed["completedAt"]))
        http("Terminal status cannot change", "PUT", f"/api/orders/{current_id}/status", 409,
             {"status": "CANCELLED"}, TOKENS["employee"])
    GROUP = "Checkout failures"
    clear("a")
    clear("b")
    add("a", primary, 21)
    count_before = int(sql(f"SELECT COUNT(*) FROM orders WHERE user_id = {int(USERS['a'])}"))
    error, _ = http("Insufficient stock", "POST", "/api/orders", 409, DINE, TOKENS["a"], code="INSUFFICIENT_STOCK")
    check("Stock error names the food", primary_body["name"] in (error or {}).get("message", ""),
          primary_body["name"], (error or {}).get("message"))
    check("Failed checkout leaves stock", stock(primary) == 20, 20, stock(primary))
    check("Failed checkout creates no order", int(sql(f"SELECT COUNT(*) FROM orders WHERE user_id = {int(USERS['a'])}")) == count_before,
          count_before, int(sql(f"SELECT COUNT(*) FROM orders WHERE user_id = {int(USERS['a'])}")))
    cart, _ = http("Cart retained after failed checkout", "GET", "/api/cart", 200, token=TOKENS["a"])
    check("Failed checkout retains quantity", cart["items"][0]["quantity"] == 21, 21, cart["items"][0]["quantity"])
    http("Stop selling cart food", "DELETE", f"/api/foods/{primary}", 204, token=TOKENS["admin"])
    error, _ = http("Inactive food blocks checkout", "POST", "/api/orders", 409, DINE, TOKENS["a"], code="FOOD_UNAVAILABLE")
    check("Unavailable error names the food", primary_body["name"] in (error or {}).get("message", ""),
          primary_body["name"], (error or {}).get("message"))
    clear("a")
    # Concurrency checks (last unit, concurrent adds, double checkout/cancel) move to phase 2:
    # phase 1 has no row locks or optimistic locking, so those races are the before/after target.
    GROUP = "Auth rate limit"
    for attempt in range(11):
        http("Email login limit attempt " + str(attempt + 1), "POST", "/api/auth/token", 401 if attempt < 10 else 429,
             {"email": f"{RUN}_quota@example.test", "password": "WrongPassword123"})


def cleanup():
    # IDs are discovered from this run's unique email/category prefix, never from global rows.
    user_ids = sql(f"SELECT id FROM users WHERE LEFT(email, {len(RUN) + 1}) = '{RUN}_'").splitlines()
    category_ids = sql(f"SELECT id FROM categories WHERE name IN ('{RUN}_one', '{RUN}_two')").splitlines()
    statements = ["START TRANSACTION"]
    if user_ids:
        ids = ",".join(str(int(value)) for value in user_ids)
        statements += [f"DELETE oi FROM order_items oi JOIN orders o ON o.id=oi.order_id WHERE o.user_id IN ({ids})",
                       f"DELETE FROM orders WHERE user_id IN ({ids})",
                       f"DELETE ci FROM cart_items ci JOIN carts c ON c.id=ci.cart_id WHERE c.user_id IN ({ids})",
                       f"DELETE FROM carts WHERE user_id IN ({ids})"]
    if category_ids:
        ids = ",".join(str(int(value)) for value in category_ids)
        statements += [f"DELETE FROM foods WHERE category_id IN ({ids})", f"DELETE FROM categories WHERE id IN ({ids})"]
    if user_ids:
        statements += [f"DELETE FROM users WHERE id IN ({','.join(str(int(value)) for value in user_ids)})"]
    sql(";\n".join(statements + ["COMMIT;"]))


if __name__ == "__main__":
    baseline = state()
    fatal = None
    cleaned = False
    try:
        run()
    except Exception as error:
        fatal = type(error).__name__ + ": " + str(error)
        print("Test execution stopped: " + fatal, flush=True)
    finally:
        GROUP = "Cleanup"
        try:
            cleanup()
            after = state()
            cleaned = after == baseline
            check("Fixture cleanup restores row counts and total stock", cleaned, baseline, after)
        except Exception as error:
            fatal = (fatal or "") + " Cleanup: " + type(error).__name__
        passed = sum(value["pass"] for value in RESULTS)
        output = {"run": RUN, "baseUrl": BASE, "timestamp": datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=7))).isoformat(),
                  "passed": passed, "failed": len(RESULTS) - passed, "cleanupVerified": cleaned, "fatal": fatal, "results": RESULTS}
        (ROOT / ".local").mkdir(exist_ok=True)
        (ROOT / ".local/endpoint-test-results.json").write_text(json.dumps(output, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"RESULT: {passed} passed, {len(RESULTS) - passed} failed; cleanup={cleaned}; fatal={fatal}", flush=True)
    raise SystemExit(1 if fatal or not cleaned or any(not value["pass"] for value in RESULTS) else 0)
