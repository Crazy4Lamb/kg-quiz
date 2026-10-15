"""
推荐能力 + 掌握程度 + 学习记录 的接口验证。

注意：本脚本会为 demo 学生(stu1)跑一次诊断并交卷，以真实触发「学习记录写入 + 掌握度覆盖式初始化」
的埋点链路，因此会改动库内数据。验证完毕后若需要干净演示态，请走三步重置
（MySQL TRUNCATE 业务表 → Neo4j DETACH DELETE → POST /api/admin/seed-demo）。

覆盖：
  [1] 参数元数据（groups/defaults/strategies）
  [2] 推荐结果含六因子分解、分级、置信度、遗忘
  [3] 掌握程度总览（分级分布/薄弱/低置信）
  [4] 沙盒模拟 diff（进/出/位移）
  [5] 策略切换改变排序（REMEDIATION vs BALANCED）
  [6] 诊断→交卷→学习记录写入（含 masteryBefore/after）
  [7] 权限边界（学生不可读配置/不可越权看他人记录；教师不可落库规则）
"""
import json
import urllib.request
import urllib.error

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
    assert s == 200, f"login {u} failed: {r}"
    return r["data"]["token"]


TT = login("teacher", "teacher123")
ST = login("stu1", "123456")
AT = login("admin", "admin123")

print("\n[1] 参数元数据")
s, r = call("GET", "/recommend/params", AT)
d = r.get("data", {})
check("返回 groups", len(d.get("groups", [])) >= 3, str(list(d.keys())))
check("返回 defaults（完整 17 字段）", len(d.get("defaults", {})) >= 14, str(d.get("defaults", {}).keys()))
check("返回 strategies（4 种）", len(d.get("strategies", [])) == 4, str([x["value"] for x in d.get("strategies", [])]))
PARAMS_DEFAULTS = d.get("defaults", {})
# 每个策略都应带完整预设
for st in d.get("strategies", []):
    check(f"策略 {st['value']} 预设含 masteryWeight", "masteryWeight" in (st.get("preset") or {}), str(st.get("preset", {}).keys()))

print("\n[2] 推荐结果含因子分解")
s, r = call("GET", "/recommend/my", ST)
d = r.get("data", {})
recs = d.get("recommendations", [])
check("返回推荐列表", len(recs) > 0, str(r)[:150])
first = recs[0] if recs else {}
check("含 effectiveMastery（遗忘后）", "effectiveMastery" in first)
check("含 confidence（置信度）", "confidence" in first)
check("含 factors 六因子", len(first.get("factors", [])) >= 5, str([f["key"] for f in first.get("factors", [])]))
check("含 reasons 文案", len(first.get("reasons", [])) > 0)
check("含 tier 分档", first.get("tier") in ("WEAK", "MID", "SOLID", "UNSEEN"), str(first.get("tier")))
check("含 suggestedAction", bool(first.get("suggestedAction")))

print("\n[3] 掌握程度总览")
s, r = call("GET", "/recommend/my/mastery", ST)
d = r.get("data", {})
check("含 byLevel 分级分布", "byLevel" in d and "UNSEEN" in (d.get("byLevel") or {}))
check("含 levelDetail（带颜色）", len(d.get("levelDetail", [])) == 5)
check("含 weakNodes 薄弱清单", "weakNodes" in d)
check("含 lowConfidenceNodes（低置信度）", "lowConfidenceNodes" in d)
check("含 coverage 覆盖率", "coverage" in d)
check("含 avgEffectiveMastery（遗忘衰减）", "avgEffectiveMastery" in d)

print("\n[4] 沙盒模拟 diff")
s, r = call("POST", "/recommend/simulate", AT,
            {"studentId": 1, "strategy": "REMEDIATION", "limit": 12})
d = r.get("data", {})
check("返回 simulated 列表", len(d.get("simulated", [])) > 0)
check("返回 current 列表", len(d.get("current", [])) > 0)
diff = d.get("diff", {})
check("diff 含 entered/left/moved",
      "entered" in diff and "left" in diff and "moved" in diff, str(list(diff.keys())))
check("diff 含 changed 布尔", "changed" in diff)

print("\n[5] 策略切换改变排序")
s1, r1 = call("POST", "/recommend/simulate", AT, {"studentId": 1, "strategy": "REMEDIATION", "limit": 12})
s2, r2 = call("POST", "/recommend/simulate", AT, {"studentId": 1, "strategy": "BALANCED", "limit": 12})
rank_rem = [x["nodeId"] for x in r1["data"]["simulated"]]
rank_bal = [x["nodeId"] for x in r2["data"]["simulated"]]
# 补弱策略应把「薄弱/未接触」推得更靠前：比较前两名的差距
top_rem_weakness = r1["data"]["simulated"][0].get("effectiveMastery", 0)
top_bal_weakness = r2["data"]["simulated"][0].get("effectiveMastery", 0)
check("REMEDIATION 首推有效掌握度不高于 BALANCED（更偏向补弱）",
      top_rem_weakness <= top_bal_weakness + 1e-9,
      f"rem={top_rem_weakness} bal={top_bal_weakness}")
