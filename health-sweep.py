"""重启后接口健康巡检（只读为主，不动种子数据）。

逐条打一遍本轮新增的六组接口，确认重启后全部存活。
"""
import json
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8090"
PASS = FAIL = 0


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {}


def expect(method, path, want, token=None, body=None, note=""):
    global PASS, FAIL
    st, r = call(method, path, body, token)
    code = r.get("code") if isinstance(r, dict) else None
    ok = (st == want) if isinstance(want, int) else True
    if isinstance(want, tuple):
        ok = (st, code) == want
    tag = "PASS" if ok else "FAIL"
    if ok:
        PASS += 1
    else:
        FAIL += 1
    print(f"[{tag}] {method} {path} -> {st}/code={code} {note}")
    return r


_, tr = call("POST", "/api/auth/login", {"username": "teacher", "password": "teacher123"})
TT = tr["data"]["token"]
_, sr = call("POST", "/api/auth/login", {"username": "stu1", "password": "123456"})
ST = sr["data"]["token"]
print("tokens ok\n--- 诊断 ---")
expect("GET", "/api/diagnosis/1/report", 200, TT)
expect("GET", "/api/diagnosis/my/report", 200, ST)
expect("POST", "/api/diagnosis/generate", (403, 403), ST, {"studentId": 1})
print("--- 内容 ---")
expect("GET", "/api/contents/status", 200, TT)
expect("GET", "/api/contents/graph/1", 200, TT)
expect("GET", "/api/contents/node/3", 200, TT)
expect("GET", "/api/contents/node/3/published", 200, ST)
print("--- 推荐 ---")
expect("GET", "/api/recommend/rules", 200, TT)
expect("GET", "/api/recommend/preview?studentId=1", 200, TT)
expect("GET", "/api/recommend/my", 200, ST)
print("--- 学习任务 ---")
expect("GET", "/api/tasks/my/today", 200, ST)
expect("GET", "/api/tasks/student/1", 200, TT)
print("--- 映射 ---")
expect("POST", "/api/questions/mapping/suggest", 200, TT, {"questionId": 1, "graphId": 1, "topK": 5})
print("--- 权限边界 ---")
expect("GET", "/api/tasks/student/1", (403, 403), ST, None, "学生查他人任务须 403")
expect("POST", "/api/recommend/rules", (403, 403), ST, {"name": "x", "config": {}}, "学生建规则须 403")

print(f"\nPASS={PASS} FAIL={FAIL}")
print("全部通过 ✅" if FAIL == 0 else "存在失败 ❌")
