# -*- coding: utf-8 -*-
"""
Agent 生效范围回归：平台 / 机构 / 校区 / 班级 四档。

验证的是「配置只能决定在哪启用，决定不了能读什么」这条边界：
每档范围都要能被正确保存、被正确筛选，越界的 id 必须 403 而不是静默放行。

依赖：后端已启动、库里已有验证机构（org_id=2，校区 2/3，班级 2/3/4）。
"""
import json
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8090"
ORG = 2
CAMPUS_A = 2
CAMPUS_B = 3
CLAZZ_1 = 2      # 校区A
CLAZZ_2 = 3      # 校区A
CLAZZ_3 = 4      # 校区B

PASS, FAIL = [], []


def call(method, path, token=None, body=None, expect=200):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    try:
        with urllib.request.urlopen(req, data, timeout=20) as r:
            code, payload = r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        code, payload = e.code, json.loads(e.read().decode("utf-8"))
    except Exception as e:
        code, payload = 0, {"message": str(e)}
    if expect and code != expect:
        raise AssertionError("期望 HTTP %s，实际 %s：%s" % (expect, code, payload.get("message")))
    return code, payload.get("data")


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  PASS  " if cond else "  FAIL  ") + name + ("  " + detail if detail and not cond else ""))


def g(d, key):
    """取字段；全局配置了 non_null，null 字段在 JSON 里根本不存在，等价于 None。"""
    return d.get(key)


def login(u, p):
    _, d = call("POST", "/api/auth/login", body={"username": u, "password": p})
    return d["token"]