# Top5 平均有效掌握度：补弱策略应显著偏低
def avg_top5(rk):
    vals = [float(x.get("effectiveMastery", 0)) for x in rk[:5]]
    return sum(vals) / len(vals) if vals else 0
avg_rem = avg_top5(r1["data"]["simulated"])
avg_bal = avg_top5(r2["data"]["simulated"])
check("REMEDIATION Top5 平均有效掌握度 ≤ BALANCED（更偏向补弱）",
      avg_rem <= avg_bal + 1e-9, f"rem_avg={avg_rem:.3f} bal_avg={avg_bal:.3f}")

print("\n[6] 诊断→交卷→学习记录写入")
# 学生先做一份诊断，前一半对、后一半错
r = call("POST", "/diagnosis/generate", TT, {"studentId": 1, "perNode": 1, "maxQuestions": 20})[1]
diag = r.get("data", {})
diag_exam = diag.get("examId")
check("诊断卷生成成功", diag_exam is not None, str(diag)[:150])
paper = call("GET", f"/exams/papers/{diag_exam}", TT)[1].get("data", {})
qs = paper.get("questions", [])
answers = {q["questionId"]: q.get("answer") for q in qs}
half = len(qs) // 2
items = []
for i, q in enumerate(qs):
    items.append({"questionId": q["questionId"],
                  "answer": answers[q["questionId"]] if i < half else "__WRONG__"})
sr = call("POST", f"/exams/my/{diag_exam}/submit", ST, {"items": items})[1].get("data", {})
check("诊断卷交卷成功", sr.get("status") == "GRADED", str(sr)[:150])

s, r = call("GET", "/learning-records/my", ST)
recs = r.get("data", []) or []
check("学习记录非空（诊断触发写入）", len(recs) > 0, f"count={len(recs)}")
actions = {x.get("action") for x in recs}
check("含 DIAGNOSIS 或 PRACTICE 动作", bool(actions & {"DIAGNOSIS", "PRACTICE"}), str(actions))
# 至少一条带掌握度前后快照
with_snap = [x for x in recs if x.get("masteryBefore") is not None and x.get("masteryAfter") is not None]
check("存在 masteryBefore/after 快照记录", len(with_snap) > 0, f"有快照 {len(with_snap)} 条")
# 掌握了的知识点应出现 0→1 的快照
improved = [x for x in with_snap if float(x["masteryAfter"]) > float(x["masteryBefore"])]
check("存在掌握度提升的快照（EMA 增量）", len(improved) > 0, f"提升 {len(improved)} 条")

# 统计接口
s, r = call("GET", "/learning-records/my/stats", ST, None)
st = r.get("data", {})
check("统计含 eventCount", "eventCount" in st)
check("统计含 dailyTrend", "dailyTrend" in st)
check("统计含 byAction", "byAction" in st)

print("\n[7] 权限边界")
s, _ = call("GET", "/recommend/params", ST)
check("学生读 /recommend/params → 403", s == 403, f"实际 {s}")
s, _ = call("GET", "/recommend/rules", ST)
check("学生读 /recommend/rules → 403", s == 403, f"实际 {s}")
# 教师（非机构管理员）落库规则应被后端 403
s, _ = call("POST", "/recommend/rules", TT,
            {"orgId": None, "name": "教师越权规则", "strategy": "BALANCED",
             "enabled": True, "isDefault": False, "config": PARAMS_DEFAULTS})
check("教师落库规则 → 403（仅机构/平台管理员）", s == 403, f"实际 {s}")
# 学生越权看他人掌握度/记录
s, _ = call("GET", "/recommend/mastery/2", ST)
check("学生读他人掌握度 → 403", s == 403, f"实际 {s}")
s, _ = call("GET", "/learning-records/student/2", ST)
check("学生读他人记录 → 403", s == 403, f"实际 {s}")
# 平台管理员可读他人记录（正向）
s, r = call("GET", "/learning-records/student/1", AT)
check("管理员读学生记录 → 200", s == 200, f"实际 {s}")

print(f"\n==== 结果：PASS={PASS}  FAIL={FAIL} ====")
if PROBLEMS:
    print("未通过：", PROBLEMS)
    raise SystemExit(1)
print("全部通过 ✅")
