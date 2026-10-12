package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.dto.StudentCreateReq;
import com.kgquiz.quiz.entity.Clazz;
import com.kgquiz.quiz.entity.Graph;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.NodeRelation;
import com.kgquiz.quiz.entity.Student;
import com.kgquiz.quiz.entity.StudentGraph;
import com.kgquiz.quiz.entity.StudentNodeState;
import com.kgquiz.quiz.entity.SysUser;
import com.kgquiz.quiz.mapper.ClazzMapper;
import com.kgquiz.quiz.mapper.StudentGraphMapper;
import com.kgquiz.quiz.mapper.StudentMapper;
import com.kgquiz.quiz.mapper.StudentNodeStateMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StudentService {

    private final StudentMapper studentMapper;
    private final StudentGraphMapper studentGraphMapper;
    private final StudentNodeStateMapper studentNodeStateMapper;
    private final ClazzMapper clazzMapper;
    private final GraphStore graphStore;
    private final AuthService authService;

    /** 学生列表（含各自复制的图谱信息与登录账号）；按当前账号的机构范围过滤 */
    public List<Map<String, Object>> list() {
        LambdaQueryWrapper<Student> wrapper = new LambdaQueryWrapper<Student>()
                .orderByDesc(Student::getId);
        Long scope = UserContext.scopeOrgId();
        if (scope != null) {
            wrapper.eq(Student::getOrgId, scope);
        }
        List<Student> students = studentMapper.selectList(wrapper);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Student student : students) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", student.getId());
            item.put("name", student.getName());
            item.put("createdAt", student.getCreatedAt());
            item.put("orgId", student.getOrgId());
            item.put("campusId", student.getCampusId());
            item.put("clazzId", student.getClazzId());
            if (student.getClazzId() != null) {
                Clazz clazz = clazzMapper.selectById(student.getClazzId());
                item.put("clazzName", clazz == null ? null : clazz.getName());
            }
            SysUser account = authService.findByStudentId(student.getId());
            item.put("username", account == null ? null : account.getUsername());
            item.put("accountEnabled", account == null ? null : account.getEnabled());
            StudentGraph sg = latestStudentGraph(student.getId());
            if (sg != null) {
                Graph graph = graphStore.findGraph(sg.getSourceGraphId());
                if (graph != null) {
                    item.put("graphId", graph.getId());
                    item.put("graphName", graph.getName());
                    item.put("subject", graph.getSubject());
                    item.put("textbook", graph.getTextbook());
                    item.put("grade", graph.getGrade());
                    item.put("term", graph.getTerm());
                }
            }
            result.add(item);
        }
        return result;
    }

    /**
     * 新建学生：复制模板图谱（引用模板节点 + 初始化掌握状态），并在同一事务内开登录账号。
     * 开户失败（登录名重复等）整体回滚，不会留下没有账号的孤儿学生。
     */
    @Transactional
    public Map<String, Object> create(StudentCreateReq req) {
        Long orgId = UserContext.resolveWriteOrgId(req.orgId());
        List<Graph> found = graphStore.listGraphs(orgId, req.subject(), req.textbook(), req.grade(), req.term());
        if (found.isEmpty()) {
            throw new BizException("未找到对应教材图谱: " + req.subject() + "/" + req.textbook()
                    + "/" + req.grade() + "/" + req.term() + "，请先创建图谱");
        }
        Graph graph = found.get(0);

        // 分班：班级必须落在同一机构；教师只能把学生放进自己带的班
        Long campusId = req.campusId();
        if (req.clazzId() != null) {
            Clazz clazz = clazzMapper.selectById(req.clazzId());
            if (clazz == null) {
                throw new BizException("班级不存在: " + req.clazzId());
            }
            if (!orgId.equals(clazz.getOrgId())) {
                throw new ForbiddenException("不能把学生放进其他机构的班级");
            }
            UserContext.checkClazzScope(clazz.getId());
            campusId = clazz.getCampusId();
        }

        Student student = new Student();
        student.setOrgId(orgId);
        student.setCampusId(campusId);
        student.setClazzId(req.clazzId());
        student.setName(req.name());
        studentMapper.insert(student);

        StudentGraph sg = new StudentGraph();
        sg.setStudentId(student.getId());
        sg.setSourceGraphId(graph.getId());
        studentGraphMapper.insert(sg);

        // 复制：为每个模板节点初始化掌握状态（掌握度 0，权重引用模板节点）
        List<GraphNode> nodes = graphStore.listNodes(graph.getId());
        for (GraphNode node : nodes) {
            StudentNodeState state = new StudentNodeState();
            state.setStudentGraphId(sg.getId());
            state.setSourceNodeId(node.getId());
            state.setMastery(java.math.BigDecimal.ZERO);
            studentNodeStateMapper.insert(state);
        }

        // 开户：登录名未指定时自动生成 stu{id}，密码未指定时用默认初始密码
        String username = (req.username() == null || req.username().isBlank())
                ? authService.suggestUsername(student.getId())
                : req.username().trim();
        String password = (req.password() == null || req.password().isBlank())
                ? AuthService.DEFAULT_STUDENT_PWD
                : req.password();
        authService.createStudentAccount(username, password, student.getName(), student.getId(),
                student.getOrgId(), student.getCampusId());

        Map<String, Object> result = new HashMap<>();
        result.put("id", student.getId());
        result.put("name", student.getName());
        result.put("graphId", graph.getId());
        result.put("copiedNodes", nodes.size());
        result.put("username", username);
        result.put("initPassword", password);
        return result;
    }

    /** 重置学生登录密码（不传则恢复默认初始密码），改后该学生所有会话失效 */
    @Transactional
    public Map<String, Object> resetPassword(Long studentId, String password) {
        requireStudent(studentId);
        SysUser account = authService.findByStudentId(studentId);
        if (account == null) {
            throw new BizException("该学生还没有登录账号");
        }
        String pwd = (password == null || password.isBlank())
                ? AuthService.DEFAULT_STUDENT_PWD : password;
        authService.resetPassword(account.getId(), pwd);
        Map<String, Object> result = new HashMap<>();
        result.put("studentId", studentId);
        result.put("username", account.getUsername());
        result.put("password", pwd);
        return result;
    }

    /**
     * 学生图谱视图：模板节点 + 关系 + 各节点掌握度/权重
     */
    public Map<String, Object> studentGraph(Long studentId) {
        Student student = requireStudent(studentId);
        StudentGraph sg = latestStudentGraph(studentId);
        if (sg == null) {
            throw new BizException("该学生尚未复制图谱");
        }
        Graph graph = graphStore.findGraph(sg.getSourceGraphId());

        List<GraphNode> nodes = graphStore.listNodes(sg.getSourceGraphId());
        Map<Long, StudentNodeState> stateMap = studentNodeStateMapper.selectList(
                        new LambdaQueryWrapper<StudentNodeState>().eq(StudentNodeState::getStudentGraphId, sg.getId()))
                .stream().collect(Collectors.toMap(StudentNodeState::getSourceNodeId, s -> s, (a, b) -> a));

        List<Map<String, Object>> nodeList = new ArrayList<>();
        for (GraphNode node : nodes) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", node.getId());
            item.put("name", node.getName());
            item.put("description", node.getDescription());
            item.put("code", node.getCode());
            item.put("weight", node.getWeight());
            item.put("posX", node.getPosX());
            item.put("posY", node.getPosY());
            StudentNodeState state = stateMap.get(node.getId());
            item.put("mastery", state == null ? 0 : state.getMastery());
            item.put("lastPracticedAt", state == null ? null : state.getLastPracticedAt());
            nodeList.add(item);
        }

        List<NodeRelation> relations = graphStore.listRelations(sg.getSourceGraphId());

        Map<String, Object> result = new HashMap<>();
        result.put("student", student);
        result.put("graph", graph);
        result.put("studentGraphId", sg.getId());
        result.put("nodes", nodeList);
        result.put("relations", relations);
        return result;
    }

    /**
     * 人工修正掌握度（老师干预）。传 0 即为重置该知识点的练习状态。
     */
    @Transactional
    public Map<String, Object> adjustMastery(Long studentId, Long nodeId, java.math.BigDecimal mastery) {
        requireStudent(studentId);
        StudentGraph sg = latestStudentGraph(studentId);
        if (sg == null) {
            throw new BizException("该学生尚未复制图谱");
        }
        if (graphStore.findNode(nodeId) == null) {
            throw new BizException("知识点不存在: " + nodeId);
        }
        StudentNodeState state = studentNodeStateMapper.selectOne(new LambdaQueryWrapper<StudentNodeState>()
                .eq(StudentNodeState::getStudentGraphId, sg.getId())
                .eq(StudentNodeState::getSourceNodeId, nodeId));
        java.math.BigDecimal old = state == null || state.getMastery() == null
                ? java.math.BigDecimal.ZERO : state.getMastery();
        if (state == null) {
            state = new StudentNodeState();
            state.setStudentGraphId(sg.getId());
            state.setSourceNodeId(nodeId);
        }
        state.setMastery(mastery.setScale(4, java.math.RoundingMode.HALF_UP));
        if (state.getId() == null) {
            studentNodeStateMapper.insert(state);
        } else {
            studentNodeStateMapper.updateById(state);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("studentId", studentId);
        result.put("nodeId", nodeId);
        result.put("masteryBefore", old);
        result.put("masteryAfter", state.getMastery());
        return result;
    }

    /** 删除学生：级联清理图谱副本、掌握度状态与登录账号 */
    @Transactional
    public void delete(Long studentId) {
        requireStudent(studentId);
        studentMapper.deleteById(studentId);
        List<StudentGraph> sgs = studentGraphMapper.selectList(
                new LambdaQueryWrapper<StudentGraph>().eq(StudentGraph::getStudentId, studentId));
        for (StudentGraph sg : sgs) {
            studentNodeStateMapper.delete(new LambdaQueryWrapper<StudentNodeState>()
                    .eq(StudentNodeState::getStudentGraphId, sg.getId()));
            studentGraphMapper.deleteById(sg.getId());
        }
        authService.deleteByStudentId(studentId);
    }

    /** 取学生并校验机构归属：跨机构一律 403 */
    public Student requireStudent(Long studentId) {
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException("学生不存在: " + studentId);
        }
        if (student.getOrgId() != null) {
            UserContext.checkOrgScope(student.getOrgId());
        } else if (!UserContext.peek().isAdmin()) {
            throw new ForbiddenException("该学生未归属机构，请联系平台管理员");
        }
        return student;
    }

    private StudentGraph latestStudentGraph(Long studentId) {
        return studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                .eq(StudentGraph::getStudentId, studentId)
                .orderByDesc(StudentGraph::getId)
                .last("limit 1"));
    }
}
