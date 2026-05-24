import requests
import csv

base_url = "http://localhost:8080"
tokens = []

for i in range(1, 51):
    res = requests.post(f"{base_url}/api/auth/login", json={
        "username": f"user{i}",
        "password": "password"
    })
    token = res.json().get("token")
    tokens.append([f"user{i}", token])
    print(f"Got token for user{i}")

with open("tokens.csv", "w", newline="") as f:
    writer = csv.writer(f)
    writer.writerow(["username", "token"])
    writer.writerows(tokens)

print("Done! tokens.csv created.")