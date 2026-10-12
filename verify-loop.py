"""诊 → 学 → 练 闭环端到端验证（在干净种子库上跑一遍）。

覆盖：
  1. 种子导入（图谱/题库/学生）与向量索引
  2. 教师给学生生成诊断卷 → 学生作答 → 覆盖式初始化掌握度 → 诊断报告
  3. 诊断后自动排学习任务（学 + 练），教师发布教学内容，学生读内容
  4. 学生「练」→ 现出专项练习 → 交卷 → EMA 更新掌握度 → 任务自动收口
  5. 教师查看学生学习清单 / 布置任务
  6. 题目批量导入（不带知识点）→ 一键映射知识点
  7. 权限边界（学生越权、跨角色接口）
"""
import json
import subprocess
import sys

B = "http://localhost:8090/api"
FAIL = []


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
        return {"_raw": out, "code": -1}


def http_code(method, path, token=None, body=None):
    cmd = ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-X", method, B + path,
           "-H", "Content-Type: application/json"]
    if token:
        cmd += ["-H", "Authorization: Bearer " + token]
    if body is not None:
        cmd += ["-d", json.dumps(body, ensure_ascii=False)]
    return subprocess.run(cmd, capture_output=True, text=True).stdout


def check(cond, title, detail=""):
    mark = "PASS" if cond else "FAIL"
    if not cond:
        FAIL.append(title)
    print(f"  [{mark}] {title}" + (f" — {detail}" if detail else ""))
    return cond


def login(u, p):
    return curl("POST", "/auth/login", body={"username": u, "password": p}).get("data", {}).get("token")


AT, TT, ST = login("admin", "admin123"), login("teacher", "teacher123"), login("stu1", "123456")
if not (AT and TT and ST):
    print("登录失败，退出")
    sys.exit(1)

print("\n########## 0. 种子数据导入 ##########")
r = curl("POST", "/admin/seed-demo", AT)
d = r.get("data", {})
print("  seed:", json.dumps(d, ensure_ascii=False)[:300])
check(r.get("code") == 0, "种子导入成功")
check((d.get("nodes") or d.get("nodeCount") or 0) >= 12, "节点数 ≥ 12", str(d.get("nodes")))
r = curl("POST", "/admin/vector-index/rebuild", AT)
check(r.get("code") == 0, "向量索引重建", json.dumps(r.get("data", {}), ensure_ascii=False)[:120])

print("\n########## 1. 学生首页（诊断前） ##########")
d = curl("GET", "/tasks/my/today", ST).get("data", {})
check(d.get("hasDiagnosis") is False, "尚无诊断卷")
check(d.get("diagnosisCompleted") is False, "诊断未完成")
check(d.get("generated") is True, "首次打开自动生成任务", str(d.get("notice")))
diag_task = next((t for t in d.get("tasks", []) if t.get("type") == "DIAGNOSIS"), None)
check(diag_task is not None, "生成「入学诊断」任务")

print("\n########## 2. 教师生成诊断卷并读取答案 ##########")
r = curl("POST", "/diagnosis/generate", TT, {"studentId": 1, "perNode": 1, "maxQuestions": 20})
diag = r.get("data", {})
diag_exam = diag.get("examId")
check(r.get("code") == 0, "诊断卷生成成功")
print("     examId =", diag_exam, "| 题数 =", diag.get("questionCount"),
      "| 覆盖 =", f"{diag.get('coveredNodes')}/{diag.get('totalNodes')}")
check(diag.get("questionCount", 0) >= 10, "覆盖全图谱（≥10 题）")
check(len(diag.get("uncovered", [])) == 0, "所有知识点都有题")

paper = curl("GET", f"/exams/papers/{diag_exam}", TT).get("data", {})
qs = paper.get("questions", [])
answers = {q["questionId"]: q.get("answer") for q in qs}
check(len(answers) == len(qs) and all(answers.values()), "教师侧可读到参考答案（用于自动比对）")

