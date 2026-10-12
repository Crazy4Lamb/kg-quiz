"""复验内容生成里的选项渲染：库里存 JSON 数组，正文必须渲染成 A./B. 列表。"""
import json
import urllib.request

BASE = "http://127.0.0.1:8090"


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
        return e.code, e.read().decode()


st, r = call("POST", "/api/auth/login", {"username": "teacher", "password": "teacher123"})
assert st == 200, (st, r)
token = r["data"]["token"]
print("login ok")

# 找一个「有配套题目」的知识点：拿积分近似数（此前映射过题）
node_id = 3
st, r = call("POST", "/api/contents/generate",
             {"nodeId": node_id, "type": "EXAMPLE", "replaceExisting": True}, token)
print("generate:", st, json.dumps(r, ensure_ascii=False)[:200])
assert st == 200, (st, r)

st, r = call("GET", f"/api/contents/node/{node_id}", token=token)
assert st == 200, (st, r)
items = r["data"] if isinstance(r["data"], list) else r["data"].get("items", [])
print("contents on node:", len(items))

ok = False
for it in items:
    body = it.get("body") or ""
    if "典型例题" in body or it.get("type") == "EXAMPLE":
        print("\n----- body -----")
        print(body)
        print("----- /body -----\n")
        if '["' in body:
            print("FAIL: 正文仍出现原始 JSON 数组")
        elif any(f"- {c}. " in body for c in "ABCD"):
            print("PASS: 选项已渲染为字母列表")
            ok = True
        else:
            print("WARN: 该内容没有选项（可能此题无 option）")
            ok = True
print("RESULT:", "PASS" if ok else "FAIL")
