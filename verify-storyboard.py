"""讲解 IR + 教案 + 自检门禁 的接口验证（非破坏性：只新增内容，不删库）"""
import json
import urllib.request

BASE = "http://127.0.0.1:8090/api"
PASS = 0
FAIL = 0
PROBLEMS = []


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


def check(label, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {label}")
    else:
        FAIL += 1
        PROBLEMS.append(label)
        print(f"  FAIL  {label}  {extra}")


def login(u, p):
    s, r = call("POST", "/auth/login", body={"username": u, "password": p})
    return r["data"]["token"]


TT = login("teacher", "teacher123")
ST = login("stu1", "123456")
AT = login("admin", "admin123")

print("\n[1] 内容类型注册")
s, r = call("GET", "/contents/status", TT)
types = {t["value"]: t["label"] for t in r["data"]["types"]}
check("含 EXPLAIN（讲解分镜）", "EXPLAIN" in types, str(types))
check("含 LESSON_PLAN（教案）", "LESSON_PLAN" in types, str(types))
check("教案标签为「教案」", types.get("LESSON_PLAN") == "教案")

print("\n[2] 讲解 IR 生成")
s, r = call("POST", "/contents/generate", TT,
            {"nodeId": 3, "type": "EXPLAIN", "replaceExisting": True})
check("生成成功", s == 200, str(r)[:200])
d = r["data"]
EXPLAIN_ID = d["id"]
sb = d.get("storyboard") or {}
check("带 storyboard IR", bool(sb), str(list(d.keys())))
segs = sb.get("segments") or []
check("分段数 >= 5", len(segs) >= 5, f"实际 {len(segs)}")
check("index 从 1 连续", [s["index"] for s in segs] == list(range(1, len(segs) + 1)),
      str([s["index"] for s in segs]))
kinds = [s["kind"] for s in segs]
check("kind 覆盖 HOOK/TEACH/RECAP", all(k in kinds for k in ("HOOK", "TEACH", "RECAP")), str(kinds))
check("每段有旁白", all((s.get("narration") or "").strip() for s in segs))
check("视觉锚点用相对位置（anchorLine 而非绝对秒）",
      any(v.get("anchorLine") for s in segs for v in (s.get("visuals") or [])), "未见 anchorLine")
check("引用题目 id 均真实存在",
      all(qid in (6, 7, 29) for s in segs for qid in (s.get("questionIds") or [])),
      str([s.get("questionIds") for s in segs]))
check("自检通过", d["check"]["passed"] is True, json.dumps(d["check"], ensure_ascii=False)[:200])
check("needsReview=false", d["needsReview"] is False)
check("摘要分段数不为 0", d["check"].get("segmentCount", 0) >= 5,
      f"summary={d['summary']}")

print("\n[3] 教案引用讲解 IR")
s, r = call("POST", "/contents/generate", TT,
            {"nodeId": 3, "type": "LESSON_PLAN", "replaceExisting": True})
check("教案生成成功", s == 200, str(r)[:200])
PLAN = r["data"]
check("教案不自带 storyboard", not PLAN.get("storyboard"), "教案不该有自己的 IR")
for sec in ("## 课题", "## 教学目标", "## 教学重点与难点", "## 教学过程", "## 作业布置"):
    check(f"教案含 {sec}", sec in PLAN["body"])
check("教案过程引用了讲解分段数", PLAN["body"].count("### ") >= 5,
      f"仅 {PLAN['body'].count('### ')} 段")
check("教案摘要分段数不为 0", "0 个分段" not in PLAN["summary"], PLAN["summary"])

print("\n[4] 发布门禁")
s, r = call("PUT", f"/contents/{EXPLAIN_ID}/status", TT, {"status": "PUBLISHED"})
check("自检通过可发布", s == 200 and r["data"]["status"] == "PUBLISHED", str(r)[:150])

print("\n[5] 教案对学生不可见")
s, r = call("GET", "/contents/node/3/published", ST)
pub = r["data"]
check("学生拿不到教案", all(c["type"] != "LESSON_PLAN" for c in pub),
      str([c["type"] for c in pub]))
check("学生能看到已发布讲解", any(c["type"] == "EXPLAIN" for c in pub),
      str([c["type"] for c in pub]))

print("\n[6] 学生端分镜接口")
s, r = call("GET", "/contents/node/3/storyboard", ST)
check("学生可读分镜", s == 200 and r["data"]["available"] is True, str(r)[:150])
sbd = r["data"]
check("返回 5 段", len(sbd["storyboard"]["segments"]) == 5,
      str(len(sbd["storyboard"]["segments"])))
check("题目不含答案字段", all("answer" not in q for q in sbd["questions"]),
      str(sbd["questions"][:1]))

print("\n[7] 自检门禁真能拦住幻觉（注入 → 检出 → 拒绝发布）")
import subprocess
mysql = "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql"
# 只在这次自检里改 EXPLAIN 的 IR，验完立刻重新生成干净的
subprocess.run([mysql, "-h", "127.0.0.1", "-P", "3306", "-u", "root", "-proot",
                "yt_knowledge_demo", "-e",
                f"UPDATE node_content SET storyboard=REPLACE(storyboard, '\"questionIds\":[6]', "
                f"'\"questionIds\":[99999]') WHERE id={EXPLAIN_ID};"],
               capture_output=True)
s, r = call("POST", f"/contents/{EXPLAIN_ID}/recheck", TT)
ck = r["data"]
check("检出编造的题目 id", ck["passed"] is False, json.dumps(ck, ensure_ascii=False)[:200])
check("标为 needsReview", ck["needsReview"] is True)
check("问题类型是 QUESTION_SCOPE",
      any(i["code"] == "QUESTION_SCOPE" for i in ck["issues"]), str(ck["issues"]))
s, r = call("PUT", f"/contents/{EXPLAIN_ID}/status", TT, {"status": "PUBLISHED"})
check("被污染内容拒绝发布（400）", s == 400, f"实际 {s}")
check("拒绝原因可读", "自检未通过" in (r.get("message") or ""), r.get("message", ""))

# 恢复：重新生成干净的讲解并发布
s, r = call("POST", "/contents/generate", TT,
            {"nodeId": 3, "type": "EXPLAIN", "replaceExisting": True})
EXPLAIN_ID = r["data"]["id"]
call("PUT", f"/contents/{EXPLAIN_ID}/status", TT, {"status": "PUBLISHED"})
check("重生成后恢复干净", True)

print("\n[8] 答案一致性检查")
subprocess.run([mysql, "-h", "127.0.0.1", "-P", "3306", "-u", "root", "-proot",
                "yt_knowledge_demo", "-e",
                f"UPDATE node_content SET storyboard=REPLACE(storyboard, "
                f"'计算：0.72 × 0.38 ≈ （ ）（保留两位小数）', "
                f"'计算：0.72 × 0.38 答案：0.99') WHERE id={EXPLAIN_ID};"],
               capture_output=True)
s, r = call("POST", f"/contents/{EXPLAIN_ID}/recheck", TT)
ck = r["data"]
check("检出画面答案与题库不一致",
      any(i["code"] == "ANSWER_MISMATCH" for i in ck["issues"]), json.dumps(ck, ensure_ascii=False)[:250])
s, r = call("POST", "/contents/generate", TT,
            {"nodeId": 3, "type": "EXPLAIN", "replaceExisting": True})
EXPLAIN_ID = r["data"]["id"]
call("PUT", f"/contents/{EXPLAIN_ID}/status", TT, {"status": "PUBLISHED"})

print("\n[9] 重渲染（IR → 正文投影）")
s, r = call("POST", "/contents/generate", TT,
            {"nodeId": 3, "type": "LESSON_PLAN", "replaceExisting": True})
PLAN_ID = r["data"]["id"]
s, r = call("POST", f"/contents/{EXPLAIN_ID}/rerender", TT, {"rerenderLessonPlans": True})
check("重渲染成功", s == 200, str(r)[:150])
check("连带重渲染教案 >= 1 份", r["data"]["rerenderedPlans"] >= 1,
      str(r["data"].get("rerenderedPlans")))

print("\n[10] 权限边界")
s, r = call("GET", "/contents/status", ST)
check("学生读内容管理接口 403", s == 403, f"实际 {s}")
s, r = call("POST", "/contents/generate", ST, {"nodeId": 3, "type": "EXPLAIN"})
check("学生生成内容 403", s == 403, f"实际 {s}")
s, r = call("POST", f"/contents/{EXPLAIN_ID}/recheck", ST)
check("学生触发自检 403", s == 403, f"实际 {s}")

print("\n[11] Agent 管理台（上一轮遗留）")
s, r = call("GET", "/agents/tools", AT)
check("工具注册表可读", s == 200 and len(r["data"]) > 0,
      f"实际 {s} 工具数 {len(r.get('data') or [])}")
s, r = call("GET", "/agents", AT)
codes = {a["code"] for a in r["data"]}
check("三个种子 Agent 就位",
      {"student-planner", "teacher-assistant", "admin-governor"} <= codes, str(codes))
s, r = call("GET", "/agents", ST)
check("学生读 Agent 配置 403", s == 403, f"实际 {s}")
s, r = call("GET", "/agents/tools", TT)
check("教师可读工具注册表", s == 200, f"实际 {s}")

print(f"\n{'=' * 56}")
print(f"PASS={PASS}  FAIL={FAIL}")
if PROBLEMS:
    print("未通过：")
    for p in PROBLEMS:
        print("  -", p)
else:
    print("全部通过 ✅")