print("\n########## 3. 学生做诊断（前一半做对、后一半做错） ##########")
stu_paper = curl("GET", f"/exams/my/{diag_exam}", ST).get("data", {})
check(stu_paper.get("answerRevealed") is False, "学生取题时未泄露答案")
check("answer" not in (stu_paper.get("questions") or [{}])[0], "题目对象不含 answer 字段")

items = []
half = len(qs) // 2
for i, q in enumerate(qs):
    items.append({"questionId": q["questionId"],
                  "answer": answers[q["questionId"]] if i < half else "__WRONG__"})
r = curl("POST", f"/exams/my/{diag_exam}/submit", ST, {"items": items})
sub = r.get("data", {})
check(sub.get("diagnosis") is True, "标记为诊断卷", str(sub.get("source")))
print("     得分:", sub.get("totalScore"), "/", sub.get("fullScore"), "| 状态:", sub.get("status"))

print("\n########## 4. 诊断报告（掌握度起点应为覆盖式写入） ##########")
rep = curl("GET", "/diagnosis/my/report", ST).get("data", {})
check(rep.get("completed") is True, "诊断已完成")
items_rep = rep.get("items", [])
by_rate = sorted(items_rep, key=lambda x: x.get("rate") or 0)
print("     逐知识点掌握度起点（升序前 3）:")
for it in by_rate[:3]:
    print("       -", it.get("nodeName"), "rate =", it.get("rate"), "mastery =", it.get("mastery"), it.get("level"))
print("     avgRate =", rep.get("avgRate"), "| 待补强", len(rep.get("weakNodes", [])),
      "| 已掌握", len(rep.get("solidNodes", [])), "| 无题目未覆盖", rep.get("uncoveredNodes"))
check(len(rep.get("weakNodes", [])) > 0 and len(rep.get("solidNodes", [])) > 0,
      "诊断结果呈现分层（既有待补强也有已掌握）")
mastery_vals = [float(x.get("mastery") or 0) for x in items_rep]
check(any(v > 0.9 for v in mastery_vals) and any(v < 0.01 for v in mastery_vals),
      "掌握度被直接写成 1.0 / 0.0（非 EMA 压缩）", f"max={max(mastery_vals)} min={min(mastery_vals)}")
check(len(rep.get("recommendations", [])) > 0, "给出学习起点推荐")

print("\n########## 5. 交诊断卷后自动收口诊断任务 ##########")
d = curl("GET", "/tasks/my/today", ST).get("data", {})
check(d.get("diagnosisCompleted") is True, "首页识别到诊断已完成")
diag_tasks = [t for t in d.get("tasks", []) if t.get("type") == "DIAGNOSIS"]
check(all(t.get("status") == "DONE" for t in diag_tasks) and diag_tasks,
      "诊断任务已自动标记完成")
learn_tasks = [t for t in d.get("tasks", []) if t.get("type") == "LEARN"]
prac_tasks = [t for t in d.get("tasks", []) if t.get("type") == "PRACTICE"]
check(len(learn_tasks) > 0 and len(prac_tasks) > 0, "已按掌握度排出「学 + 练」任务",
      f"学 {len(learn_tasks)} / 练 {len(prac_tasks)}")
first_learn = sorted(learn_tasks, key=lambda t: t.get("priority") or 0)[0]
print("     首个学习任务:", first_learn.get("title"), "|", first_learn.get("reason"))
node_id = first_learn.get("nodeId")

print("\n########## 6. 教学内容：生成 → 发布 → 学生阅读 ##########")
st = curl("GET", "/contents/status", TT).get("data", {})
print("     大模型可用 =", st.get("available"), "|", (st.get("reason") or "")[:60])
c = curl("POST", "/contents/generate", TT, {"nodeId": node_id, "type": "LECTURE"}).get("data", {})
check(c.get("id") is not None, "生成讲义成功")
check(c.get("status") == "DRAFT", "新内容为草稿态")
print("     generatedBy =", c.get("generatedBy"), "| 正文", len(c.get("body") or ""), "字")
before = curl("GET", f"/contents/node/{node_id}/published", ST).get("data", [])
check(len(before) == 0, "草稿对学生不可见")
curl("PUT", f"/contents/{c['id']}/status", TT, {"status": "PUBLISHED"})
after = curl("GET", f"/contents/node/{node_id}/published", ST).get("data", [])
check(len(after) == 1, "发布后学生可见")

