import json
import subprocess
import sys

B = "http://localhost:8090/api"


def curl(method, path, token=None, body=None):
    cmd = ["curl", "-s", "-X", method, B + path, "-H", "Content-Type: application/json"]
    if token:
        cmd += ["-H", "Authorization: Bearer " + token]
    if body is not None:
        cmd += ["-d", json.dumps(body, ensure_ascii=False)]
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8").stdout
    try:
        return json.loads(out)
    except Exception:
        return {"_raw": out}


def login(u, p):
    r = curl("POST", "/auth/login", body={"username": u, "password": p})
    return r.get("data", {}).get("token")


def show(title, obj, keys=None):
    print("\n=== " + title + " ===")
    if obj is None:
        print("  (null)")
        return
    if isinstance(obj, list):
        print(f"  list[{len(obj)}]")
        for it in obj[:4]:
            print("   -", json.dumps(it, ensure_ascii=False)[:220])
        return
    d = obj.get("data", obj) if isinstance(obj, dict) and "code" in obj else obj
    if isinstance(d, dict) and keys:
        for k in keys:
            v = d.get(k)
            print(f"  {k} = {json.dumps(v, ensure_ascii=False)[:300] if not isinstance(v, (str, int, float, type(None))) else v}")
    else:
        print("  ", json.dumps(d, ensure_ascii=False)[:500])


TT = login("teacher", "teacher123")
ST = login("stu1", "123456")
AT = login("admin", "admin123")
print("tokens ok:", bool(TT), bool(ST), bool(AT))
if not (TT and ST and AT):
    sys.exit(1)

# ---------- 1. 学生首次打开学习首页：应拿到一条「入学诊断」任务 ----------
r = curl("GET", "/tasks/my/today", ST)
d = r.get("data", {})
print("\n=== 1 今日清单（诊断前） ===")
print("  generated =", d.get("generated"), "| hasDiagnosis =", d.get("hasDiagnosis"),
      "| diagnosisCompleted =", d.get("diagnosisCompleted"), "| pending =", d.get("pendingCount"))
print("  notice =", d.get("notice"))
diag_task = next((t for t in d.get("tasks", []) if t.get("type") == "DIAGNOSIS"), None)
print("  诊断任务 id =", diag_task and diag_task.get("id"))

# ---------- 2. 点「去做诊断」 ----------
r = curl("POST", f"/tasks/my/{diag_task['id']}/start", ST)
exam_id = r.get("data", {}).get("examId")
print("\n=== 2 开始诊断任务 ===")
print("  examId =", exam_id, "| created =", r.get("data", {}).get("created"))

# ---------- 3. 取题 ----------
r = curl("GET", f"/exams/my/{exam_id}", ST)
paper = r.get("data", {})
qs = paper.get("questions", [])
print("\n=== 3 诊断卷 ·", paper.get("name"), f"（{len(qs)} 题） ===")
print("  answerRevealed =", paper.get("answerRevealed"))
print("  第 1 题带答案字段？", "answer" in (qs[0] if qs else {}))

# 故意做对一半、做错一半，便于观察掌握度初始化
items = []
for i, q in enumerate(qs):
    ans = q.get("answer")  # 学生端不应有 answer，这里取不到就随便填
    items.append({"questionId": q["questionId"],
                  "answer": ("__wrong__" if i % 2 else (ans if ans else "1"))})
r = curl("POST", f"/exams/my/{exam_id}/submit", ST, {"items": items})
show("4 交诊断卷", r, ["diagnosis", "status", "totalScore", "fullScore", "pendingCount"])

# ---------- 5. 诊断报告 ----------
r = curl("GET", "/diagnosis/my/report", ST)
rep = r.get("data", {})
print("\n=== 5 诊断报告 ===")
print("  hasDiagnosis =", rep.get("hasDiagnosis"), "| completed =", rep.get("completed"),
      "| avgRate =", rep.get("avgRate"), "| 题数 =", rep.get("questionCount"))
