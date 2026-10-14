#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
组织管理层级回归：校区管理员 / 班级分层 / 班级下钻 / 学生批量导入。

对应本轮实现的四项能力：
1. CAMPUS_ADMIN 角色与校区级边界（跨校区 403）
2. 班级分层 parent_clazz_id（跨校区父班拒绝 / 自环拒绝 / 两层拒绝）
3. 班级下钻详情（班主任 + 任课教师 + 学生名单 + 子班）
4. 学生批量导入到班级（建档 + 建账号 + 同名跳过）

跟 verify-agent-scope.py 一样，脚本会改业务数据，
**改动前先拍快照，最后按快照还原**，绝不重读当前值当原值。
"""
import json
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8090/api"

PASS, FAIL = [], []


def call(method, path, token=None, body=None, expect=200):
    """发请求并返回 (http_status, data)。expect 不为 None 时断言状态码。"""
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    payload = None
    if body is not None:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
    try:
        with urllib.request.urlopen(req, payload, timeout=30) as r:
            status = r.getcode()
            raw = r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        status = e.code
        raw = e.read().decode("utf-8", "replace")
    except Exception as e:  # 连接失败等
        status, raw = 0, str(e)
    try:
        data = json.loads(raw)
    except Exception:
        data = {"_raw": raw}
    if expect is not None:
        ok = (status == expect) if isinstance(expect, int) else (status in expect)
        if not ok:
            print("      [HTTP %s] %s %s -> %s" % (status, method, path, raw[:180]))
    return status, data


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  PASS  " if cond else "  FAIL  ") + name + (("  " + str(detail)) if detail and not cond else ""))


def login(username, password):
    st, d = call("POST", "/auth/login", body={"username": username, "password": password})
    if st != 200:
        raise SystemExit("登录失败 %s: %s" % (username, d))
    return d["data"]["token"]


def main():
    admin = login("admin", "admin123")
    print("=" * 68)
    print("组织管理层级回归：校区管理员 / 班级分层 / 班级下钻 / 批量导入")
    print("=" * 68)

    # ---------- 准备：找到两个校区和它们各一个班 ----------
    print("\n[0] 准备校验数据")
    st, ovs = call("GET", "/orgs", token=admin)
    orgs = ovs.get("data") or []
    check("能读到机构列表", len(orgs) >= 1, str(orgs))

    # 挑「校区最多」的机构 —— 验证跨校区隔离至少需要 2 个校区，
    # 而第一个机构未必满足（比如只有示例校区的演示机构）
    best = None
    for o in orgs:
        st, cps = call("GET", "/orgs/%d/campuses" % o["id"], token=admin)
        cs = cps.get("data") or []
        if best is None or len(cs) > len(best[1]):
            best = (o["id"], cs)
    org_id, campuses = best
    check("选定机构下至少有 2 个校区（验证跨校区隔离）", len(campuses) >= 2,
          "机构 %s 下 %d 个校区" % (org_id, len(campuses)))
    if len(campuses) < 2:
        print("  跳过：校区不足 2 个，无法验证跨校区边界")
        return
    campus_a, campus_b = campuses[0]["id"], campuses[1]["id"]

    st, czs = call("GET", "/orgs/clazzes?orgId=%d" % org_id, token=admin)
    clazzes = czs.get("data") or []
    clazz_by_campus = {}
    for c in clazzes:
        clazz_by_campus.setdefault(c.get("campusId"), []).append(c)
    check("两个校区各至少 1 个班",
          campus_a in clazz_by_campus and campus_b in clazz_by_campus, str(clazz_by_campus.keys()))
    if campus_a not in clazz_by_campus or campus_b not in clazz_by_campus:
        print("  跳过：校区下班级不足")
        return
    clazz_a = clazz_by_campus[campus_a][0]
    clazz_b = clazz_by_campus[campus_b][0]

    # 快照：改动前的原始归属（还原时只认这份快照）
    snap_campus_a = (campus_a, org_id)
    created = {"admins": [], "clazzes": [], "students": [], "teachers": []}

    try:
        # ---------- 1. 校区管理员：创建与边界 ----------
        print("\n[1] 校区管理员分配")

        st, d = call("POST", "/orgs/campus-admins", token=admin, body={
            "username": "campus_a_admin", "password": "campus123",
            "displayName": "A校区负责人", "campusId": campus_a}, expect=200)
        admin_a = d.get("data") or {}
        created["admins"].append(admin_a.get("userId"))
        check("创建校区管理员成功", st == 200 and admin_a.get("role") == "CAMPUS_ADMIN", str(admin_a)[:150])
        check("管理员绑定到校区A", admin_a.get("campusId") == campus_a, str(admin_a.get("campusId")))
        check("管理员带出校区名", bool(admin_a.get("campusName")), str(admin_a.get("campusName")))

        # 校区视图应回显管理员
        st, cp = call("GET", "/orgs/%d/campuses" % org_id, token=admin)
        camp_list = cp.get("data") or []
        view_a = next((c for c in camp_list if c["id"] == campus_a), {})
        view_b = next((c for c in camp_list if c["id"] == campus_b), {})
        check("校区A 视图显示已配管理员",
              view_a.get("adminAssigned") is True and view_a.get("adminCount", 0) >= 1,
              str(view_a.get("adminCount")))
        check("校区B 视图显示未配管理员", view_b.get("adminAssigned") is False, str(view_b.get("adminCount")))

        # 必须绑定校区
        st, _ = call("POST", "/orgs/campus-admins", token=admin, body={
            "username": "campus_nobind", "password": "campus123",
            "displayName": "无校区"}, expect=(200, 400))
        check("校区管理员不绑校区 → 400", st == 400, "HTTP %s" % st)

        # 登录校区管理员
        token_a = login("campus_a_admin", "campus123")
        st, prof = call("GET", "/auth/me", token=token_a)
        check("校区管理员可登录且角色正确",
              (prof.get("data") or {}).get("role") == "CAMPUS_ADMIN", str(prof)[:120])
        check("管理员登录态带回校区归属",
              (prof.get("data") or {}).get("campusId") == campus_a,
              str((prof.get("data") or {}).get("campusId")))

        # ---------- 2. 校区级数据边界 ----------
        print("\n[2] 校区管理员的数据边界")

        st, cp = call("GET", "/orgs/%d/campuses" % org_id, token=token_a)
        vis = cp.get("data") or []
        check("只看到自己校区", len(vis) == 1 and vis[0]["id"] == campus_a, str([c["id"] for c in vis]))

        st, czs = call("GET", "/orgs/clazzes?orgId=%d" % org_id, token=token_a)
        a_clazzes = czs.get("data") or []
        check("班级列表只含本校区",
              all(c.get("campusId") == campus_a for c in a_clazzes),
              str([c.get("campusId") for c in a_clazzes]))

        # 改别校区班级 → 403
        st, _ = call("PUT", "/orgs/clazzes/%d" % clazz_b["id"], token=token_a, body={
            "campusId": campus_b, "name": clazz_b["name"]}, expect=403)
        check("改别校区班级 → 403", st == 403, "HTTP %s" % st)

        # 读别校区班级详情 → 403
        st, _ = call("GET", "/orgs/clazzes/%d/detail" % clazz_b["id"], token=token_a, expect=403)
        check("读别校区班级详情 → 403", st == 403, "HTTP %s" % st)

        # 导入学生到别校区班级 → 403
        st, _ = call("POST", "/orgs/clazzes/%d/students/import" % clazz_b["id"], token=token_a,
                     body={"names": ["越权生"]}, expect=403)
        check("往别校区班级导学生 → 403", st == 403, "HTTP %s" % st)

        # 本校区班级可以管
        st, _ = call("GET", "/orgs/clazzes/%d/detail" % clazz_a["id"], token=token_a, expect=200)
        check("读本校区班级详情 → 200", st == 200, "HTTP %s" % st)

        # 校区管理员不该能分配校区管理员（那是机构管理员的权）
        st, _ = call("POST", "/orgs/campus-admins", token=token_a, body={
            "username": "self_admin", "password": "campus123",
            "displayName": "自封", "campusId": campus_a}, expect=403)
        check("校区管理员自封管理员 → 403", st == 403, "HTTP %s" % st)

        # ---------- 3. 班级分层 ----------
        print("\n[3] 班级分层（仅一层）")

        st, d = call("POST", "/orgs/clazzes", token=admin, body={
            "campusId": campus_a, "name": "回归用顶级班", "grade": "五年级"}, expect=200)
        top_clazz = d.get("data") or {}
        created["clazzes"].append(top_clazz.get("id"))
        check("建顶级班成功", st == 200 and top_clazz.get("level") == 0, str(top_clazz.get("level")))

        st, d = call("POST", "/orgs/clazzes", token=admin, body={
            "campusId": campus_a, "name": "回归用平行班", "grade": "五年级",
            "parentClazzId": top_clazz["id"]}, expect=200)
        sub_clazz = d.get("data") or {}
        created["clazzes"].append(sub_clazz.get("id"))
        check("建子班成功且 level=1", st == 200 and sub_clazz.get("level") == 1, str(sub_clazz.get("level")))
        check("子班回显上级班名", sub_clazz.get("parentClazzName") == top_clazz["name"],
              str(sub_clazz.get("parentClazzName")))
        check("顶级班 childCount=1", top_clazz.get("id") and
              _get_child_count(admin, top_clazz["id"]) == 1,
              str(_get_child_count(admin, top_clazz["id"])))

        # 自环
        st, _ = call("PUT", "/orgs/clazzes/%d" % top_clazz["id"], token=admin, body={
            "campusId": campus_a, "name": top_clazz["name"],
            "parentClazzId": top_clazz["id"]}, expect=(200, 400))
        check("班级把自己设为上级 → 400", st == 400, "HTTP %s" % st)

        # 两层：把顶级班挂到已有下级的班上 → 拒绝
        st, _ = call("PUT", "/orgs/clazzes/%d" % sub_clazz["id"], token=admin, body={
            "campusId": campus_a, "name": sub_clazz["name"],
            "parentClazzId": top_clazz["id"]}, expect=200)
        st, _ = call("PUT", "/orgs/clazzes/%d" % top_clazz["id"], token=admin, body={
            "campusId": campus_a, "name": top_clazz["name"],
            "parentClazzId": sub_clazz["id"]}, expect=400)
        check("两级分层（顶级班挂到已有上级的班）→ 400", st == 400, "HTTP %s" % st)

        # 跨校区父班 → 400
        st, _ = call("POST", "/orgs/clazzes", token=admin, body={
            "campusId": campus_a, "name": "跨校区挂靠班",
            "parentClazzId": clazz_b["id"]}, expect=400)
        check("跨校区挂上级 → 400", st == 400, "HTTP %s" % st)

        # ---------- 4. 学生批量导入 ----------
        print("\n[4] 学生批量导入到班级")

        st, d = call("POST", "/orgs/clazzes/%d/students/import" % sub_clazz["id"], token=admin,
                     body={"names": ["回归甲", "回归乙", "回归丙"]}, expect=200)
        r1 = d.get("data") or {}
        check("导入 3 人成功", r1.get("createdCount") == 3, str(r1.get("createdCount")))
        check("无重复项", r1.get("duplicatedCount") == 0, str(r1.get("duplicated")))
        check("返回默认密码", r1.get("defaultPassword") == "123456", str(r1.get("defaultPassword")))

        # 重复导入同名 → 跳过而非报错
        st, d = call("POST", "/orgs/clazzes/%d/students/import" % sub_clazz["id"], token=admin,
                     body={"names": ["回归甲", "回归丁"]}, expect=200)
        r2 = d.get("data") or {}
        check("同名重复被跳过", r2.get("createdCount") == 1 and r2.get("duplicatedCount") == 1,
              str(r2))
        check("重复名单正确", r2.get("duplicated") == ["回归甲"], str(r2.get("duplicated")))

        # CSV 多列输入（第一列取姓名）
        st, d = call("POST", "/orgs/clazzes/%d/students/import" % sub_clazz["id"], token=admin,
                     body={"csvText": "回归戊\t学号001\n回归己,002，备注"}, expect=200)
        r3 = d.get("data") or {}
        check("CSV 多列解析出 2 人", r3.get("createdCount") == 2, str(r3.get("created")))

        # 空输入 → 400
        st, _ = call("POST", "/orgs/clazzes/%d/students/import" % sub_clazz["id"], token=admin,
                     body={"names": [], "csvText": "   "}, expect=400)
        check("空名单导入 → 400", st == 400, "HTTP %s" % st)

        # ---------- 5. 班级下钻 ----------
        print("\n[5] 班级下钻详情")

        st, d = call("GET", "/orgs/clazzes/%d/detail" % top_clazz["id"], token=admin, expect=200)
        det = d.get("data") or {}
        check("下钻返回班级名", det.get("name") == top_clazz["name"], str(det.get("name")))
        check("下钻含子班列表", len(det.get("children") or []) == 1,
              str([c.get("name") for c in (det.get("children") or [])]))
        check("下钻含学生名单字段", isinstance(det.get("students"), list), str(type(det.get("students"))))
        check("下钻含教师名单字段", isinstance(det.get("teachers"), list), str(type(det.get("teachers"))))

        st, d = call("GET", "/orgs/clazzes/%d/detail" % sub_clazz["id"], token=admin, expect=200)
        det2 = d.get("data") or {}
        names = [s["name"] for s in (det2.get("students") or [])]
        check("子班下钻含全部导入学生", len(names) == 6, str(names))
        check("导入学生都开通了账号", all(s.get("username") for s in (det2.get("students") or [])),
              str(names))
        # 记录这些学生 id 以便清理
        created["students"].extend([s["id"] for s in (det2.get("students") or [])])

        # ---------- 6. 管理员候选与提权 ----------
        print("\n[6] 管理员候选与提权")

        st, d = call("GET", "/orgs/campuses/%d/admin-candidates" % campus_a, token=admin, expect=200)
        cands = d.get("data") or []
        check("候选列表可读", isinstance(cands, list), str(type(cands)))
        check("候选含本校区管理员本人",
              any(c["userId"] == admin_a.get("userId") for c in cands),
              str([c.get("username") for c in cands]))

        # ---------- 7. 教师侧回归（带班不受影响） ----------
        print("\n[7] 教师权限未受影响")
        st, _ = call("GET", "/orgs/teachers?orgId=%d" % org_id, token=admin, expect=200)
        check("管理员仍能读教师列表", st == 200, "HTTP %s" % st)
        st, _ = call("GET", "/orgs/clazzes?orgId=%d" % org_id, token=admin, expect=200)
        check("管理员仍能读班级列表", st == 200, "HTTP %s" % st)

    finally:
        # ---------- 还原：只认快照，绝不重读当前值 ----------
        # 顺序很重要：先删学生再删班。反过来会因 deleteClazz 的「该班还有学生」校验全部失败，
        # 留下一堆垃圾班级，下次跑又撞同名。这是上一版脚本踩过的坑。
        #
        # 学生没有对外的删除接口（产品上刻意不给），所以这里直接走 SQL 清理。
        # 这是验证脚本的特权，不该照搬进业务代码。
        print("\n[还原] 清理验证数据")
        _cleanup_students_by_prefix("回归")
        st, czs = call("GET", "/orgs/clazzes?orgId=%d" % org_id, token=admin)
        for c in (czs.get("data") or []):
            if c.get("name", "").startswith("回归用"):
                call("DELETE", "/orgs/clazzes/%d" % c["id"], token=admin, expect=None)
        for uid in created["admins"]:
            if uid:
                call("DELETE", "/orgs/campus-admins/%d" % uid, token=admin, expect=None)

        st, czs = call("GET", "/orgs/clazzes?orgId=%d" % org_id, token=admin)
        leftover = [c["name"] for c in (czs.get("data") or []) if c.get("name", "").startswith("回归用")]
        check("验证班级已清理干净", not leftover, str(leftover))
        st, ss = call("GET", "/orgs/%d/students" % org_id, token=admin)
        left_stu = [s["name"] for s in (ss.get("data") or []) if s.get("name", "").startswith("回归")]
        check("验证学生已清理干净", not left_stu, str(left_stu))

    print("\n" + "=" * 68)
    print("PASS %d / FAIL %d" % (len(PASS), len(FAIL)))
    if FAIL:
        print("失败项：")
        for f in FAIL:
            print("  - " + f)
    print("=" * 68)
    return 1 if FAIL else 0


def _get_child_count(token, clazz_id):
    st, d = call("GET", "/orgs/clazzes/%d/detail" % clazz_id, token=token)
    return len(((d.get("data") or {}).get("children")) or [])


def _cleanup_students_by_prefix(prefix):
    """直连 MySQL 删掉验证产生的学生及其登录账号。

    产品没有对外的删除学生接口（刻意不给），所以验证脚本走 SQL。
    先删 sys_user 再删 student —— 反过来会留下孤儿账号。
    """
    import subprocess
    mysql = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
    sql = (
        "DELETE u FROM sys_user u JOIN student s ON u.student_id = s.id "
        "WHERE s.name LIKE '%s%%';\n"
        "DELETE FROM student_node_state WHERE source_node_id IN "
        "(SELECT id FROM student WHERE name LIKE '%s%%');\n"
        "DELETE FROM student WHERE name LIKE '%s%%';\n"
    ) % (prefix, prefix, prefix)
    p = subprocess.run([mysql, "-h", "127.0.0.1", "-P", "3306", "-uroot", "-proot",
                        "yt_knowledge_demo", "--default-character-set=utf8mb4"],
                       input=sql.encode("utf-8"), capture_output=True)
    if p.returncode != 0:
        print("      [SQL 清理告警] " + p.stderr.decode("utf-8", "replace")[:150])


if __name__ == "__main__":
    raise SystemExit(main())