def main():
    print("=" * 64)
    print("Agent 生效范围回归")
    print("=" * 64)

    admin = login("admin", "admin123")

    # ---------- 1. 四档范围保存 ----------
    print("\n[1] 四档范围保存与回显")

    # 清理上一轮残留
    _, existing = call("GET", "/api/agents?orgId=%d" % ORG, token=admin)
    for a in existing or []:
        if a.get("code", "").startswith("scope-"):
            call("DELETE", "/api/agents/%d" % a["id"], token=admin)

    _, orgLevel = call("POST", "/api/agents", token=admin, body={
        "code": "scope-org", "orgId": ORG, "name": "机构级验证",
        "targetRole": "STUDENT", "temperature": 0.3, "maxTokens": 1024, "enabled": True})
    check("机构级 → scopeLevel=ORG", orgLevel["scopeLevel"] == "ORG", orgLevel.get("scopeLevel"))
    check("机构级 → campusId 为空", g(orgLevel, "campusId") is None)
    check("机构级 → clazzId 为空", g(orgLevel, "clazzId") is None)
    check("机构级 → 标签文案", "本机构" in orgLevel["scopeLabel"], orgLevel["scopeLabel"])

    _, campusLevel = call("POST", "/api/agents", token=admin, body={
        "code": "scope-campus", "orgId": ORG, "campusId": CAMPUS_A, "name": "校区级验证",
        "targetRole": "STUDENT", "temperature": 0.3, "maxTokens": 1024, "enabled": True})
    check("校区级 → scopeLevel=CAMPUS", campusLevel["scopeLevel"] == "CAMPUS", campusLevel.get("scopeLevel"))
    check("校区级 → 回填校区名", campusLevel["campusName"] == "验证校区A", str(campusLevel.get("campusName")))

    _, clazzLevel = call("POST", "/api/agents", token=admin, body={
        "code": "scope-clazz", "orgId": ORG, "campusId": CAMPUS_A, "clazzId": CLAZZ_1,
        "name": "班级级验证", "targetRole": "STUDENT",
        "temperature": 0.3, "maxTokens": 1024, "enabled": True})
    check("班级级 → scopeLevel=CLAZZ", clazzLevel["scopeLevel"] == "CLAZZ", clazzLevel.get("scopeLevel"))
    check("班级级 → 回填班级名", clazzLevel["clazzName"] == "五年级1班", str(clazzLevel.get("clazzName")))

    # 关键：只给 clazzId 不给 campusId，后端应反查回填而不是报错
    _, backfill = call("POST", "/api/agents", token=admin, body={
        "code": "scope-backfill", "orgId": ORG, "clazzId": CLAZZ_2, "name": "回填验证",
        "targetRole": "STUDENT", "temperature": 0.3, "maxTokens": 1024, "enabled": True})
    check("只给 clazzId → campusId 自动回填", g(backfill, "campusId") == CAMPUS_A, str(backfill.get("campusId")))
    check("回填后 scopeLevel=CLAZZ", backfill["scopeLevel"] == "CLAZZ")

    # 平台级：不传 orgId
    _, plat = call("POST", "/api/agents", token=admin, body={
        "code": "scope-platform", "name": "平台级验证",
        "targetRole": "STUDENT", "temperature": 0.3, "maxTokens": 1024, "enabled": True})
    check("不传 orgId → 平台级", plat["scopeLevel"] == "PLATFORM", plat.get("scopeLevel"))
    check("平台级 → platformLevel=true", plat["platformLevel"] is True)

    # ---------- 2. 越界必须 403 / 400，不能静默放行 ----------
    print("\n[2] 越界与冲突输入")

    code, _ = call("POST", "/api/agents", token=admin, body={
        "code": "scope-bad-campus", "orgId": ORG, "campusId": 999999, "name": "不存在校区",
        "targetRole": "STUDENT", "enabled": True}, expect=0)
    check("校区不存在 → 报错而非静默", code == 400, "HTTP %s" % code)

    # 班级与校区不匹配：班级3属于校区B，却指定校区A
    code, _ = call("POST", "/api/agents", token=admin, body={
        "code": "scope-mismatch", "orgId": ORG, "campusId": CAMPUS_A, "clazzId": CLAZZ_3,
        "name": "错配", "targetRole": "STUDENT", "enabled": True}, expect=0)
    check("班级不属于该校区 → 400", code == 400, "HTTP %s" % code)

    # 不传 orgId 却给了 campusId：后端从校区反推机构，落成「该校区级」而不是平台级。
    # 这不是错误 —— 校区本来就归属于某个机构，「平台级 + 限定校区」本身自相矛盾。
    _, derived = call("POST", "/api/agents", token=admin, body={
        "code": "scope-derived", "campusId": CAMPUS_A, "name": "反推机构",
        "targetRole": "STUDENT", "enabled": True})
    check("只给 campusId → 自动反推 orgId", g(derived, "orgId") == ORG, str(derived.get("orgId")))
    check("只给 campusId → scopeLevel=CAMPUS", derived["scopeLevel"] == "CAMPUS")
    call("DELETE", "/api/agents/%d" % derived["id"], token=admin)

    # ---------- 3. 按范围筛选 ----------
    print("\n[3] 按校区 / 班级下钻")

    # 预期集合由「基线 + 本轮新增」组成，而不是硬编码数字：
    # 库里还有 3 个平台级种子 Agent（student-planner 等），
    # 它们在任何校区都生效，写死数字会在有人加种子时误报。
    _, baseAll = call("GET", "/api/agents", token=admin)
    base = sorted(a["code"] for a in baseAll if not a["code"].startswith("scope-"))
    check("存在平台级基线 Agent", len(base) >= 3, str(base))

    def expect(*extra):
        return sorted(base + list(extra))

    # 本轮建的 5 条：1 机构级 + 1 校区级 + 2 班级级 + 1 平台级
    ORG_LVL = ["scope-org"]
    ALL_LOCAL = ["scope-org", "scope-campus", "scope-clazz", "scope-backfill"]
    PLAT = ["scope-platform"]

    _, allInOrg = call("GET", "/api/agents?orgId=%d" % ORG, token=admin)
    codes_all = sorted(a["code"] for a in allInOrg)
    check("机构视角 = 平台级基线 + 平台级验证 + 本机构 4 条",
          codes_all == expect(*(ORG_LVL + ALL_LOCAL[1:] + PLAT)), str(codes_all))

    _, byCampusA = call("GET", "/api/agents?orgId=%d&campusId=%d" % (ORG, CAMPUS_A), token=admin)
    codes_a = sorted(a["code"] for a in byCampusA)
    check("校区A 生效 = 平台 + 机构 + 校区A + 两个A班",
          codes_a == expect(*(ALL_LOCAL + PLAT)), str(codes_a))

    _, byCampusB = call("GET", "/api/agents?orgId=%d&campusId=%d" % (ORG, CAMPUS_B), token=admin)
    codes_b = sorted(a["code"] for a in byCampusB)
    check("校区B 生效 = 平台 + 机构（校区A 的不波及）",
          codes_b == expect(*(ORG_LVL + PLAT)), str(codes_b))

    _, byClazz1 = call("GET", "/api/agents?orgId=%d&clazzId=%d" % (ORG, CLAZZ_1), token=admin)
    codes_c1 = sorted(a["code"] for a in byClazz1)
    # scope-backfill 挂在 2 班，1 班不该看到它 —— 这正是要验的隔离
    check("1班生效 = 平台 + 机构 + 校区A + 本班（不含2班的班级级）",
          codes_c1 == expect(*(ORG_LVL + ["scope-campus", "scope-clazz"] + PLAT)), str(codes_c1))
    check("1班看不到 2 班的班级级 Agent（scope-backfill）",
          "scope-backfill" not in codes_c1, str(codes_c1))

    _, byClazz3 = call("GET", "/api/agents?orgId=%d&clazzId=%d" % (ORG, CLAZZ_3), token=admin)
    codes_c3 = sorted(a["code"] for a in byClazz3)
    check("B校区1班生效 = 平台 + 机构（校区A 的不波及）",
          codes_c3 == expect(*(ORG_LVL + PLAT)), str(codes_c3))

    # ---------- 4. 范围总览 ----------
    print("\n[4] 校区 / 班级总览")

    _, sc = call("GET", "/api/agents/scopes", token=admin)
    camp = {c["id"]: c for c in sc["campuses"]}
    check("总览含校区A", CAMPUS_A in camp)
    check("校区A 单独配置 = 3（校区级 + 两个A班级）",
          camp.get(CAMPUS_A, {}).get("directCount") == 3,
          str(camp.get(CAMPUS_A, {}).get("directCount")))
    check("校区A 实际生效 = 基线 + 平台级验证 + 4",
          camp.get(CAMPUS_A, {}).get("effectiveCount") == len(base) + 5,
          "%s（基线 %d）" % (camp.get(CAMPUS_A, {}).get("effectiveCount"), len(base)))
    check("校区B 单独配置 = 0", camp.get(CAMPUS_B, {}).get("directCount") == 0)
    check("校区B 实际生效 = 基线 + 平台级验证 + 机构级",
          camp.get(CAMPUS_B, {}).get("effectiveCount") == len(base) + 2,
          str(camp.get(CAMPUS_B, {}).get("effectiveCount")))

    klasses = {k["id"]: k for k in sc["clazzes"]}
    check("1班 单独配置 = 1", klasses.get(CLAZZ_1, {}).get("directCount") == 1)
    check("2班 单独配置 = 1（回填那条）", klasses.get(CLAZZ_2, {}).get("directCount") == 1)
    check("B班 单独配置 = 0", klasses.get(CLAZZ_3, {}).get("directCount") == 0)
    check("B班 实际生效 = 基线 + 平台级验证 + 机构级",
          klasses.get(CLAZZ_3, {}).get("effectiveCount") == len(base) + 2,
          str(klasses.get(CLAZZ_3, {}).get("effectiveCount")))
    check("班级总览带 grade/subject/campusId（前端展示用）",
          all(k.get("campusId") is not None for k in sc["clazzes"]), str(sc["clazzes"]))

    # ---------- 5. 角色可见性：学生看不到别班的班级级 Agent ----------
    print("\n[5] 学生侧可见性")

    _, students = call("GET", "/api/students", token=admin)
    if len(students) < 2:
        print("  SKIP  学生不足 2 名，跳过学生侧可见性验证")
    else:
        s1, s2 = students[0], students[1]
        # 特意把两个学生分到不同校区：s1 在 1 班（校区A），s2 在 B班（校区B）
        # 必须在改动前把原归属拍下来：改动后再读就只剩新值了，还原会失败
        orig1 = (g(s1, "clazzId"), g(s1, "campusId"), g(s1, "orgId"))
        orig2 = (g(s2, "clazzId"), g(s2, "campusId"), g(s2, "orgId"))
        _, m1 = call("PUT", "/api/orgs/students/%d/clazz" % s1["id"], token=admin,
                     body={"clazzId": CLAZZ_1})
        _, m2 = call("PUT", "/api/orgs/students/%d/clazz" % s2["id"], token=admin,
                     body={"clazzId": CLAZZ_3})
        check("学生1 转入 1 班", g(m1, "clazzId") == CLAZZ_1, str(m1))
        check("学生2 转入 B班", g(m2, "clazzId") == CLAZZ_3, str(m2))

        def mine(token):
            _, d = call("GET", "/api/agents/mine", token=token)
            return sorted(a["code"] for a in d)

        # 学生登录名不总是 stu{id}（手工建号可能起别的名），按接口返回的实际 username 登录
        def token_of(student):
            u = student.get("username")
            if not u:
                return None
            try:
                return login(u, "123456")
            except AssertionError:
                return None

        t1, t2 = token_of(s1), token_of(s2)
        if not t1 or not t2:
            print("  SKIP  学生账号密码非默认（%s / %s），跳过可见性断言"
                  % (s1.get("username"), s2.get("username")))
        else:
            mine1 = mine(t1)
            mine2 = mine(t2)

            check("学生1 看到本班的班级级 Agent（scope-clazz）", "scope-clazz" in mine1, str(mine1))
            check("学生1 看不到别班的班级级 Agent（scope-backfill）",
                  "scope-backfill" not in mine1, str(mine1))
            check("学生1 能看到平台级 + 机构级 + 本校区级",
                  {"scope-org", "scope-campus", "scope-platform"}.issubset(set(mine1)), str(mine1))
            check("学生2 看不到校区A 的班级级 Agent（跨校区隔离）",
                  "scope-clazz" not in mine2 and "scope-campus" not in mine2, str(mine2))
            check("学生2 能看到平台级 + 机构级",
                  {"scope-org", "scope-platform"}.issubset(set(mine2)), str(mine2))

            # 预演也要按生效范围卡：不能用班级级 Agent 预演别班学生
            code, _ = call("POST", "/api/agents/scope-clazz/dry-run?studentId=%d" % s2["id"],
                           token=admin, expect=0)
            check("预演：不覆盖该学生的 Agent → 403", code == 403, "HTTP %s" % code)

        # 还原学生班级（用改动前拍下的原值，不是改动后读到的）
        for stu, orig in ((s1, orig1), (s2, orig2)):
            if orig[0]:
                call("PUT", "/api/orgs/students/%d/clazz" % stu["id"], token=admin,
                     body={"clazzId": orig[0]})
        _, after = call("GET", "/api/students", token=admin)
        restored = {s["id"]: g(s, "clazzId") for s in after if s["id"] in (s1["id"], s2["id"])}
        check("学生归属已还原",
              restored.get(s1["id"]) == orig1[0] and restored.get(s2["id"]) == orig2[0],
              "%s vs 期望 %s / %s" % (restored, orig1[0], orig2[0]))

    # ---------- 6. 权限：教师不能改 Agent 编排 ----------
    print("\n[6] 角色边界")

    code, _ = call("POST", "/api/agents", token=login("teacher", "teacher123"), body={
        "code": "scope-teacher-try", "name": "教师越权", "targetRole": "TEACHER",
        "enabled": True}, expect=0)
    check("教师建 Agent → 403", code == 403, "HTTP %s" % code)

    code, _ = call("GET", "/api/agents/scopes", token=login("teacher", "teacher123"), expect=0)
    check("教师看范围总览 → 403", code == 403, "HTTP %s" % code)

    code, _ = call("GET", "/api/agents", token=None, expect=0)
    check("未登录看 Agent 列表 → 401", code == 401, "HTTP %s" % code)

    # ---------- 清理 ----------
    print("\n[5] 清理验证数据")
    _, lst = call("GET", "/api/agents?orgId=%d" % ORG, token=admin)
    n = 0
    for a in lst or []:
        if a["code"].startswith("scope-"):
            call("DELETE", "/api/agents/%d" % a["id"], token=admin)
            n += 1
    check("已清理 %d 条验证 Agent" % n, n == 5, str(n))

    print("\n" + "=" * 64)
    print("PASS %d / FAIL %d" % (len(PASS), len(FAIL)))
    if FAIL:
        for f in FAIL:
            print("  ✗ " + f)
    print("=" * 64)
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