items_rep = rep.get("items", [])
print(f"  逐知识点 {len(items_rep)} 条，前 3 条掌握度起点：")
for it in items_rep[:3]:
    print("   -", it.get("nodeName"), "rate=", it.get("rate"), "mastery=", it.get("mastery"), it.get("level"))
print("  待补强", len(rep.get("weakNodes", [])), "个 / 已掌握", len(rep.get("solidNodes", [])), "个")
print("  推荐起点：", [x.get("nodeName") for x in rep.get("recommendations", [])[:3]])

# ---------- 6. 诊断后再看清单：应收掉诊断任务、生成学+练 ----------
r = curl("GET", "/tasks/my/today", ST)
d2 = r.get("data", {})
print("\n=== 6 今日清单（诊断后） ===")
print("  generated =", d2.get("generated"), "| notice =", d2.get("notice"))
print("  diagnosisCompleted =", d2.get("diagnosisCompleted"), "| pending =", d2.get("pendingCount"))
for t in d2.get("tasks", [])[:6]:
    print("   -", t.get("type"), t.get("status"), t.get("title"), "| mastery =", t.get("mastery"))

# ---------- 7. 教师生成并发布内容 ----------
node_id = next((t.get("nodeId") for t in d2.get("tasks", []) if t.get("type") == "LEARN"), None)
r = curl("POST", "/contents/generate", TT, {"nodeId": node_id, "type": "LECTURE"})
content_id = r.get("data", {}).get("id")
print("\n=== 7 教师生成内容 ===")
print("  nodeId =", node_id, "contentId =", content_id, "generatedBy =", r.get("data", {}).get("generatedBy"),
      "status =", r.get("data", {}).get("status"))
r = curl("PUT", f"/contents/{content_id}/status", TT, {"status": "PUBLISHED"})
print("  发布后 status =", r.get("data", {}).get("status"))

# ---------- 8. 学生看已发布内容 ----------
r = curl("GET", f"/contents/node/{node_id}/published", ST)
lst = r.get("data", [])
print("\n=== 8 学生读已发布内容 ===")
print("  条数 =", len(lst), "| 类型 =", [x.get("typeLabel") for x in lst])
print("  正文前 80 字 =", (lst[0].get("body") or "")[:80].replace("\n", " ") if lst else "-")

# ---------- 9. 学生开始「学」任务 ----------
learn_task = next((t for t in d2.get("tasks", []) if t.get("type") == "LEARN"), None)
r = curl("POST", f"/tasks/my/{learn_task['id']}/start", ST)
print("\n=== 9 开始「学」任务 ===")
print("  contentId =", r.get("data", {}).get("contentId"), "| hasContent =", r.get("data", {}).get("hasContent"))

# ---------- 10. 学生开始「练」任务 → 现出专项练习 ----------
prac_task = next((t for t in d2.get("tasks", []) if t.get("type") == "PRACTICE"), None)
r = curl("POST", f"/tasks/my/{prac_task['id']}/start", ST)
p_exam = r.get("data", {}).get("examId")
pqs = r.get("data", {}).get("questions", [])
print("\n=== 10 开始「练」任务 ===")
print("  专项练习 examId =", p_exam, "| 题数 =", len(pqs), "| 名称 =", r.get("data", {}).get("examId"))

# ---------- 11. 交专项练习（全对） → 掌握度应上升 ----------
items = [{"questionId": q["questionId"], "answer": "1"} for q in pqs]
r = curl("POST", f"/exams/my/{p_exam}/submit", ST, {"items": items})
show("11 交专项练习", r, ["diagnosis", "status", "totalScore", "fullScore"])

# ---------- 12. 掌握度对比 ----------
r = curl("GET", "/exams/mastery?studentId=1", ST)
ms = {x["nodeName"]: x["mastery"] for x in r.get("data", {}).get("nodes", [])}
print("\n=== 12 掌握度（诊断后 → 练完） ===")
print("  avgMastery =", r.get("data", {}).get("avgMastery"))
print("  目标知识点 mastery =", ms.get(learn_task.get("nodeName")))

