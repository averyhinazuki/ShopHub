#!/usr/bin/env python3
"""
JMeter pre-test setup for the checkout stress test.

What this does:
  1. Logs in as admin and creates (or resets) a test product with INITIAL_STOCK units.
  2. Registers/logs in as user1..user50, puts exactly 1 unit of the test product
     in each user's cart, and writes a fresh tokens.csv to the project root.

Run this immediately before launching JMeter — access tokens expire in 15 minutes.

Requirements:
  pip install requests

Usage:
  cd flash-sale-system
  python jmeter/setup.py
"""

import requests
import csv
import sys

# ── Configuration ────────────────────────────────────────────────────────────
BASE_URL     = "http://localhost:8080"
ADMIN_USER   = "admin"          # Must exist in DB with role ADMIN
ADMIN_PASS   = "password"
TEST_USERS   = 50               # Must match ThreadGroup.num_threads in the JMX
USER_PASS    = "password"       # Password for all test users
INITIAL_STOCK = 10              # Product stock — fewer than TEST_USERS to expose the race
PRODUCT_ID   = None             # Set to an existing product ID, or None to create one
# ─────────────────────────────────────────────────────────────────────────────

session = requests.Session()
session.headers.update({"Content-Type": "application/json"})


def login(username, password):
    res = session.post(f"{BASE_URL}/api/auth/login",
                       json={"username": username, "password": password})
    if res.status_code == 200:
        return res.json()["accessToken"]
    return None


def register_and_login(username, password):
    session.post(f"{BASE_URL}/api/auth/register",
                 json={"username": username, "password": password})
    return login(username, password)


def auth_headers(token):
    return {"Authorization": f"Bearer {token}"}


# ── Step 1: Admin login ───────────────────────────────────────────────────────
print("=== Step 1: Admin login ===")
admin_token = login(ADMIN_USER, ADMIN_PASS)
if not admin_token:
    print(f"ERROR: Could not log in as '{ADMIN_USER}'. "
          f"Make sure the user exists with role ADMIN.")
    sys.exit(1)
print(f"OK: logged in as {ADMIN_USER}")


# ── Step 2: Ensure a test category exists ────────────────────────────────────
print("\n=== Step 2: Ensure test category ===")
res = session.get(f"{BASE_URL}/api/categories",
                  headers=auth_headers(admin_token))
categories = res.json() if res.status_code == 200 else []
if categories:
    category_id = categories[0]["id"]
    print(f"Using existing category id={category_id} ({categories[0]['name']})")
else:
    res = session.post(f"{BASE_URL}/api/categories",
                       headers=auth_headers(admin_token),
                       json={"name": "Test", "description": "JMeter test category"})
    if res.status_code not in (200, 201):
        print(f"ERROR: Could not create category: {res.status_code} {res.text}")
        sys.exit(1)
    category_id = res.json()["id"]
    print(f"Created category id={category_id}")


# ── Step 3: Create or reset the test product ─────────────────────────────────
print("\n=== Step 3: Test product ===")
if PRODUCT_ID:
    # Reset available_stock to INITIAL_STOCK via PATCH /inventory
    res = session.get(f"{BASE_URL}/api/products/{PRODUCT_ID}",
                      headers=auth_headers(admin_token))
    if res.status_code != 200:
        print(f"ERROR: Product {PRODUCT_ID} not found.")
        sys.exit(1)
    product = res.json()
    current_stock = product["availableStock"]
    delta = INITIAL_STOCK - current_stock
    if delta != 0:
        reason = "restock" if delta > 0 else "correction"
        res = session.patch(
            f"{BASE_URL}/api/products/{PRODUCT_ID}/inventory",
            headers=auth_headers(admin_token),
            json={"delta": delta, "reason": reason}
        )
        if res.status_code != 200:
            print(f"ERROR: Stock reset failed: {res.status_code} {res.text}")
            sys.exit(1)
    product_id = PRODUCT_ID
    print(f"Product id={product_id} stock reset to {INITIAL_STOCK} (was {current_stock})")
else:
    res = session.post(
        f"{BASE_URL}/api/products",
        headers=auth_headers(admin_token),
        json={
            "name": "JMeter Stress Test Product",
            "description": "Auto-created by jmeter/setup.py — safe to delete after testing",
            "price": 9.99,
            "categoryId": category_id,
            "imageUrl": "",
            "initialStock": INITIAL_STOCK,
        }
    )
    if res.status_code not in (200, 201):
        print(f"ERROR: Could not create product: {res.status_code} {res.text}")
        sys.exit(1)
    product_id = res.json()["id"]
    print(f"Created product id={product_id} with stock={INITIAL_STOCK}")


# ── Step 4: Register / login each test user, populate their cart ──────────────
print(f"\n=== Step 4: Setting up {TEST_USERS} test users ===")
tokens = []
failed = []

for i in range(1, TEST_USERS + 1):
    username = f"user{i}"

    token = login(username, USER_PASS)
    if not token:
        token = register_and_login(username, USER_PASS)
    if not token:
        print(f"  FAILED: cannot log in as {username}")
        failed.append(username)
        continue

    headers = auth_headers(token)

    # GET cart to find existing item for this product
    cart_res = session.get(f"{BASE_URL}/api/cart", headers=headers)
    if cart_res.status_code != 200:
        print(f"  FAILED: cannot get cart for {username}: {cart_res.status_code}")
        failed.append(username)
        continue

    cart = cart_res.json()
    existing_item = next(
        (item for item in cart.get("items", []) if item["productId"] == product_id),
        None
    )

    if existing_item:
        if existing_item["quantity"] != 1:
            # Reset to exactly 1
            put_res = session.put(
                f"{BASE_URL}/api/cart/items/{existing_item['id']}",
                headers=headers,
                json={"productId": product_id, "quantity": 1}
            )
            if put_res.status_code != 200:
                print(f"  WARN: could not set qty=1 for {username}: {put_res.status_code}")
    else:
        # Add product to cart
        post_res = session.post(
            f"{BASE_URL}/api/cart/items",
            headers=headers,
            json={"productId": product_id, "quantity": 1}
        )
        if post_res.status_code not in (200, 201):
            print(f"  FAILED: add-to-cart for {username}: {post_res.status_code} {post_res.text}")
            failed.append(username)
            continue

    tokens.append([username, token])
    print(f"  OK: {username} — cart ready, token captured")

# Write tokens.csv to project root
csv_path = "../tokens.csv"
with open(csv_path, "w", newline="", encoding="utf-8") as f:
    writer = csv.writer(f)
    writer.writerow(["username", "token"])
    writer.writerows(tokens)


# ── Summary ───────────────────────────────────────────────────────────────────
print(f"""
=== Setup complete ===
  Product ID  : {product_id}
  Stock       : {INITIAL_STOCK}
  Users ready : {len(tokens)} / {TEST_USERS}
  Failures    : {len(failed)} {failed if failed else ""}
  tokens.csv  : {csv_path}

Next steps:
  1. Start the Spring Boot app if not running.
  2. Within 15 minutes (token TTL), run:

     jmeter -n -t jmeter/checkout-stress-test.jmx -l jmeter/results/checkout-results.jtl

  3. After the test, verify zero oversell:
     SELECT available_stock FROM product_inventory WHERE product_id = {product_id};
     -- Expected: {max(0, INITIAL_STOCK - len(tokens))} or 0 (all stock consumed / 0 = correct)
""")
