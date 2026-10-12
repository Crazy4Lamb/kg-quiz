package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.dto.QuestionBatchReq;
import com.kgquiz.quiz.dto.QuestionReq;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.Question;
import com.kgquiz.quiz.entity.QuestionNode;
import com.kgquiz.quiz.mapper.QuestionMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import com.kgquiz.quiz.mapper.QuestionNodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class QuestionService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Set<String> VALID_TYPES = Set.of(
            "SINGLE_CHOICE", "MULTI_CHOICE", "TRUE_FALSE", "FILL_BLANK", "SHORT_ANSWER");

    private final QuestionMapper questionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final GraphStore graphStore;

    public Map<String, Object> list(String subject, String textbook, String questionType,
                                    String keyword, Long nodeId, int page, int size) {
        LambdaQueryWrapper<Question> qw = new LambdaQueryWrapper<>();
        // 题库按机构隔离：平台管理员（scope 为 null）看全部，其余只看本机构
        Long scope = UserContext.scopeOrgId();
        qw.eq(scope != null, Question::getOrgId, scope)
                .eq(subject != null && !subject.isEmpty(), Question::getSubject, subject)
                .eq(textbook != null && !textbook.isEmpty(), Question::getTextbook, textbook)
                .eq(questionType != null && !questionType.isEmpty(), Question::getQuestionType, questionType)
                .like(keyword != null && !keyword.isEmpty(), Question::getStem, keyword)
                .orderByDesc(Question::getId);

        if (nodeId != null) {
            List<Long> questionIds = questionNodeMapper.selectList(
                            new LambdaQueryWrapper<QuestionNode>().eq(QuestionNode::getNodeId, nodeId))
                    .stream().map(QuestionNode::getQuestionId).toList();
            if (questionIds.isEmpty()) {
                Map<String, Object> empty = new HashMap<>();
                empty.put("total", 0);
                empty.put("records", List.of());
                return empty;
            }
            qw.in(Question::getId, questionIds);
        }

        long total = questionMapper.selectCount(qw);
        qw.last("limit " + (page - 1) * size + ", " + size);
        List<Question> questions = questionMapper.selectList(qw);

        List<Map<String, Object>> records = new ArrayList<>();
        for (Question q : questions) {
            records.add(toDetail(q, false));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("records", records);
        return result;
    }

    public Map<String, Object> detail(Long id) {
        Question q = getById(id);
        return toDetail(q, true);
    }

    @Transactional
    public Question create(QuestionReq req) {
        validate(req);
        Question q = new Question();
        applyReq(q, req);
        q.setOrgId(UserContext.resolveWriteOrgId(req.orgId()));
        questionMapper.insert(q);
        saveNodeLinks(q.getId(), req.nodeIds());
        return q;
    }

    @Transactional
    public Question update(Long id, QuestionReq req) {
        validate(req);
        Question q = getById(id);
        applyReq(q, req);
        questionMapper.updateById(q);
        questionNodeMapper.delete(new LambdaQueryWrapper<QuestionNode>()
                .eq(QuestionNode::getQuestionId, id));
        saveNodeLinks(id, req.nodeIds());
        return q;
    }

    @Transactional
    public void delete(Long id) {
        getById(id);
        questionNodeMapper.delete(new LambdaQueryWrapper<QuestionNode>()
                .eq(QuestionNode::getQuestionId, id));
        questionMapper.deleteById(id);
    }

    /**
     * 批量导入题目。
     *
     * <p>策略：<b>先全量校验，有错就整体不落库</b>，把每一行的具体原因回给前端（第几行、
     * 什么原因），教研改完再导一次。避免一半进一半不进留下脏数据。</p>
     *
     * <p>知识点支持两种写法：{@code nodeIds}（id）与 {@code nodeNames}（名称，需配合
     * {@code graphId}），取并集。</p>
     */
    @Transactional
    public Map<String, Object> batchCreate(QuestionBatchReq req) {
        Map<String, Long> nameToId = new HashMap<>();
        if (req.graphId() != null) {
            graphStore.listNodes(req.graphId())
                    .forEach(n -> nameToId.putIfAbsent(n.getName(), n.getId()));
        }

        List<Map<String, Object>> errors = new ArrayList<>();
        List<Resolved> resolved = new ArrayList<>();
        int row = 0;

        for (QuestionBatchReq.Item item : req.items()) {
            row++;
            List<String> reasons = new ArrayList<>();
            if (!VALID_TYPES.contains(item.questionType())) {
                reasons.add("不支持的题型: " + item.questionType());
            }
            if (item.difficulty() < 1 || item.difficulty() > 5) {
                reasons.add("难度需在 1-5");
            }
            if (item.score().signum() < 0) {
                reasons.add("分值不能为负");
            }
            LinkedHashSet<Long> nodeIds = new LinkedHashSet<>();
            if (item.nodeIds() != null) {
                nodeIds.addAll(item.nodeIds());
            }
            if (item.nodeNames() != null) {
                for (String rawName : item.nodeNames()) {
                    if (rawName == null || rawName.isBlank()) {
                        continue;
                    }
                    String name = rawName.trim();
                    Long id = nameToId.get(name);
                    if (id == null) {
                        reasons.add("知识点不存在: " + name);
                    } else {
                        nodeIds.add(id);
                    }
                }
            }
            // 知识点允许留空：整批导入后可以走「一键映射知识点」自动补，
            // 否则教研必须先手工标注每一道题，批量导入就失去意义了。
            for (Long nodeId : nodeIds) {
                if (graphStore.findNode(nodeId) == null) {
                    reasons.add("知识点不存在: " + nodeId);
                }
            }
            if (!reasons.isEmpty()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("row", row);
                err.put("stem", abbreviate(item.stem()));
                err.put("reasons", reasons);
                errors.add(err);
                continue;
            }
            resolved.add(new Resolved(item, List.copyOf(nodeIds)));
        }

        if (!errors.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("created", 0);
            result.put("errors", errors);
            return result;
        }

        Long batchOrgId = UserContext.resolveWriteOrgId(req.orgId());
        List<Long> unmappedIds = new ArrayList<>();
        for (Resolved r : resolved) {
            Question q = new Question();
            applyReq(q, new QuestionReq(
                    r.item.subject(), r.item.textbook(), r.item.questionType(), r.item.stem(),
                    r.item.options(), r.item.answer(), r.item.analysis(),
                    r.item.score(), r.item.difficulty(), r.nodeIds(), null));
            q.setOrgId(batchOrgId);
            questionMapper.insert(q);
            saveNodeLinks(q.getId(), r.nodeIds);
            if (r.nodeIds().isEmpty()) {
                unmappedIds.add(q.getId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", resolved.size());
        result.put("errors", List.of());
        result.put("unmappedCount", unmappedIds.size());
        result.put("unmappedQuestionIds", unmappedIds);
        return result;
    }

    private record Resolved(QuestionBatchReq.Item item, List<Long> nodeIds) {
    }

    private String abbreviate(String stem) {
        if (stem == null) {
            return "";
        }
        String flat = stem.replaceAll("\\s+", " ").trim();
        return flat.length() <= 30 ? flat : flat.substring(0, 30) + "…";
    }

    private void validate(QuestionReq req) {
        if (!VALID_TYPES.contains(req.questionType())) {
            throw new BizException("不支持的题型: " + req.questionType());
        }
        if (req.difficulty() < 1 || req.difficulty() > 5) {
            throw new BizException("难度取值 1-5");
        }
        for (Long nodeId : req.nodeIds()) {
            if (graphStore.findNode(nodeId) == null) {
                throw new BizException("知识点不存在: " + nodeId);
            }
        }
    }

    private void applyReq(Question q, QuestionReq req) {
        q.setSubject(req.subject());
        q.setTextbook(req.textbook());
        q.setQuestionType(req.questionType());
        q.setStem(req.stem());
        q.setOptions(req.options() == null ? null : toJson(req.options()));
        q.setAnswer(req.answer());
        q.setAnalysis(req.analysis());
        q.setScore(req.score());
        q.setDifficulty(req.difficulty());
    }

    private void saveNodeLinks(Long questionId, List<Long> nodeIds) {
        for (Long nodeId : nodeIds) {
            QuestionNode qn = new QuestionNode();
            qn.setQuestionId(questionId);
            qn.setNodeId(nodeId);
            questionNodeMapper.insert(qn);
        }
    }

    private Map<String, Object> toDetail(Question q, boolean withAnswer) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", q.getId());
        item.put("subject", q.getSubject());
        item.put("textbook", q.getTextbook());
        item.put("questionType", q.getQuestionType());
        item.put("stem", q.getStem());
        item.put("options", parseJsonArray(q.getOptions()));
        item.put("analysis", q.getAnalysis());
        item.put("score", q.getScore());
        item.put("difficulty", q.getDifficulty());
        item.put("createdAt", q.getCreatedAt());
        if (withAnswer) {
            item.put("answer", q.getAnswer());
        }
        List<Long> nodeIds = questionNodeMapper.selectList(new LambdaQueryWrapper<QuestionNode>()
                        .eq(QuestionNode::getQuestionId, q.getId()))
                .stream().map(QuestionNode::getNodeId).toList();
        item.put("nodeIds", nodeIds);
        if (!nodeIds.isEmpty()) {
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (Long nodeId : nodeIds) {
                GraphNode node = graphStore.findNode(nodeId);
                if (node != null) {
                    Map<String, Object> n = new HashMap<>();
                    n.put("id", node.getId());
                    n.put("name", node.getName());
                    n.put("graphId", node.getGraphId());
                    nodes.add(n);
                }
            }
            item.put("nodes", nodes);
        }
        return item;
    }

    private Question getById(Long id) {
        Question q = questionMapper.selectById(id);
        if (q == null) {
            throw new BizException("题目不存在: " + id);
        }
        if (q.getOrgId() != null) {
            UserContext.checkOrgScope(q.getOrgId());
        } else if (!UserContext.require().isAdmin()) {
            // 历史数据没有机构归属时保守处理：只有平台管理员可见
            throw new BizException("题目不存在: " + id);
        }
        return q;
    }

    private String toJson(List<String> options) {
        try {
            return OBJECT_MAPPER.writeValueAsString(options);
        } catch (Exception e) {
            throw new BizException("选项序列化失败");
        }
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json,
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return null;
        }
    }
}
