package com.kgquiz.quiz.service;

import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.entity.Student;
import com.kgquiz.quiz.mapper.StudentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudentAccessServiceTest {
    private final StudentMapper mapper = mock(StudentMapper.class);
    private final StudentAccessService service = new StudentAccessService(mapper);

    @AfterEach void clear() { UserContext.clear(); }

    @Test void teacherCannotReadAnotherClass() {
        login(UserRole.TEACHER, 1L, 2L, null, List.of(3L));
        student(1L, 2L, 4L);
        assertThrows(ForbiddenException.class, () -> service.requireStudent(9L));
    }
    @Test void teacherCanReadAssignedClass() {
        login(UserRole.TEACHER, 1L, 2L, null, List.of(3L)); student(1L, 2L, 3L);
        assertEquals(9L, service.requireStudent(9L).getId());
    }
    @Test void organizationAdminCannotReadOtherOrganization() {
        login(UserRole.ORG_ADMIN, 1L, null, null, List.of()); student(2L, 2L, 3L);
        assertThrows(ForbiddenException.class, () -> service.requireStudent(9L));
    }
    @Test void campusAdminCannotReadUnassignedCampus() {
        login(UserRole.CAMPUS_ADMIN, 1L, 2L, null, List.of()); student(1L, null, 3L);
        assertThrows(ForbiddenException.class, () -> service.requireStudent(9L));
    }
    @Test void unboundCampusAdminCannotListAllCampuses() {
        login(UserRole.CAMPUS_ADMIN, 1L, null, null, List.of());
        assertThrows(ForbiddenException.class, UserContext::scopeCampusId);
    }
    @Test void studentCannotReadAnotherStudent() {
        login(UserRole.STUDENT, 1L, 2L, 8L, List.of());
        assertThrows(ForbiddenException.class, () -> service.requireStudent(9L));
        verifyNoInteractions(mapper);
    }
    @Test void platformAdminCanReadAcrossOrganizations() {
        login(UserRole.ADMIN, null, null, null, List.of()); student(2L, 4L, 3L);
        assertEquals(9L, service.requireStudent(9L).getId());
    }
    private void login(UserRole role, Long org, Long campus, Long sid, List<Long> classes) {
        UserContext.set(new CurrentUser(1L, "test", role, "Test", sid, org, campus, classes));
    }
    private void student(Long org, Long campus, Long clazz) {
        Student s = new Student(); s.setId(9L); s.setOrgId(org); s.setCampusId(campus); s.setClazzId(clazz);
        when(mapper.selectById(9L)).thenReturn(s);
    }
}
