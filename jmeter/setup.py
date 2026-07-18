#!/usr/bin/env python3
"""
Seed script for the checkout latency test (recreated; original was lost).

Brings a FRESH database to the state the .jmx expects:
  1. Register an admin user, elevate it to ADMIN directly in MySQL
     (there is no API path to mint an admin), then re-login so the JWT
     carries role=ADMIN (the role is baked into the token at issuance).
  2. Admin creates a category + one product with initialStock=10.
  3. Register N test users (each auto-gets an empty cart), add the product
     to each cart (qty=1), and write tokens.csv  ->  USERNAME,ACCESS_TOKEN
     (no header — JMeter's CSVDataSet reads every line as data).

Stdlib only (urllib), so no pip install needed.

Usage:
  python3 jmeter/setup.py [N_USERS]      # default 5000
Outputs:
  jmeter/tokens.csv      and prints PRODUCT_ID to stdout
"""
import csv, json, os, sys, urllib.request, urllib.error, subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE = os.environ.get("BASE_URL", "http://localhost:8080")
N    = int(sys.argv[1]) if len(sys.argv) > 1 else 5000
ADMIN_USER, ADMIN_PASS = "admin", "secret123"
HERE = os.path.dirname(os.path.abspath(__file__))
TOKENS = os.path.join(HERE, "tokens.csv")


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read().decode()
        return r.status, (json.loads(raw) if raw else {})


def main():
    # 1. admin: register -> elevate in DB -> re-login for an ADMIN-role token
    print(f"[1/4] Creating admin '{ADMIN_USER}' ...")
    try:
        call("POST", "/api/auth/register", {"username": ADMIN_USER, "password": ADMIN_PASS})
    except urllib.error.HTTPError as e:
        # "Username already taken" comes back as 500 (mapped from a plain RuntimeException),
        # not 400 — tolerate it on re-runs; re-raise anything else.
        if "already taken" not in (e.read().decode(errors="ignore")):
            raise
        print("       admin already exists, continuing")
        print("       admin already exists, continuing")
    # elevate via the mysql container (no API path to create an admin)
    subprocess.run(
        ["docker", "compose", "exec", "-T", "mysql", "mysql", "-uroot", "-p123456",
         "flash_sale_db", "-e", f"UPDATE users SET role='ADMIN' WHERE username='{ADMIN_USER}';"],
        cwd=os.path.dirname(HERE), check=True)
    _, tok = call("POST", "/api/auth/login", {"username": ADMIN_USER, "password": ADMIN_PASS})
    admin = tok["accessToken"]

    # 2. category + product (stock 10)
    print("[2/4] Creating category + product ...")
    _, cat = call("POST", "/api/categories",
                  {"name": "LoadTest", "description": "seed"}, admin)
    cat_id = cat["id"]
    _, prod = call("POST", "/api/products",
                   {"name": "FlashSale SKU", "description": "seed", "price": 9.99,
                    "categoryId": cat_id, "imageUrl": "http://x/y.png", "initialStock": 10}, admin)
    product_id = prod["id"]
    print(f"       product_id = {product_id}")

    # 3. register N users + fill carts, in parallel
    print(f"[3/4] Registering {N} users + filling carts ...")
    def seed_user(i):
        u = f"loaduser{i}"
        try:
            _, a = call("POST", "/api/auth/register", {"username": u, "password": "pw12345678"})
        except urllib.error.HTTPError as e:
            if "already taken" not in (e.read().decode(errors="ignore")):
                raise
            _, a = call("POST", "/api/auth/login", {"username": u, "password": "pw12345678"})
        t = a["accessToken"]
        call("POST", "/api/cart/items", {"productId": product_id, "quantity": 1}, t)
        return u, t

    rows, errors = [], 0
    with ThreadPoolExecutor(max_workers=40) as pool:
        futs = [pool.submit(seed_user, i) for i in range(N)]
        for n, f in enumerate(as_completed(futs), 1):
            try:
                rows.append(f.result())
            except Exception as e:
                errors += 1
                if errors <= 5:
                    print(f"       user error: {e}")
            if n % 500 == 0:
                print(f"       {n}/{N} (errors={errors})")

    # 4. write tokens.csv (no header)
    with open(TOKENS, "w", newline="") as fh:
        csv.writer(fh).writerows(rows)
    print(f"[4/4] Wrote {len(rows)} rows -> {TOKENS} (errors={errors})")
    print(f"PRODUCT_ID={product_id}")


if __name__ == "__main__":
    main()
