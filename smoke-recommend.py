"""快速冒烟：推荐参数 / 推荐结果 / 掌握画像 / 学习记录"""
import json
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8090/api"


def call(method, path, token=None, body=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def login(u, p):
    s, r = call("POST", "/auth/login", body={"username": u, "password": p})
    assert s == 200, f"login {u} failed: {r}"
    return r["data"]["token"]


TT = login("teacher", "teacher123")
ST = login("stu1", "123456")
AT = login("admin", "admin123")

print("== /recommend/params ==")
s, r = call("GET", "/recommend/params", AT)
print(s, json.dumps(r, ensure_ascii=False)[:400])

print("\n== /recommend/my ==")
s, r = call("GET", "/recommend/my", ST)
print(s, json.dumps(r, ensure_ascii=False)[:500])

print("\n== /recommend/my/mastery ==")
s, r = call("GET", "/recommend/my/mastery", ST)
print(s, json.dumps(r, ensure_ascii=False)[:600])

print("\n== /recommend/mastery/1 ==")
s, r = call("GET", "/recommend/mastery/1", TT)
print(s, json.dumps(r, ensure_ascii=False)[:600])

print("\n== /learning-records/my ==")
s, r = call("GET", "/learning-records/my", ST)
print(s, json.dumps(r, ensure_ascii=False)[:400])

print("\n== /recommend/simulate ==")
s, r = call("POST", "/recommend/simulate", AT, {"studentId": 1, "strategy": "REMEDIATION", "limit": 5})
print(s, json.dumps(r, ensure_ascii=False)[:500])
