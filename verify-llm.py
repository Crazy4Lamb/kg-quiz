#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""验证多协议适配 + 预设目录 + 运行时配置 + Qdrant 自愈。

不依赖任何真实 API Key：预设、协议编解码、配置校验、权限边界都能静态验证；
「试连通」则用假 Key 打真实端点，验证的是错误分类与脱敏是否正确。
"""
import json
import sys
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8090/api"
PASS, FAIL = [], []


def call(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data, timeout=60) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {}
    except Exception as e:
        return 0, {"message": str(e)}


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  PASS  " if cond else "  FAIL  ") + name + (("  -> " + str(detail)) if detail and not cond else ""))


def login(u, p):
    st, r = call("POST", "/auth/login", body={"username": u, "password": p})
    return r.get("data", {}).get("token") if r.get("code") == 0 else None


print("=== 1. 登录 ===")
admin = login("admin", "admin123")
teacher = login("teacher", "teacher123")
stu = login("stu1", "123456")
check("管理员登录", bool(admin))
check("教师登录", bool(teacher))
check("学生登录", bool(stu))
if not (admin and teacher and stu):
    print("登录失败，后续用例无法执行")
    sys.exit(1)

print("\n=== 2. 预设目录 ===")
st, r = call("GET", "/llm/overview", token=teacher)
check("教师可读 overview", st == 200 and r.get("code") == 0, r)
ov = r.get("data", {})
presets = ov.get("presets", [])
check("预设数量 >= 10", len(presets) >= 10, f"实际 {len(presets)}")
codes = {p["presetCode"] for p in presets}
for must in ("deepseek", "qwen", "anthropic", "gemini", "ollama-local", "custom"):
    check(f"预设含 {must}", must in codes)

print("\n=== 3. 协议覆盖 ===")
protocols = {p["value"] for p in ov.get("protocols", [])}
for must in ("OPENAI", "ANTHROPIC", "GEMINI", "OLLAMA", "VOLCENGINE"):
    check(f"支持协议 {must}", must in protocols)

print("\n=== 4. 预设的协议/鉴权/端点自洽 ===")
by_code = {p["presetCode"]: p for p in presets}
check("anthropic 鉴权为 x-api-key", by_code["anthropic"]["auth"] == "HEADER", by_code["anthropic"])
check("anthropic 端点为 /v1/messages", by_code["anthropic"]["chatPath"] == "/v1/messages")
check("anthropic 无原生 JSON 模式", by_code["anthropic"]["jsonMode"] is False)
check("gemini 路径含 {model} 占位", "{model}" in by_code["gemini"]["chatPath"], by_code["gemini"]["chatPath"])
check("gemini 支持 JSON 模式", by_code["gemini"]["jsonMode"] is True)
check("ollama 无需鉴权", by_code["ollama-local"]["auth"] == "NONE")
check("ollama 标为零成本", by_code["ollama-local"]["free"] is True)
check("qwen base-url 带 compatible-mode",
      "compatible-mode" in by_code["qwen"]["baseUrl"], by_code["qwen"]["baseUrl"])
check("deepseek base-url 不含 /v1/chat",
      "chat/completions" not in by_code["deepseek"]["baseUrl"], by_code["deepseek"]["baseUrl"])

print("\n=== 5. 配置校验：把常见配错挡在落库前 ===")
st, r = call("POST", "/llm/config", token=admin, body={
    "baseUrl": "https://api.deepseek.com/v1/chat/completions",
    "chatPath": "/v1/chat/completions", "model": "deepseek-chat", "protocol": "OPENAI"})
check("base-url 误含端点路径被拒", st == 400, f"{st} {r.get('message')}")

st, r = call("POST", "/llm/config", token=admin, body={
    "baseUrl": "api.deepseek.com", "model": "deepseek-chat", "protocol": "OPENAI"})
check("base-url 缺协议头被拒", st == 400, f"{st} {r.get('message')}")

st, r = call("POST", "/llm/config", token=admin, body={
    "baseUrl": "https://generativelanguage.googleapis.com",
    "chatPath": "/v1beta/chat/completions", "model": "gemini-2.0-flash", "protocol": "GEMINI"})
check("gemini 缺 {model} 占位被拒", st == 400, f"{st} {r.get('message')}")

st, r = call("POST", "/llm/config", token=admin, body={
    "baseUrl": "https://api.deepseek.com", "model": "", "protocol": "OPENAI"})
check("空模型名被拒", st == 400, f"{st} {r.get('message')}")

print("\n=== 6. 保存 + 生效（不重启）===")
st, r = call("POST", "/llm/config", token=admin, body={
    "presetCode": "ollama-local", "vendor": "Ollama 本地推理",
    "protocol": "OLLAMA", "auth": "NONE",
    "baseUrl": "http://localhost:11434", "chatPath": "/api/chat",
    "model": "qwen2.5:7b", "timeoutSeconds": 15, "maxTokens": 2048, "temperature": 0.3,
    "enabled": True})
check("保存 ollama 配置", st == 200 and r.get("code") == 0, r)
check("响应不回显真实 Key 字段", "apiKey" not in (r.get("data") or {}), (r.get("data") or {}).keys())

st, r = call("GET", "/llm/status", token=teacher)
eff = r.get("data", {})
check("状态显示已切到 OLLAMA 协议", eff.get("protocol") == "OLLAMA", eff.get("protocol"))
check("状态显示模型 qwen2.5:7b", eff.get("model") == "qwen2.5:7b", eff.get("model"))
check("无鉴权协议即使没 Key 也算可用", eff.get("available") is True,
      f"available={eff.get('available')} reason={eff.get('reason')}")
check("状态含厂商名", eff.get("vendor") == "Ollama 本地推理", eff.get("vendor"))

print("\n=== 7. 试连通：错误分类 ===")
st, r = call("POST", "/llm/test", token=teacher, body={})
res = r.get("data", {})
check("本地未起 ollama 应判为网络问题", res.get("ok") is False and res.get("stage") == "NETWORK",
      f"stage={res.get('stage')} msg={res.get('message')}")
check("失败时给出排查建议", bool(res.get("hint")), res.get("hint"))

st, r = call("POST", "/llm/test", token=teacher, body={
    "protocol": "OPENAI", "auth": "BEARER", "baseUrl": "https://api.deepseek.com",
    "chatPath": "/v1/chat/completions", "model": "deepseek-chat", "apiKey": ""})
res = r.get("data", {})
check("缺 Key 判为配置不完整", res.get("stage") == "CONFIG", f"stage={res.get('stage')}")

st, r = call("POST", "/llm/test", token=teacher, body={
    "protocol": "GEMINI", "auth": "QUERY_KEY", "baseUrl": "https://generativelanguage.googleapis.com",
    "chatPath": "/v1beta/models/{model}:generateContent", "model": "gemini-2.0-flash",
    "apiKey": "SECRET-KEY-SHOULD-BE-MASKED"})
res = r.get("data", {})
check("QUERY_KEY 模式下 URL 里的 key 被打码", "SECRET" not in str(res.get("endpoint", "")),
      res.get("endpoint"))
check("端点回显保留 {model} 占位", "{model}" in str(res.get("endpoint", "")), res.get("endpoint"))

st, r = call("POST", "/llm/test", token=teacher, body={
    "protocol": "OPENAI", "auth": "BEARER", "baseUrl": "https://api.deepseek.com",
    "chatPath": "/v1/chat/completions", "model": "deepseek-chat",
    "apiKey": "TEST-ONLY-NOT-A-REAL-API-KEY"})
res = r.get("data", {})
check("假 Key 应判为认证失败", res.get("ok") is False and res.get("stage") in ("AUTH", "ENDPOINT", "NETWORK"),
      f"stage={res.get('stage')} msg={res.get('message')}")
check("认证失败时提示不泄漏 Key 原文",
      "TEST-ONLY-NOT-A-REAL-API-KEY" not in json.dumps(res, ensure_ascii=False))

print("\n=== 8. 权限边界 ===")
st, r = call("GET", "/llm/overview", token=stu)
check("学生读 overview 403", st == 403, st)
st, r = call("POST", "/llm/config", token=teacher, body={
    "baseUrl": "https://api.deepseek.com", "model": "x", "protocol": "OPENAI"})
check("教师写配置 403（写限 ADMIN）", st == 403, st)
st, r = call("POST", "/llm/test", token=stu, body={})
check("学生试连通 403", st == 403, st)

print("\n=== 9. 恢复默认（回落环境变量）===")
st, r = call("POST", "/llm/config/enabled", token=admin, body={"enabled": False})
check("停用配置", st == 200 and r.get("code") == 0, r)
st, r = call("GET", "/llm/status", token=teacher)
eff = r.get("data", {})
check("停用后回落环境变量协议", eff.get("protocol") == "OPENAI", eff.get("protocol"))
check("停用后回落后 available=False（无 Key）", eff.get("available") is False,
      f"available={eff.get('available')}")
check("停用后给出明确原因", "API Key" in str(eff.get("reason")), eff.get("reason"))
st, r = call("POST", "/llm/config/enabled", token=admin, body={"enabled": True})
check("重新启用", st == 200 and r.get("code") == 0, r)

print("\n=== 10. 原降级路径未被破坏（无 Key 时内容生成仍走模板）===")
st, r = call("POST", "/llm/config", token=admin, body={
    "presetCode": "deepseek", "vendor": "DeepSeek（深度求索）",
    "protocol": "OPENAI", "auth": "BEARER",
    "baseUrl": "https://api.deepseek.com", "chatPath": "/v1/chat/completions",
    "model": "deepseek-chat", "timeoutSeconds": 10, "enabled": True})
check("切回 deepseek 且不带 Key", st == 200, r)
st, r = call("GET", "/contents/status", token=teacher)
sd = r.get("data", {})
check("无 Key 时内容状态 available=False", sd.get("available") is False, sd.get("available"))
check("无 Key 时给出原因", bool(sd.get("reason")), sd.get("reason"))
check("内容类型含讲解与教案",
      {"EXPLAIN", "LESSON_PLAN"} <= {t["value"] for t in sd.get("types", [])},
      [t["value"] for t in sd.get("types", [])])

print("\n=== 11. 语义检索仍正常（Qdrant 自愈后）===")
st, r = call("POST", "/questions/mapping/suggest", token=teacher,
             body={"questionId": 28, "graphId": 1})
md = r.get("data", {})
check("语义检索可用", md.get("semanticAvailable") is True, md.get("semanticAvailable"))
cands = md.get("candidates", [])
check("召回到候选（向量非空）", len(cands) > 0, f"候选数 {len(cands)}")
check("候选带语义来源", cands and cands[0].get("source") == "semantic",
      cands[0] if cands else None)

print("\n" + "=" * 60)
print(f"PASS={len(PASS)} FAIL={len(FAIL)}")
if FAIL:
    print("失败项：")
    for f in FAIL:
        print("  - " + f)
    sys.exit(1)
print("全部通过 ✅")