print("\n########## 7. 学生「学」→ 拿到内容 ##########")
r = curl("POST", f"/tasks/my/{first_learn['id']}/start", ST).get("data", {})
check(r.get("hasContent") is True and r.get("contentId") == c.get("id"),
      "「学」任务定位到已发布内容", f"contentId={r.get('contentId')}")

print("\n########## 8. 学生「练」→ 现出专项练习 → 交卷 → EMA 更新 ##########")
# 任务清单里的练任务都指向最薄弱的 5 个知识点（掌握度 0），答错也观察不到 EMA 变化。
# 所以这里挑一个「已掌握」的知识点直接发起专项练习，答错后掌握度必须被拉下来。
m_all = curl("GET", "/exams/mastery?studentId=1", ST).get("data", {}).get("nodes", [])
solid = [x for x in m_all if (x.get("mastery") or 0) >= 0.99]
target = solid[0] if solid else max(m_all, key=lambda x: x.get("mastery") or 0)
print("     选中知识点:", target.get("nodeName"), "| 掌握度", target.get("mastery"))
pr = curl("POST", f"/exams/my/practice?nodeId={target['nodeId']}&count=5", ST).get("data", {})
p_exam = pr.get("examId")
check(p_exam is not None, "学生自助发起专项练习（POST /exams/my/practice）", f"examId={p_exam}")
pq = curl("GET", f"/exams/my/{p_exam}", ST).get("data", {}).get("questions", [])
check(len(pq) > 0, "专项练习含题目", f"{len(pq)} 题")
check(all(q.get("nodeId") == target["nodeId"] for q in pq), "卷内题目都归属该知识点")

m_before = {x["nodeId"]: x["mastery"] for x in m_all}
items = [{"questionId": q["questionId"], "answer": "__WRONG__"} for q in pq]
r = curl("POST", f"/exams/my/{p_exam}/submit", ST, {"items": items}).get("data", {})
check(r.get("diagnosis") is False, "普通练习不走覆盖式初始化")
print("     得分:", r.get("totalScore"), "/", r.get("fullScore"))
m_after = {x["nodeId"]: x["mastery"] for x in
           curl("GET", "/exams/mastery?studentId=1", ST).get("data", {}).get("nodes", [])}
changed = [n for n in m_before if m_before.get(n) != m_after.get(n)]
check(len(changed) > 0, "交卷后掌握度发生变化（EMA 增量）",
      f"{[(n, m_before.get(n), m_after.get(n)) for n in changed[:3]]}")

print("\n########## 8b. 从「练」任务进入 → 交卷 → 任务自动收口 ##########")
task_prac = sorted(prac_tasks, key=lambda t: t.get("priority") or 0)[0]
r = curl("POST", f"/tasks/my/{task_prac['id']}/start", ST).get("data", {})
t_exam = r.get("examId")
check(t_exam is not None, "「练」任务现出专项练习", f"examId={t_exam}")
tq = curl("GET", f"/exams/my/{t_exam}", ST).get("data", {}).get("questions", [])
curl("POST", f"/exams/my/{t_exam}/submit", ST,
     {"items": [{"questionId": q["questionId"], "answer": "0"} for q in tq]})
d = curl("GET", "/tasks/student/1", TT).get("data", {})
trow = next((t for t in d.get("tasks", []) if t.get("id") == task_prac["id"]), {})
check(trow.get("status") == "DONE", "练完的「练」任务已自动收口")
check(trow.get("examId") == t_exam, "任务回填了练习卷 id", f"examId={trow.get('examId')}")