# ---------- 13. 诊断任务应已收掉，练任务也应完成 ----------
r = curl("GET", "/tasks/my/today", ST)
d3 = r.get("data", {})
print("\n=== 13 清单状态 ===")
print("  doneToday =", d3.get("doneTodayCount"), "| pending =", d3.get("pendingCount"))
for t in d3.get("tasks", []):
    print("   -", t.get("type"), t.get("status"), t.get("title"))

# ---------- 14. 教师查看该生清单 + 诊断报告 ----------
r = curl("GET", "/tasks/student/1", TT)
print("\n=== 14 教师查看学生清单 ===")
print("  studentName =", r.get("data", {}).get("studentName"),
      "| pending =", r.get("data", {}).get("pendingCount"))
r = curl("GET", "/diagnosis/1/report", TT)
print("  教师读诊断报告 completed =", r.get("data", {}).get("completed"),
      "| avgRate =", r.get("data", {}).get("avgRate"))

# ---------- 15. 教师布置任务 ----------
r = curl("POST", "/tasks/teacher", TT,
         {"studentId": 1, "nodeId": node_id, "type": "REVIEW", "title": "今晚复习一遍小数乘法",
          "dueAt": "2026-10-20T18:00"})
show("15 教师布置任务", r, ["id", "type", "title", "source", "dueAt"])

# ---------- 16. 一键映射知识点（预演） ----------
r = curl("POST", "/questions/mapping/auto", TT,
         {"graphId": 1, "minConfidence": 0.6, "maxQuestions": 10, "dryRun": True})
show("16 一键映射（预演）", r, ["scanned", "mapped", "skippedMapped", "threshold", "mode"])

# ---------- 17. 单题识别 ----------
r = curl("POST", "/questions/mapping/suggest", TT, {"questionId": 1, "graphId": 1, "topK": 3})
d = r.get("data", {})
print("\n=== 17 单题识别 ===")
print("  mode =", d.get("mode"), "| 候选 =", [(c.get("nodeName"), round(c.get("score", 0), 3)) for c in d.get("candidates", [])])
print("  建议 =", [(m.get("nodeName"), m.get("confidence")) for m in d.get("matches", [])])

# ---------- 18. 推荐规则 CRUD ----------
r = curl("POST", "/recommend/rules", AT,
         {"name": "测试规则-强化补弱", "enabled": True, "isDefault": False,
          "config": {"masteryWeight": 0.8, "importanceWeight": 0.2, "weakThreshold": 0.5,
                     "solidThreshold": 0.75, "prereqBoost": 0.2, "consolidateRatio": 0.15,
                     "dailyTaskLimit": 4}})
rid = r.get("data", {}).get("id")
show("18 新建推荐规则", r, ["id", "name", "isDefault", "configMap"])
r = curl("GET", "/recommend/preview?studentId=1&limit=3", TT)
print("  规则生效后推荐前 3：", [(x.get("nodeName"), x.get("tier")) for x in r.get("data", [])])
r = curl("DELETE", f"/recommend/rules/{rid}", AT)
print("  删除测试规则 code =", r.get("code"))

# ---------- 19. 权限边界 ----------
for m, p, tok, tag in [
    ("GET", "/tasks/student/1", ST, "学生读他人清单"),
    ("GET", "/contents/status", ST, "学生读内容管理"),
    ("POST", "/recommend/rules", TT, "教师建推荐规则"),
    ("POST", "/contents/generate", ST, "学生生成内容"),
]:
    code = subprocess.run(
        ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-X", m, B + p,
         "-H", "Content-Type: application/json", "-H", "Authorization: Bearer " + tok,
         "-d", "{}"], capture_output=True, text=True).stdout
    print(f"\n=== 19 {tag} → http={code} ===")

print("\nDONE")
