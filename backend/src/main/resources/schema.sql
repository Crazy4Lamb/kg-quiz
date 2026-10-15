-- 知识图谱 + 题库 Demo Schema（幂等，可重复执行）
-- schema 由 JDBC URL 的 createDatabaseIfNotExist=true 自动创建
--
-- 【已迁移】图域（图谱 / 知识点节点 / 节点关系）整体搬到 Neo4j，DDL 见 resources/neo4j-schema.cypher。
-- 本文件只负责 MySQL 侧的业务域表。
--
-- 图域的业务键（BIGINT）仍由 MySQL 分配 —— 因为 question_node.node_id、
-- student_node_state.source_node_id、exam_question.node_id、answer_record.node_id、
-- exam.plan 里的 items[].nodeId、以及 Qdrant 的 point id 全都引用这个数值，
-- 必须保持与旧 graph_node.id 一一对应。Neo4j 内部 id / elementId 不稳定，不能用。
CREATE TABLE IF NOT EXISTS id_seq (
    name VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '序列名: graph / node / relation',
    val  BIGINT      NOT NULL DEFAULT 0 COMMENT '已分配到的最大值'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '图域业务键序列（供 Neo4j 节点与关系分配 uid）';

-- ========== 组织层：机构 → 校区 → 班级 ==========
-- 机构（org）是多租户的根：图谱、题库、学生、试卷全部挂在机构下，跨机构不可见。
-- 平台管理员（ADMIN）org_id 为 NULL，可跨机构；机构管理员（ORG_ADMIN）/教师/学生必属一个机构。

CREATE TABLE IF NOT EXISTS org (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(128) NOT NULL COMMENT '机构名称',
    code          VARCHAR(32)  NULL COMMENT '机构编码（对外唯一标识）',
    contact_name  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '联系人',
    contact_phone VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '联系电话',
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 正常 / SUSPENDED 停用',
    remark        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '备注',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_org_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '机构（租户根）';

CREATE TABLE IF NOT EXISTS campus (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id     BIGINT       NOT NULL COMMENT '所属机构',
    name       VARCHAR(128) NOT NULL COMMENT '校区名称',
    code       VARCHAR(32)  NULL COMMENT '校区编码',
    address    VARCHAR(255) NOT NULL DEFAULT '' COMMENT '地址',
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 正常 / SUSPENDED 停用',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_campus_name (org_id, name),
    KEY idx_org (org_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '校区';

CREATE TABLE IF NOT EXISTS clazz (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id          BIGINT       NOT NULL COMMENT '所属机构（冗余，便于租户过滤）',
    campus_id       BIGINT       NOT NULL COMMENT '所属校区',
    name            VARCHAR(128) NOT NULL COMMENT '班级名称',
    grade           VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '年级',
    subject         VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '主修科目',
    textbook        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '教材版本',
    term            VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '学期',
    head_teacher_id BIGINT       NULL COMMENT '班主任 sys_user.id',
    parent_clazz_id BIGINT       NULL COMMENT '上级班级（自引用，仅一层：平行班/实验班挂在常规班下）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 在读 / ARCHIVED 归档',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_campus (campus_id),
    KEY idx_org (org_id),
    KEY idx_parent_clazz (parent_clazz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '班级';

CREATE TABLE IF NOT EXISTS teacher_clazz (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    teacher_id BIGINT      NOT NULL COMMENT '教师 sys_user.id',
    clazz_id   BIGINT      NOT NULL COMMENT '班级',
    subject    VARCHAR(64) NOT NULL DEFAULT '' COMMENT '授课科目（空表示班主任/全科）',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_teacher_clazz (teacher_id, clazz_id, subject),
    KEY idx_clazz (clazz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '教师带班（授课）关系';

CREATE TABLE IF NOT EXISTS student (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id     BIGINT      NULL COMMENT '所属机构',
    campus_id  BIGINT      NULL COMMENT '所属校区',
    clazz_id   BIGINT      NULL COMMENT '所属班级',
    name       VARCHAR(64) NOT NULL COMMENT '学生姓名',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_org (org_id),
    KEY idx_clazz (clazz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '学生';

CREATE TABLE IF NOT EXISTS student_graph (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id       BIGINT   NOT NULL,
    source_graph_id  BIGINT   NOT NULL COMMENT '复制来源模板图谱',
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_student (student_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '学生图谱副本（引用模板节点 + 独立掌握度状态）';

-- mastery 是「最近一次测评反映的水平」，practice_count 决定这个数字有多可信：
-- 只练过 1 次得 0.9 分，和练过 12 次均分 0.9，是完全不同的两件事，不能同等对待。
-- last_score_rate 用于判断「这次是进步还是退步」，recommend 的遗忘因子靠 last_practiced_at 与它一起算。
CREATE TABLE IF NOT EXISTS student_node_state (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_graph_id  BIGINT        NOT NULL,
    source_node_id    BIGINT        NOT NULL COMMENT '模板节点 id',
    mastery           DECIMAL(5, 4) NOT NULL DEFAULT 0 COMMENT '掌握度 0-1',
    practice_count    INT           NOT NULL DEFAULT 0 COMMENT '累计有效练习次数（置信度分母）',
    last_score_rate   DECIMAL(5, 4) NULL COMMENT '最近一次得分率',
    mastery_peak      DECIMAL(5, 4) NOT NULL DEFAULT 0 COMMENT '历史最高掌握度（遗忘后仍可达的锚点）',
    last_practiced_at DATETIME NULL,
    UNIQUE KEY uk_sg_node (student_graph_id, source_node_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '学生节点掌握状态';

CREATE TABLE IF NOT EXISTS question (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id        BIGINT        NULL COMMENT '所属机构（题库按机构隔离）',
    subject       VARCHAR(64)   NOT NULL COMMENT '科目',
    textbook      VARCHAR(64)   NOT NULL COMMENT '教材',
    question_type VARCHAR(16)   NOT NULL COMMENT 'SINGLE_CHOICE/MULTI_CHOICE/TRUE_FALSE/FILL_BLANK/SHORT_ANSWER',
    stem          TEXT          NOT NULL COMMENT '题干',
    options       TEXT          NULL COMMENT '选项 JSON 数组',
    answer        TEXT          NOT NULL COMMENT '答案',
    analysis      TEXT          NULL COMMENT '解析',
    score         DECIMAL(6, 2) NOT NULL DEFAULT 5 COMMENT '分值',
    difficulty    TINYINT       NOT NULL DEFAULT 3 COMMENT '难度 1-5',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_dim (subject, textbook),
    KEY idx_org (org_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '题目';

CREATE TABLE IF NOT EXISTS question_node (
    question_id BIGINT NOT NULL,
    node_id     BIGINT NOT NULL COMMENT '关联知识点（模板节点）',
    PRIMARY KEY (question_id, node_id),
    KEY idx_node (node_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '题目-知识点 多对多';

-- 卷子：既是自适应引擎为学生生成的练习（student_id 非空），也是教师组卷（student_id 为空）
-- 只有创建者/管理员能编辑；发给谁由 exam_assignment 决定
CREATE TABLE IF NOT EXISTS exam (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id       BIGINT       NULL COMMENT '所属机构',
    student_id   BIGINT       NULL COMMENT '自适应练习的归属学生；教师组卷为 NULL',
    name         VARCHAR(128) NOT NULL DEFAULT '' COMMENT '卷子名称',
    source       VARCHAR(16)  NOT NULL DEFAULT 'ADAPTIVE' COMMENT 'ADAPTIVE 自适应 / TEACHER 教师组卷',
    subject      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '科目',
    textbook     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '教材',
    description  VARCHAR(512) NOT NULL DEFAULT '' COMMENT '说明（学生可见）',
    created_by   BIGINT       NULL COMMENT '出卷人 sys_user.id',
    plan         TEXT         NOT NULL COMMENT '出题方案 JSON',
    status       VARCHAR(16)  NOT NULL DEFAULT 'CONFIRMED' COMMENT 'PENDING 待确认 / CONFIRMED 可作答',
    published_at DATETIME     NULL COMMENT '发布时间',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_student (student_id),
    KEY idx_creator (created_by),
    KEY idx_org (org_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '卷子（自适应练习 / 教师组卷）';

CREATE TABLE IF NOT EXISTS exam_question (
    exam_id     BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    node_id     BIGINT NOT NULL COMMENT '该题在此卷子中归属的知识点',
    sort_order  INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (exam_id, question_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '卷子-题目';

-- 指派：一份卷子发给一个学生。学生只能看到指派给自己的卷子
CREATE TABLE IF NOT EXISTS exam_assignment (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    exam_id       BIGINT        NOT NULL,
    student_id    BIGINT        NOT NULL,
    status        VARCHAR(16)   NOT NULL DEFAULT 'ASSIGNED' COMMENT 'ASSIGNED 待作答 / SUBMITTED 已交待批改 / GRADED 已定稿',
    total_score   DECIMAL(7, 2) NULL COMMENT '得分',
    full_score    DECIMAL(7, 2) NULL COMMENT '满分',
    pending_count INT           NOT NULL DEFAULT 0 COMMENT '待人工批改题数',
    submitted_at  DATETIME      NULL,
    graded_at     DATETIME      NULL,
    grader_id     BIGINT        NULL COMMENT '最后批改人',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_exam_student (exam_id, student_id),
    KEY idx_student (student_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '作业指派：一份卷子发给一个学生';

CREATE TABLE IF NOT EXISTS answer_record (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id     BIGINT        NOT NULL,
    exam_id        BIGINT        NULL,
    assignment_id  BIGINT        NULL COMMENT '所属指派',
    question_id    BIGINT        NOT NULL,
    node_id        BIGINT        NOT NULL COMMENT '归属知识点',
    student_answer TEXT          NULL COMMENT '学生作答原文',
    auto_score     DECIMAL(6, 2) NULL COMMENT '机器判分',
    score          DECIMAL(6, 2) NOT NULL COMMENT '实际得分（教师改分后以此为准）',
    full_score     DECIMAL(6, 2) NOT NULL COMMENT '满分',
    graded_by      VARCHAR(16)   NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO 机器 / MANUAL 人工',
    comment        VARCHAR(512)  NOT NULL DEFAULT '' COMMENT '教师评语',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_student_node (student_id, node_id),
    KEY idx_assignment (assignment_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '答题记录';

-- ========== 账号与登录 ==========

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL COMMENT '登录名',
    password      VARCHAR(255) NOT NULL COMMENT 'PBKDF2 密文（pbkdf2$迭代$盐$哈希）',
    role          VARCHAR(16)  NOT NULL COMMENT 'ADMIN 平台管理员 / ORG_ADMIN 机构管理员 / TEACHER 教师 / STUDENT 学生',
    org_id        BIGINT       NULL COMMENT '所属机构（平台管理员为 NULL，可跨机构）',
    campus_id     BIGINT       NULL COMMENT '所属校区（教师归属校区，机构管理员可为 NULL）',
    display_name  VARCHAR(64)  NOT NULL COMMENT '显示名',
    student_id    BIGINT       NULL COMMENT '学生角色绑定的 student.id',
    enabled       TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用',
    last_login_at DATETIME     NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username),
    KEY idx_student (student_id),
    KEY idx_org (org_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '系统账号';

-- ========== 教学内容生成（知识点 → 讲义 / 例题 / 微课脚本） ==========
-- generated_by 区分是大模型生成还是教师手写；status 控制学生端可见性（草稿不可见）。
CREATE TABLE IF NOT EXISTS node_content (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id       BIGINT       NOT NULL COMMENT '所属机构（租户）',
    graph_id     BIGINT       NOT NULL COMMENT '所属图谱',
    node_id      BIGINT       NOT NULL COMMENT '知识点（Neo4j KgNode.uid）',
    type         VARCHAR(24)  NOT NULL COMMENT 'LECTURE 讲义 / EXAMPLE 例题 / MICRO_LESSON 微课脚本 / CARD 知识卡片',
    title        VARCHAR(200) NOT NULL DEFAULT '' COMMENT '标题',
    summary      VARCHAR(500) NOT NULL DEFAULT '' COMMENT '一句话摘要',
    body         MEDIUMTEXT   NOT NULL COMMENT 'Markdown 正文',
    generated_by VARCHAR(16)  NOT NULL DEFAULT 'LLM' COMMENT 'LLM 大模型生成 / TEACHER 教师编写',
    model        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '生成时使用的模型名',
    version      INT          NOT NULL DEFAULT 1 COMMENT '版本号，重新生成时递增',
    status       VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT 草稿 / PUBLISHED 已发布',
    created_by   BIGINT       NULL COMMENT '创建人 sys_user.id',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_org (org_id),
    KEY idx_node (node_id),
    KEY idx_graph_type (graph_id, type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '知识点教学内容';

-- ========== 学习优先级推荐规则 ==========
-- 把原来硬编码在出题引擎里的权重抽出来，机构可自建规则、可解释、可干预。
CREATE TABLE IF NOT EXISTS recommend_rule (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id     BIGINT      NULL COMMENT '所属机构；NULL 表示平台默认规则',
    name       VARCHAR(64) NOT NULL COMMENT '规则名称',
    strategy   VARCHAR(24) NOT NULL DEFAULT 'BALANCED' COMMENT '推荐策略：REMEDIATION 补弱优先 / SEQUENTIAL 顺序推进 / EXAM 应试优先 / BALANCED 均衡',
    is_default TINYINT     NOT NULL DEFAULT 0 COMMENT '1 = 该机构默认规则',
    enabled    TINYINT     NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用',
    config     TEXT        NOT NULL COMMENT '权重与阈值 JSON',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_org (org_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '学习优先级推荐规则';

-- ========== 学习任务流 ==========
-- 学生每天打开就能走的清单：诊断 → 学 → 练 → 复习。任务由诊断结果与推荐规则驱动。
CREATE TABLE IF NOT EXISTS learning_task (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id        BIGINT       NOT NULL COMMENT '所属机构（租户）',
    student_id    BIGINT       NOT NULL COMMENT '学生',
    node_id       BIGINT       NULL COMMENT '关联知识点（复习类任务可为空）',
    type          VARCHAR(16)  NOT NULL COMMENT 'DIAGNOSIS 诊断 / LEARN 学习 / PRACTICE 练习 / REVIEW 复习',
    title         VARCHAR(200) NOT NULL DEFAULT '' COMMENT '任务标题',
    reason        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '推荐理由（可解释）',
    priority      INT          NOT NULL DEFAULT 0 COMMENT '优先级，越小越先做',
    source        VARCHAR(16)  NOT NULL DEFAULT 'SYSTEM' COMMENT 'SYSTEM 系统推荐 / TEACHER 教师布置',
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待做 / DONE 已完成 / SKIPPED 已跳过',
    exam_id       BIGINT       NULL COMMENT '关联卷子（练习/诊断类任务）',
    content_id    BIGINT       NULL COMMENT '关联教学内容（学习类任务）',
    due_at        DATETIME     NULL COMMENT '截止时间',
    completed_at  DATETIME     NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_org (org_id),
    KEY idx_student (student_id),
    KEY idx_student_status (student_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '学习任务流';

-- ========== 学习记录（知识点层面的行为流水）============
-- learning_task 记的是「该做什么」，learning_record 记的是「实际做了什么、产生了什么变化」。
-- 两者的区别是前者可被重排覆盖，后者只追加不改写 —— 推荐算法的「近期重复惩罚」「遗忘间隔」
-- 「置信度」三个因子全部依赖这张表，所以它不能被任务重排冲掉。
--
-- 每次写记录都带 mastery_before / mastery_after，形成掌握度变化轨迹，
-- 教师端「这个孩子是怎么一步步变成现在这样的」才有据可查。
CREATE TABLE IF NOT EXISTS learning_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id          BIGINT       NOT NULL COMMENT '所属机构（租户）',
    student_id      BIGINT       NOT NULL COMMENT '学生',
    node_id         BIGINT       NULL COMMENT '知识点；无知识点级事件（如入学诊断）为空',
    action          VARCHAR(24)  NOT NULL COMMENT 'VIEW 学讲解 / PRACTICE 做题 / DIAGNOSIS 诊断 / REVIEW 复习 / TASK_DONE 完成任务 / ADJUST 教师改分',
    ref_type        VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '关联对象类型：TASK/EXAM/CONTENT/ANSWER',
    ref_id          BIGINT       NULL COMMENT '关联对象 id（任务 id / 卷子 id / 内容 id）',
    task_id         BIGINT       NULL COMMENT '触发本次行为的学习任务（能回溯「推荐 → 执行」闭环）',
    score_rate      DECIMAL(5, 4) NULL COMMENT '本次得分率 0-1',
    duration_sec    INT          NOT NULL DEFAULT 0 COMMENT '本次投入时长（秒）',
    question_count  INT          NOT NULL DEFAULT 0 COMMENT '本次涉及题目数',
    mastery_before  DECIMAL(5, 4) NULL COMMENT '行为发生前掌握度',
    mastery_after   DECIMAL(5, 4) NULL COMMENT '行为发生后掌握度',
    note            VARCHAR(255) NOT NULL DEFAULT '' COMMENT '备注（卷子名 / 判分说明）',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_student (student_id, created_at),
    KEY idx_student_node (student_id, node_id),
    KEY idx_org (org_id),
    KEY idx_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '知识点学习记录流水（只追加）';

CREATE TABLE IF NOT EXISTS sys_token (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    token      VARCHAR(64) NOT NULL COMMENT '登录令牌（UUID）',
    user_id    BIGINT      NOT NULL,
    expire_at  DATETIME    NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_token (token),
    KEY idx_user (user_id),
    KEY idx_expire (expire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '登录会话令牌';

-- ========== Agent 配置域 ==========
-- 三层边界：工具是代码（不入库）、编排是数据（本节落库）、权限是运行时算（由 UserContext 决定）。
-- agent_profile 只描述「谁、用什么编排、什么模型、在哪里启用」，不承载任何数据权限 —— 那是 Service 层的事。
--
-- 生效范围（org_id + campus_id + clazz_id 三列从粗到细，只允许「最细的那列有值」）：
--   org_id=NULL                   → 平台级，全平台生效
--   org_id=X, campus_id=NULL      → 该机构全部校区
--   org_id=X, campus_id=Y         → 该校区全部班级
--   org_id=X, campus_id=Y, clazz_id=Z → 仅该班级
-- 注意：这是「在哪里启用这个 Agent」，不是「能读哪些数据」。后者永远由 UserContext 实时算。
CREATE TABLE IF NOT EXISTS agent_profile (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    code          VARCHAR(64)  NOT NULL COMMENT '业务编码，唯一。种子用固定值便于引用',
    org_id        BIGINT       NULL COMMENT '所属机构；NULL 表示平台级 Agent',
    campus_id     BIGINT       NULL COMMENT '生效校区；NULL 表示机构内全部校区',
    clazz_id      BIGINT       NULL COMMENT '生效班级；NULL 表示校区/机构内全部班级',
    name          VARCHAR(64)  NOT NULL COMMENT 'Agent 名称',
    description   VARCHAR(255) NOT NULL DEFAULT '' COMMENT '一句话说明它做什么',
    target_role   VARCHAR(32)  NOT NULL DEFAULT 'STUDENT' COMMENT '面向角色：STUDENT / TEACHER / ADMIN',
    system_prompt TEXT         NULL COMMENT '系统提示词（二期开放在线编辑）',
    model         VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '模型标识，留空用后端默认',
    temperature   DOUBLE       NOT NULL DEFAULT 0.3 COMMENT '采样温度',
    max_tokens    INT          NOT NULL DEFAULT 2048 COMMENT '单次响应上限',
    enabled       TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_code (code),
    KEY idx_org (org_id),
    KEY idx_scope (org_id, campus_id, clazz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'Agent 编排配置';

-- Agent 与工具的绑定。注意：本表只决定「看不看得到这个工具」，
-- 绝不决定「能访问哪些数据」—— 后者永远由 Service 层按 UserContext 实时算。
CREATE TABLE IF NOT EXISTS agent_tool (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id       BIGINT      NOT NULL,
    tool_key       VARCHAR(64) NOT NULL COMMENT '工具标识，对应代码里的工具目录',
    enabled        TINYINT     NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用',
    param_override TEXT        NULL COMMENT '工具级参数覆盖 JSON，如 {"minConfidence":0.6}',
    sort_order     INT         NOT NULL DEFAULT 0 COMMENT '排序，越小越靠前',
    UNIQUE KEY uk_agent_tool (agent_id, tool_key),
    KEY idx_agent (agent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'Agent 工具绑定';

-- 工具调用观测：喂给工具注册表的「调用次数 / 成功率 / 平均耗时」
CREATE TABLE IF NOT EXISTS agent_call_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id          BIGINT      NULL COMMENT 'Agent，平台级巡检可为空',
    tool_key          VARCHAR(64) NOT NULL,
    operator_user_id  BIGINT      NULL COMMENT '发起者',
    target_student_id BIGINT      NULL COMMENT '预演时的目标学生（真实调用为会话学生）',
    ok                TINYINT     NOT NULL DEFAULT 1,
    cost_ms           BIGINT      NOT NULL DEFAULT 0,
    error             VARCHAR(255) NULL,
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_tool (tool_key),
    KEY idx_agent (agent_id),
    KEY idx_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'Agent 工具调用观测';
