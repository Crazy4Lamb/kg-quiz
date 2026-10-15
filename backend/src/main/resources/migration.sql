-- 增量迁移（幂等）：schema.sql 用的是 CREATE TABLE IF NOT EXISTS，对已存在的表不会补列，
-- 所以新加/变更的列集中在这里。重复执行时 "Duplicate column name" 之类的错误会被
-- spring.sql.init.continue-on-error=true 跳过，因此本文件必须只放幂等语句。

-- ========== exam：从「某学生的练习」升级为通用「卷子」 ==========
-- student_id 改为可空：教师组卷为公共卷（NULL），自适应练习仍写归属学生
ALTER TABLE exam MODIFY COLUMN student_id BIGINT NULL;
ALTER TABLE exam ADD COLUMN name        VARCHAR(128) NOT NULL DEFAULT '' COMMENT '卷子名称';
ALTER TABLE exam ADD COLUMN source      VARCHAR(16)  NOT NULL DEFAULT 'ADAPTIVE' COMMENT 'ADAPTIVE 自适应 / TEACHER 教师组卷';
ALTER TABLE exam ADD COLUMN subject     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '科目';
ALTER TABLE exam ADD COLUMN textbook    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '教材';
ALTER TABLE exam ADD COLUMN description VARCHAR(512) NOT NULL DEFAULT '' COMMENT '说明（学生可见）';
ALTER TABLE exam ADD COLUMN created_by  BIGINT       NULL COMMENT '出卷人 sys_user.id';
ALTER TABLE exam ADD COLUMN published_at DATETIME    NULL COMMENT '发布时间';

-- ========== exam_assignment：卷子发给哪个学生 ==========
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

-- ========== answer_record：记录学生作答原文与批改结果 ==========
ALTER TABLE answer_record ADD COLUMN assignment_id  BIGINT       NULL COMMENT '所属指派';
ALTER TABLE answer_record ADD COLUMN student_answer TEXT         NULL COMMENT '学生作答原文';
ALTER TABLE answer_record ADD COLUMN auto_score     DECIMAL(6, 2) NULL COMMENT '机器判分（客观题精确、主观题关键词参考分）';
ALTER TABLE answer_record ADD COLUMN graded_by      VARCHAR(16)  NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO 机器 / MANUAL 人工';
ALTER TABLE answer_record ADD COLUMN comment        VARCHAR(512) NOT NULL DEFAULT '' COMMENT '教师评语';
ALTER TABLE answer_record ADD INDEX idx_assignment (assignment_id);

-- ========== 组织层与多租户：机构 → 校区 → 班级 ==========
-- org / campus / clazz / teacher_clazz 四张新表由 schema.sql 建出，这里只给「已存在的表」补租户列。
ALTER TABLE student ADD COLUMN org_id    BIGINT NULL COMMENT '所属机构';
ALTER TABLE student ADD COLUMN campus_id BIGINT NULL COMMENT '所属校区';
ALTER TABLE student ADD COLUMN clazz_id  BIGINT NULL COMMENT '所属班级';
ALTER TABLE student ADD INDEX idx_org (org_id);
ALTER TABLE student ADD INDEX idx_clazz (clazz_id);

ALTER TABLE question ADD COLUMN org_id BIGINT NULL COMMENT '所属机构（题库按机构隔离）';
ALTER TABLE question ADD INDEX idx_org (org_id);

ALTER TABLE exam ADD COLUMN org_id BIGINT NULL COMMENT '所属机构';
ALTER TABLE exam ADD INDEX idx_org (org_id);

ALTER TABLE sys_user ADD COLUMN org_id    BIGINT NULL COMMENT '所属机构（平台管理员为 NULL，可跨机构）';
ALTER TABLE sys_user ADD COLUMN campus_id BIGINT NULL COMMENT '所属校区（教师归属校区）';
ALTER TABLE sys_user ADD INDEX idx_org (org_id);

