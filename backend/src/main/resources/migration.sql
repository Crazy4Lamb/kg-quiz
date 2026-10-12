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