print("\n########## 9. 学习任务收口汇总 ##########")
d = curl("GET", "/tasks/my/today", ST).get("data", {})
done_now = [t for t in d.get("tasks", []) if t.get("status") == "DONE"]
check(len(done_now) >= 2, "诊断任务 + 练习任务均已收口",
      str([(t.get("type"), t.get("status")) for t in done_now]))
print("     doneToday =", d.get("doneTodayCount"), "| pending =", d.get("pendingCount"))

print("\n########## 10. 学生学习进度总览 ##########")
p = d.get("progress", {})
print("     知识点", p.get("total"), "| 已掌握", p.get("mastered"), "| 待补强", p.get("weak"),
      "| 平均掌握度", p.get("avgMastery"))
check((p.get("mastered") or 0) + (p.get("weak") or 0) > 0, "掌握度统计有效")

print("\n########## 11. 教师视角：学生清单 / 诊断报告 / 布置任务 ##########")
d = curl("GET", "/tasks/student/1", TT).get("data", {})
check(d.get("studentName") == "小明", "教师可查看学生清单", d.get("studentName"))
r = curl("GET", "/diagnosis/1/report", TT).get("data", {})
check(r.get("completed") is True, "教师可查看学生诊断报告", f"avgRate={r.get('avgRate')}")
r = curl("POST", "/tasks/teacher", TT,
         {"studentId": 1, "nodeId": node_id, "type": "REVIEW",
          "title": "今晚复习一遍这个知识点", "dueAt": "2026-10-25T18:00"}).get("data", {})
check(r.get("source") == "TEACHER", "教师布置任务成功", str(r.get("dueAt")))
d2 = curl("GET", "/tasks/my/today", ST).get("data", {})
check(any(t.get("source") == "TEACHER" for t in d2.get("tasks", [])), "学生清单出现教师布置的任务")
r = curl("POST", "/tasks/student/1/regenerate", TT).get("data", {})
check(r.get("created") is not None, "教师可重排学生任务")
d4 = curl("GET", "/tasks/student/1", TT).get("data", {})
check(any(t.get("source") == "TEACHER" for t in d4.get("tasks", [])),
      "重排后教师布置的任务不被清除")

print("\n########## 12. 推荐规则：默认规则 / 新建 / 生效 / 删除 ##########")
rules = curl("GET", "/recommend/rules", TT).get("data", [])
check(any(r.get("isDefault") == 1 for r in rules), "存在默认推荐规则",
      json.dumps([r.get("name") for r in rules], ensure_ascii=False))
nr = curl("POST", "/recommend/rules", AT, {
    "name": "验证用-强化补弱", "enabled": True, "isDefault": False,
    "config": {"masteryWeight": 0.85, "importanceWeight": 0.15, "weakThreshold": 0.5,
               "solidThreshold": 0.8, "prereqBoost": 0.2, "consolidateRatio": 0.1,
               "dailyTaskLimit": 3}}).get("data", {})
check(nr.get("id") is not None, "新建规则成功")
prev = curl("GET", "/recommend/preview?studentId=1&limit=5", TT).get("data", [])
check(len(prev) > 0, "推荐预览可用", f"{[(x.get('nodeName'), x.get('tier')) for x in prev[:3]]}")
check(all(x.get("reasons") for x in prev), "推荐结果带可解释理由")
curl("DELETE", f"/recommend/rules/{nr['id']}", AT)
check(http_code("POST", "/recommend/rules", TT, {}) == "403", "教师无权维护推荐规则（403）")

print("\n########## 13. 题目识别与批量映射 ##########")
def imp_item(stem, qtype, options, answer, score, difficulty):
    return {"subject": "数学", "textbook": "人教版", "questionType": qtype, "stem": stem,
            "options": options, "answer": answer, "analysis": "自动识别验证用",
            "score": score, "difficulty": difficulty, "nodeIds": [], "nodeNames": []}


