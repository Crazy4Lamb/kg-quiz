package com.kgquiz.quiz.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具目录（配置域的<b>代码层</b>）。
 *
 * <p>工具在这里<b>显式声明</b>，不进数据库——因为工具是有副作用的代码，
 * 「能不能被调」取决于它的实现与后端的鉴权，而不是取决于表里有没有一行。</p>
 *
 * <p>与之相对，{@code agent_profile} / {@code agent_tool} 落库存的是
 * <b>编排</b>（谁、什么提示词、挂哪些工具）。</p>
 *
 * <p>{@link SideEffect#WRITE} 的工具在管理台会被标红，配 Agent 时给出警告；
 * 它们也<b>不参与</b> {@link AgentService#dryRun} 上下文预演——
 * 预演只跑只读工具，绝不产生副作用。</p>
 */
public final class AgentToolCatalog {

    private AgentToolCatalog() {
    }

    /** 工具副作用。READ 可进预演；WRITE 只在真实调用时用，且需人工确认。 */
    public enum SideEffect {
        READ("只读"),
        WRITE("写入");

        private final String label;

        SideEffect(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一个工具的定义。
     *
     * @param key         工具标识（Agent 绑定与调用日志用这个）
     * @param name        中文名
     * @param category    分类，用于管理台分组
     * @param sideEffect  副作用
     * @param endpoint    对应的后端接口（文档用途，便于回溯权限边界）
     * @param description 描述；这段文字会直接进 Agent 的提示词，写清楚很重要
     * @param dryRunnable 是否参与上下文预演
     */
    public record Tool(
            String key,
            String name,
            String category,
            SideEffect sideEffect,
            String endpoint,
            String description,
            boolean dryRunnable) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("name", name);
            m.put("category", category);
            m.put("sideEffect", sideEffect.name());
            m.put("sideEffectLabel", sideEffect.label());
            m.put("writable", sideEffect == SideEffect.WRITE);
            m.put("endpoint", endpoint);
            m.put("description", description);
            m.put("dryRunnable", dryRunnable);
            return m;
        }
    }

    private static Tool read(String key, String name, String category, String endpoint, String description) {
        return new Tool(key, name, category, SideEffect.READ, endpoint, description, true);
    }

    private static Tool write(String key, String name, String category, String endpoint, String description) {
        return new Tool(key, name, category, SideEffect.WRITE, endpoint, description, false);
    }

    /**
     * 工具全集。刻意做成白名单常量而不是反射扫描：
     * 一是工具的参数签名本来就要人工写给模型看，二是反射扫 Controller 容易把
     * 非工具的接口（登录、健康检查）也捞进来。
     */
    public static final List<Tool> ALL = List.of(
            // ---------- 诊断 ----------
            read("diagnosis_my_report", "查我的诊断报告", "诊断",
                    "GET /api/diagnosis/my/report",
                    "返回当前学生的逐知识点得分率、掌握度起点、薄弱与已掌握清单、未覆盖知识点。回答「我哪里不行」时用。"),
            read("diagnosis_report", "查某个学生的诊断报告", "诊断",
                    "GET /api/diagnosis/{studentId}/report",
                    "教师/管理员查看指定学生的诊断报告。必须传 studentId，受机构与班级范围约束。"),
            // ---------- 学习 ----------
            read("my_tasks_today", "看我的今日学习任务", "学习",
                    "GET /api/tasks/my/today",
                    "返回当前学生今天的学习任务清单：诊断 → 学 → 练 → 复习，附带可解释的推荐理由。"),
            read("student_tasks", "看某学生的学习任务", "学习",
                    "GET /api/tasks/student/{studentId}",
                    "教师查看指定学生的学习任务清单与完成情况。"),
            read("my_recommend", "看我的学习优先级", "学习",
                    "GET /api/recommend/my",
                    "返回当前学生该按什么顺序学，带分数与理由。只能解释，不能改写——推荐公式真源在后端。"),
            read("published_content", "读某个知识点的已发布内容", "学习",
                    "GET /api/contents/node/{nodeId}/published",
                    "读取某个知识点已发布的教学内容（讲义/例题/微课/卡片）。草稿对学生不可见。"),
            // ---------- 检索 ----------
            read("search_nodes", "检索知识点", "检索",
                    "GET /api/search?q=",
                    "按关键词检索知识图谱节点（ID 精确 / 关系 / 语义三路）。"),
            read("search_questions", "检索题目", "检索",
                    "GET /api/questions?q=",
                    "按题干关键词检索题目。"),
            // ---------- 教研（写入，需人工确认）----------
            write("mapping_suggest", "识别题目知识点（预演）", "教研",
                    "POST /api/questions/mapping/suggest",
                    "为单道题召回并判定候选知识点，返回候选列表与置信度。只给建议，不写入。"),
            write("content_generate", "生成教学内容（草稿）", "教研",
                    "POST /api/contents/generate",
                    "为知识点生成教学内容。未配置大模型时降级为规则模板，产出为草稿，学生看不到。"),
            write("practice_generate", "生成专项练习", "教研",
                    "POST /api/exams/my/practice",
                    "为指定知识点现场出一份专项练习并直接指派给学生。"),
            write("assign_task", "布置学习任务", "教研",
                    "POST /api/tasks/teacher",
                    "教师手工给学生布置一条学习任务。"),
            // ---------- 图谱治理 ----------
            read("graph_analysis", "图谱分析", "图谱",
                    "GET /api/analysis/{graphId}/...",
                    "知识点核心度排序、学习路径、前置缺口分析。"),
            write("node_create", "新增知识点", "图谱",
                    "POST /api/graphs/{graphId}/nodes",
                    "在图谱中新增节点，可在同一次事务里带上与锚点的关系。"),
            write("relation_create", "新增关系", "图谱",
                    "POST /api/graphs/{graphId}/relations",
                    "在两个已存在的知识点之间建立前置/包含/相近关系。"),
            write("mapping_apply", "确认写入知识点映射", "教研",
                    "POST /api/questions/mapping/apply",
                    "把确认过的知识点映射写入题库。这是不可逆的写操作，必须经过人工确认。")
    );

    /** 按 key 索引 */
    public static Tool byKey(String key) {
        for (Tool t : ALL) {
            if (t.key().equals(key)) {
                return t;
            }
        }
        return null;
    }

    /** 可参与上下文预演的只读工具 */
    public static List<Tool> dryRunnable() {
        return ALL.stream().filter(Tool::dryRunnable).toList();
    }
}