-- ========== 卷子来源扩展：诊断卷 ==========
-- source 已有 ADAPTIVE / TEACHER，新增 DIAGNOSIS（入学 / 章节诊断），
-- 交卷后走「覆盖式初始化掌握度」而不是 EMA 增量。字段本身是 VARCHAR，无需改结构。

-- node_content / recommend_rule / learning_task 由 schema.sql 建出（幂等）。

-- ========== 讲解 IR：node_content 从「只有 Markdown」升级为「IR + 渲染产物」============
-- storyboard 存分镜中间表示（JSON），body 退化为它的一个渲染后端，不再是唯一真源。
-- 一个知识点一份讲解 IR；教案（LESSON_PLAN）引用同一份 IR 的分段，不另起一套内容。
ALTER TABLE node_content ADD COLUMN storyboard MEDIUMTEXT NULL COMMENT '讲解分镜 IR（JSON）：分段 + 旁白 + 画面 + 视觉动作锚点';
ALTER TABLE node_content ADD COLUMN check_report TEXT        NULL COMMENT '内容自检报告（JSON）：不通过项 + 是否需人工复核';
ALTER TABLE node_content ADD COLUMN needs_review  TINYINT     NOT NULL DEFAULT 0 COMMENT '自检未通过需人工复核，阻断自动发布';