imp = curl("POST", "/questions/batch", TT, {
    "graphId": 1,
    "items": [
        imp_item("计算：0.6 × 7 = （ ）", "SINGLE_CHOICE", ["4.2", "42", "0.42", "4.02"], "4.2", 5, 2),
        imp_item("把 3.7 × 1.2 的积保留一位小数是 ____", "FILL_BLANK", None, "4.4", 5, 3),
    ]}).get("data", {})
check(imp.get("created") == 2, "批量导入（不带知识点）成功", json.dumps(imp, ensure_ascii=False)[:160])
check(imp.get("unmappedCount") == 2, "识别出未映射题目数", str(imp.get("unmappedQuestionIds")))

dry = curl("POST", "/questions/mapping/auto", TT,
           {"graphId": 1, "maxQuestions": 10, "dryRun": True}).get("data", {})
print("     预演：扫描", dry.get("scanned"), "题，可映射", dry.get("mapped"),
      "题，跳过已映射", dry.get("skippedMapped"), "题，阈值", dry.get("threshold"))
check(dry.get("mapped", 0) > 0, "预演可自动映射（阈值按模式自动取）")
for it in (dry.get("items") or [])[:2]:
    print("       -", it.get("stem"), "→", [m.get("nodeName") for m in it.get("matches", []) if m.get("nodeId") in it.get("nodeIds", [])])

real = curl("POST", "/questions/mapping/auto", TT,
            {"graphId": 1, "maxQuestions": 10, "dryRun": False}).get("data", {})
check(real.get("mapped", 0) > 0, "实际写入映射", f"mapped={real.get('mapped')}")
lst = curl("GET", "/questions?page=1&size=50", TT).get("data", {}).get("records", [])
mapped_new = [q for q in lst if q.get("id") in (imp.get("unmappedQuestionIds") or []) and q.get("nodeIds")]
check(len(mapped_new) > 0, "新导入题目已带上知识点映射",
      json.dumps([(q.get("stem")[:16], q.get("nodes")) for q in mapped_new], ensure_ascii=False)[:200])
sg = curl("POST", "/questions/mapping/suggest", TT, {"questionId": 1, "graphId": 1, "topK": 5}).get("data", {})
check(len(sg.get("candidates", [])) > 0 and sg.get("mode"), "单题识别返回候选与模式",
      f"mode={sg.get('mode')} 候选 {len(sg.get('candidates', []))} 个")
check(bool(sg.get("notice")), "识别结果带模式说明", (sg.get("notice") or "")[:70])

print("\n########## 14. 权限边界 ##########")
cases = [
    ("GET", "/exams/my?x=1", ST, "200", "学生读自己的作业"),
    ("GET", "/exams/1", ST, "403", "学生读练习详情（含答案）被拦"),
    ("GET", "/exams/1", TT, "200", "教师读练习详情"),
    ("GET", "/tasks/student/1", ST, "403", "学生读他人学习清单"),
    ("GET", "/contents/status", ST, "403", "学生读内容管理接口"),
    ("POST", "/contents/generate", ST, "403", "学生生成教学内容"),
    ("GET", "/diagnosis/999/report", ST, "403", "学生读他人诊断报告"),
    ("GET", "/diagnosis/1/report", ST, "200", "学生读自己的诊断报告"),
    ("POST", "/diagnosis/generate", TT, "400", "教师生成诊断卷（学生不存在 → 业务校验拦住，非 403）"),
    ("POST", "/diagnosis/generate", ST, "403", "学生生成诊断卷被拦"),
    ("GET", "/orgs", TT, "200", "教师读机构列表"),
    ("POST", "/orgs", TT, "403", "教师建机构被拦"),
]
for m, p, tok, expect, label in cases:
    body = {}
    if p == "/diagnosis/generate":
        body = {"studentId": 999}
    got = http_code(m, p, tok, body if m == "POST" else None)
    check(got == expect, f"{label}（期望 {expect}）", f"got {got}")

print("\n########## 汇总 ##########")
if FAIL:
    print(f"  {len(FAIL)} 项未通过：")
    for f in FAIL:
        print("   -", f)
    sys.exit(2)
print("  全部通过 ✅")
