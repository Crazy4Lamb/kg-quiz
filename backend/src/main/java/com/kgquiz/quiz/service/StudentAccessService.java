package com.kgquiz.quiz.service;

import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.entity.Student;
import com.kgquiz.quiz.mapper.StudentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 学生数据入口统一校验身份、机构、校区与教师带班范围。 */
@Service
@RequiredArgsConstructor
public class StudentAccessService {
    private final StudentMapper studentMapper;

    public Student requireStudent(Long studentId) {
        UserContext.checkStudentScope(studentId);
        if (studentId == null) {
            throw new BizException("请指定学生");
        }
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException("学生不存在: " + studentId);
        }
        UserContext.checkOrgScope(student.getOrgId());
        var user = UserContext.require();
        if (user.isCampusAdmin() || (user.role() == UserRole.TEACHER && user.campusId() != null)) {
            UserContext.checkCampusScope(student.getCampusId());
        }
        if (user.role() == UserRole.TEACHER) {
            UserContext.checkClazzScope(student.getClazzId());
        }
        return student;
    }
}