-- ========== 大模型接入配置（运行时可改，不必重启）============
-- org_id 为 NULL 是平台默认，机构可覆盖；解析顺序 机构行 > 平台行 > 环境变量/yaml。
-- api_key 明文存储是 Demo 期的取舍：换来运行期可换厂商。生产应改 KMS 加密或独立密钥管理服务。
-- 无论哪种，该字段都不出现在任何接口响应里（管理台只回掩码）。
CREATE TABLE IF NOT EXISTS llm_config (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id          BIGINT      NULL COMMENT '所属机构；NULL = 平台默认配置',
    preset_code     VARCHAR(64)  NOT NULL DEFAULT 'CUSTOM' COMMENT '预设编码，见 LlmPresetCatalog',
    vendor          VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '厂商名，预设回填',
    protocol        VARCHAR(32)  NOT NULL DEFAULT 'OPENAI' COMMENT 'OPENAI/ANTHROPIC/GEMINI/OLLAMA/VOLCENGINE',
    auth            VARCHAR(32)  NOT NULL DEFAULT 'BEARER' COMMENT 'BEARER/HEADER/QUERY_KEY/GOOG_HEADER/NONE/CUSTOM_HEADER',
    auth_header     VARCHAR(128) NULL     COMMENT 'CUSTOM_HEADER 时的 header 名',
    base_url        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '服务根地址，不含 chat-path',
    chat_path       VARCHAR(255) NOT NULL DEFAULT '/v1/chat/completions' COMMENT '端点路径；Gemini 保留 {model} 占位',
    api_key         VARCHAR(512) NULL     COMMENT '密钥明文；见表头注释说明取舍',
    model           VARCHAR(128) NOT NULL DEFAULT '' COMMENT '模型标识',
    timeout_seconds INT          NOT NULL DEFAULT 90,
    max_tokens      INT          NOT NULL DEFAULT 2400,
    temperature     DOUBLE       NOT NULL DEFAULT 0.3,
    enabled         TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用；停用后回落环境变量',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_org (org_id),
    KEY idx_enabled (enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '大模型接入配置（运行时可改）';

-- ========== Agent 生效范围下探到校区 / 班级 ==========
-- 四档范围：平台级（org_id=NULL）→ 机构级 → 校区级 → 班级级。
-- 这决定「在哪里启用这个 Agent」，不决定「能读哪些数据」——后者永远由 UserContext 实时算。
ALTER TABLE agent_profile ADD COLUMN campus_id BIGINT NULL COMMENT '生效校区；NULL 表示机构内全部校区';
ALTER TABLE agent_profile ADD COLUMN clazz_id  BIGINT NULL COMMENT '生效班级；NULL 表示校区/机构内全部班级';
ALTER TABLE agent_profile ADD INDEX idx_scope (org_id, campus_id, clazz_id);

-- ========== 组织管理层级：班级分层 ==========
-- 一层自引用，表达「平行班 / 实验班挂在常规班下」。不支持多深，见 ClazzReq#parentClazzId。
ALTER TABLE clazz ADD COLUMN parent_clazz_id BIGINT NULL COMMENT '上级班级（自引用，仅一层）';
ALTER TABLE clazz ADD INDEX idx_parent_clazz (parent_clazz_id);

-- ========== 掌握度置信度 + 学习记录流水 ==========
-- 1) student_node_state：把「掌握度是多少」升级成「掌握度是多少、有多可信、忘到哪儿了」。
--    practice_count 决定置信度：练 1 次得 0.9 和练 12 次均分 0.9 完全不同，不能同等推荐。
--    mastery_peak 给遗忘衰减一个下界锚点，避免衰减把掌握度一路压到 0 造成永久推荐。
ALTER TABLE student_node_state ADD COLUMN practice_count  INT           NOT NULL DEFAULT 0 COMMENT '累计有效练习次数（置信度分母）';
ALTER TABLE student_node_state ADD COLUMN last_score_rate DECIMAL(5, 4) NULL COMMENT '最近一次得分率';
ALTER TABLE student_node_state ADD COLUMN mastery_peak    DECIMAL(5, 4) NOT NULL DEFAULT 0 COMMENT '历史最高掌握度';

-- 2) learning_task 记「该做什么」，learning_record 记「实际做了什么 + 掌握度怎么变的」。
--    只追加、不覆盖：推荐算法的近期重复惩罚 / 遗忘间隔 / 置信度三个因子都依赖它，
--    所以它不能像任务那样被「按最新掌握度重排」冲掉。
CREATE TABLE IF NOT EXISTS learning_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id          BIGINT       NOT NULL COMMENT '所属机构（租户）',
    student_id      BIGINT       NOT NULL COMMENT '学生',
    node_id         BIGINT       NULL COMMENT '知识点；无知识点级事件（如入学诊断）为空',
    action          VARCHAR(24)  NOT NULL COMMENT 'VIEW 学讲解 / PRACTICE 做题 / DIAGNOSIS 诊断 / REVIEW 复习 / TASK_DONE 完成任务 / ADJUST 教师改分',
    ref_type        VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '关联对象类型：TASK/EXAM/CONTENT/ANSWER',
    ref_id          BIGINT       NULL COMMENT '关联对象 id',
    task_id         BIGINT       NULL COMMENT '触发本次行为的学习任务（回溯推荐→执行闭环）',
    score_rate      DECIMAL(5, 4) NULL COMMENT '本次得分率 0-1',
    duration_sec    INT          NOT NULL DEFAULT 0 COMMENT '本次投入时长（秒）',
    question_count  INT          NOT NULL DEFAULT 0 COMMENT '本次涉及题目数',
    mastery_before  DECIMAL(5, 4) NULL COMMENT '行为发生前掌握度',
    mastery_after   DECIMAL(5, 4) NULL COMMENT '行为发生后掌握度',
    note            VARCHAR(255) NOT NULL DEFAULT '' COMMENT '备注',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_student (student_id, created_at),
    KEY idx_student_node (student_id, node_id),
    KEY idx_org (org_id),
    KEY idx_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '知识点学习记录流水（只追加）';

-- 3) 推荐规则从「一条公式」升级为「策略 + 多因子」，strategy 决定因子的组合方式。
ALTER TABLE recommend_rule ADD COLUMN strategy VARCHAR(24) NOT NULL DEFAULT 'BALANCED'
    COMMENT '推荐策略：REMEDIATION 补弱优先 / SEQUENTIAL 顺序推进 / EXAM 应试优先 / BALANCED 均衡';
