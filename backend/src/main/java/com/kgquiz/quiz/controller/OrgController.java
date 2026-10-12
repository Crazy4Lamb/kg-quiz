package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.dto.CampusReq;
import com.kgquiz.quiz.dto.ClazzReq;
import com.kgquiz.quiz.dto.OrgReq;
import com.kgquiz.quiz.dto.TeacherReq;
import com.kgquiz.quiz.service.OrgService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 机构管理平台：机构 → 校区 → 班级 → 教师 / 学生。
 *
 * <p>权限：机构本身（含停用、编码）只有平台管理员能改；校区 / 班级 / 教师由平台管理员
 * 与机构管理员共同维护，机构边界由服务层强制校验，越界一律 403。</p>
 */
@RestController
@RequestMapping("/api/orgs")
@RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN, UserRole.TEACHER})
@RequiredArgsConstructor
public class OrgController {

    private final OrgService orgService;

    // ---------------- 机构 ----------------

    @GetMapping
    public R<List<Map<String, Object>>> listOrgs(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String status) {
        return R.ok(orgService.listOrgs(keyword, status));
    }

    /** 机构总览：校区 → 班级 → 人数 的层级视图 */
    @GetMapping("/{id}/overview")
    public R<Map<String, Object>> overview(@PathVariable Long id) {
        return R.ok(orgService.overview(id));
    }

    @PostMapping
    @RequireRole(UserRole.ADMIN)
    public R<Map<String, Object>> createOrg(@Valid @RequestBody OrgReq req) {
        return R.ok(orgService.createOrg(req));
    }

    @PutMapping("/{id}")
    @RequireRole(UserRole.ADMIN)
    public R<Map<String, Object>> updateOrg(@PathVariable Long id, @Valid @RequestBody OrgReq req) {
        return R.ok(orgService.updateOrg(id, req));
    }

    @PutMapping("/{id}/status")
    @RequireRole(UserRole.ADMIN)
    public R<Map<String, Object>> setOrgStatus(@PathVariable Long id,
                                               @RequestBody Map<String, String> body) {
        return R.ok(orgService.setOrgStatus(id, body.get("status")));
    }

    // ---------------- 校区 ----------------

    @GetMapping("/{orgId}/campuses")
    public R<List<Map<String, Object>>> listCampuses(@PathVariable Long orgId) {
        return R.ok(orgService.listCampuses(orgId));
    }

    @PostMapping("/campuses")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Map<String, Object>> createCampus(@Valid @RequestBody CampusReq req) {
        return R.ok(orgService.createCampus(req));
    }

    @PutMapping("/campuses/{id}")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Map<String, Object>> updateCampus(@PathVariable Long id, @Valid @RequestBody CampusReq req) {
        return R.ok(orgService.updateCampus(id, req));
    }

    @DeleteMapping("/campuses/{id}")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Void> deleteCampus(@PathVariable Long id) {
        orgService.deleteCampus(id);
        return R.ok();
    }

    // ---------------- 班级 ----------------

    @GetMapping("/clazzes")
    public R<List<Map<String, Object>>> listClazzes(@RequestParam(required = false) Long orgId,
                                                    @RequestParam(required = false) Long campusId) {
        return R.ok(orgService.listClazzes(orgId, campusId));
    }

    @PostMapping("/clazzes")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Map<String, Object>> createClazz(@Valid @RequestBody ClazzReq req) {
        return R.ok(orgService.createClazz(req));
    }

    @PutMapping("/clazzes/{id}")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Map<String, Object>> updateClazz(@PathVariable Long id, @Valid @RequestBody ClazzReq req) {
        return R.ok(orgService.updateClazz(id, req));
    }

    @DeleteMapping("/clazzes/{id}")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Void> deleteClazz(@PathVariable Long id) {
        orgService.deleteClazz(id);
        return R.ok();
    }

    // ---------------- 教师 ----------------

    @GetMapping("/teachers")
    public R<List<Map<String, Object>>> listTeachers(@RequestParam(required = false) Long orgId,
                                                     @RequestParam(required = false) Long campusId,
                                                     @RequestParam(required = false) String keyword) {
        return R.ok(orgService.listTeachers(orgId, campusId, keyword));
    }

    @PostMapping("/teachers")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Map<String, Object>> createTeacher(@Valid @RequestBody TeacherReq req) {
        return R.ok(orgService.createTeacher(req));
    }

    @PutMapping("/teachers/{userId}")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Map<String, Object>> updateTeacher(@PathVariable Long userId,
                                                @Valid @RequestBody TeacherReq req) {
        return R.ok(orgService.updateTeacher(userId, req));
    }

    @PutMapping("/teachers/{userId}/enabled")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Map<String, Object>> setTeacherEnabled(@PathVariable Long userId,
                                                    @RequestBody Map<String, Object> body) {
        Object raw = body.get("enabled");
        boolean enabled = raw == null || Boolean.parseBoolean(String.valueOf(raw));
        return R.ok(orgService.setTeacherEnabled(userId, enabled));
    }

    @PutMapping("/teachers/{userId}/password")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Map<String, Object>> resetTeacherPassword(@PathVariable Long userId,
                                                       @RequestBody Map<String, String> body) {
        return R.ok(orgService.resetTeacherPassword(userId, body.get("password")));
    }

    @DeleteMapping("/teachers/{userId}")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Void> deleteTeacher(@PathVariable Long userId) {
        orgService.deleteTeacher(userId);
        return R.ok();
    }

    // ---------------- 学生（组织视角） ----------------

    @GetMapping("/{orgId}/students")
    public R<List<Map<String, Object>>> listStudents(@PathVariable Long orgId,
                                                     @RequestParam(required = false) Long campusId,
                                                     @RequestParam(required = false) Long clazzId,
                                                     @RequestParam(required = false) String keyword) {
        return R.ok(orgService.listStudents(orgId, campusId, clazzId, keyword));
    }

    /** 学生转班（校区随之更新，账号归属同步） */
    @PutMapping("/students/{studentId}/clazz")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN, UserRole.TEACHER})
    public R<Map<String, Object>> moveStudent(@PathVariable Long studentId,
                                              @RequestBody Map<String, Long> body) {
        return R.ok(orgService.moveStudent(studentId, body.get("clazzId")));
    }
}
