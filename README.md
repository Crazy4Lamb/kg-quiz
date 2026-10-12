# 知识图谱题库管理系统（Demo）

> 独立新建项目，不改动现有 yt-* 服务。分角色登录（管理员 / 教师 / 学生），前后端双重权限控制。

## 一、功能

| 模块 | 能力 |
|---|---|
| **角色与权限** | 管理员 / 教师 / 学生三种角色登录。菜单与路由按角色分发；后端对每个接口做角色校验，并额外校验学生只能访问自己的数据，越权返回 403 |
| 知识图谱管理 | 按 学科 × 教材 × 年级 × 学期 管理多套图谱；可视化画布编辑节点与关系（包含 / 相近 / 前置），后置关系由前置反向查询 |
| 图谱模板复制 | 以某套图谱为蓝本，原样复制全部节点（含坐标/权重/编码）与关系到新维度，用于"新版教材基于旧版改" |
| 批量导入 | 知识点：粘贴缩进大纲自动建树（含「包含」关系）；题目：从 Excel 粘贴 TSV，按知识点**名称**自动匹配 |
| 图谱检索 | 节点 ID 精确查询；关键词模糊搜索（SQL LIKE 字面匹配 + Qdrant 语义召回合并） |
| 图谱复制（学生） | 新建学生选教材后复制对应图谱为该生个人图谱（含节点权重，掌握度初始 0），同时开登录账号 |
| 题库 | 题目关联知识点（多对多）、题型、题干、选项、答案、解析、分值、难度、科目、教材 |
| 自适应出题 | 按掌握度生成出题方案（薄弱优先 + 巩固配额 + 前置约束 + 难度自适应），确认后生成练习并自动指派给该学生，答题回写掌握度形成闭环 |
| **组卷与定向发布** | 教师从题库挑题组卷，勾选学生定向发送。只有被发送的学生在「我的作业」里能看到并作答，其他学生列表里看不到、直接访问接口也 403 |
| **自动批改** | 学生交卷即刻判分：单选/判断/多选/填空按答案精确匹配；简答按**要点覆盖率**给参考分并挂起待复核。含主观题的作业状态为"待批改"，全客观题直接定稿 |
| **教师复核批改** | 批改台逐题对照「学生作答 / 参考答案」改分并写评语，支持「全部采用机器分」一键采纳；定稿后学生才看到分数、评语与参考答案，掌握度按修正后的得分率更新 |
| **教师管理**（管理员） | 跨教师汇总视图：每人出了多少卷、发送多少人次、积压多少待批改、批改进度，并可启停账号/重置密码 |
| 学情报告 | 掌握度分布（已掌握/掌握中/薄弱/未练习）、加权掌握度、正确率、历次练习趋势、补强建议（自动提示未掌握的前置） |
| 错题本 | 按题目聚合答错记录：错因、错次、最近得分、正确答案与解析 |
| 人工干预 | 老师可修正或重置某学生在某知识点上的掌握度（只改掌握度，不删历史答题记录） |
| 图分析 | ① 学习路径推荐（前置闭包 + 拓扑分层）② 前置缺口分析（递归找未掌握前置）③ 核心知识点排序（PageRank，支持按权重个性化） |

## 二、技术栈

- **后端**：Spring Boot 3.4.5 / JDK 17 / MyBatis-Plus 3.5.9 / MySQL 8
- **鉴权**：自研轻量方案 —— 登录签发 UUID 令牌落库（24h 过期），`HandlerInterceptor` 校验令牌并解析角色，`@RequireRole` 注解声明接口角色。**不引入 Spring Security**；口令用 JDK 自带 PBKDF2-HMAC-SHA256 加盐散列
- **检索**：Qdrant 1.18（向量库，独立 collection `kg_nodes`）+ ONNX Runtime 1.16.3 + bge-small-zh-v1.5（本地中文 embedding，512 维，CLS pooling + L2 归一化）
- **前端**：React 18 + Vite 5 + Ant Design 5 + React Flow 12
- **图算法**：应用层内存实现（图谱规模几十至几百节点，无需图数据库）

### 为什么知识图谱用 MySQL 而不是 Neo4j

本系统的图查询最深只有 **1 跳**（出题时的前置约束），其余是属性过滤与全量拉取；图数据库的强项（变长路径、图算法）改为在**应用层**实现，图谱一次加载进内存计算即可。因此保持 `graph_node` + `node_relation` 两张关系表，不引入额外中间件与双写一致性问题。若后续图谱上到万级节点且频繁多跳查询，可引入 Neo4j（`GraphService` 已收敛全部图操作，便于替换）。

### 为什么鉴权不用 Spring Security

Demo 阶段需要的是"登录 + 角色边界"，而不是完整的认证授权栈。自研方案的取舍：

- 令牌落库可**即时失效**（改密、停用、删除账号时直接删 `sys_token` 记录），比无状态 JWT 更好做"踢下线"
- 拦截器 + 注解的表达力对三种角色足够，且逻辑全都看得见、好调试
- 代价是没有现成的会话并发控制、CSRF 防护、OAuth2 接入等能力；真要上生产，只需把 `AuthService.resolve()` 与 `AuthInterceptor` 换成 Spring Security 的过滤器链，业务代码里的 `UserContext` / `@RequireRole` 可以原样保留

## 三、数据模型

```
graph               图谱主表（学科/教材/年级/学期唯一）
graph_node          知识点节点（weight=重要性权重，pos_x/pos_y=画布坐标）
node_relation       节点关系 CONTAINS / SIMILAR / PREREQUISITE
student             学生
student_graph       学生图谱副本（引用模板节点 + 独立状态）
student_node_state  节点掌握度状态（mastery, last_practiced_at）
question            题目（题型/题干/选项/答案/分值/难度/科目/教材）
question_node       题目↔知识点 多对多
exam                卷子：自适应练习（student_id 非空）与教师组卷（student_id 为空，有 name/created_by）
exam_question       卷子↔题目（含该题在本卷归属的知识点）
exam_assignment     作业指派：exam_id + student_id（唯一），状态 ASSIGNED / SUBMITTED / GRADED，含得分与待批改题数
answer_record       答题记录（学生作答原文 student_answer、机器判分 auto_score、最终得分 score、graded_by=AUTO/MANUAL、教师评语）
sys_user            系统账号（username / password(PBKDF2) / role / display_name / student_id / enabled）
sys_token           登录会话令牌（token / user_id / expire_at）
```

Schema 由 `spring.sql.init` 在启动时依次执行 `schema.sql`（建表，幂等）与 `migration.sql`（给已存在的表补列，`continue-on-error` 容忍重复执行）。
数据库 `yt_knowledge_demo` 通过 JDBC URL 的 `createDatabaseIfNotExist=true` 自动创建。

## 四、启动

### 1. 数据库

默认连接**本机 MySQL**（`127.0.0.1:3306`，账号 `root` / 密码 `root`）；需要切到内网 dev 库时用 `dev` profile。可用环境变量覆盖：

```
MYSQL_HOST=127.0.0.1      # 内网 dev 库改 192.168.0.69
MYSQL_PORT=3306           # 内网 dev 库改 23306
MYSQL_USER=root
MYSQL_PASSWORD=root
QDRANT_URL=http://192.168.0.69:6333
```

库 `yt_knowledge_demo` 与全部表在启动时自动创建（JDBC URL 的 `createDatabaseIfNotExist=true` + `schema.sql` 幂等执行），无需手动建库建表。

### 2. 启动后端

双击 `start-backend.bat`（默认本机库）；`start-backend.bat dev` 则切内网 dev 库。

等价的命令行方式：

```bash
cd kg-quiz/backend
JAVA_HOME="C:/Users/Mayn/.jdks/ms-17.0.17" \
MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER=root MYSQL_PASSWORD=root \
KG_EMBEDDING_MODEL="$PWD/../models/bge-small-zh-v1.5.onnx" \
KG_EMBEDDING_TOKENIZER="$PWD/../models/tokenizer.json" \
"/c/Users/Mayn/.m2/wrapper/dists/apache-maven-3.9.10/a38810a491b03367137adfdfbe7d14c4/bin/mvn" spring-boot:run
```

服务端口 **8090**。注意两点：后端工作目录是 `backend/`，所以模型默认路径写成 `../models/...`（启动脚本会覆盖为绝对路径）；端口已在 `pom.xml` 的 `spring-boot-maven-plugin` 中固定（`--server.port=8090` + `-Dserver.port=8090`），因为宿主的沙盒环境会向子进程注入 `SERVER_PORT`，只用 yml 配置会被悄悄改掉。

### 3. 启动前端

```bash
cd kg-quiz/frontend
npm install --ignore-scripts
npm run dev
```

访问 http://localhost:5173 （已配置 `/api` 代理到 8090）。或运行 `start-frontend.bat`。

### 4. 登录

首次启动会自动创建默认账号（已存在则跳过），并给没有账号的学生补建 `stu{学生id}`：

| 角色 | 账号 | 密码 | 登录后能看到 |
|---|---|---|---|
| 管理员 | `admin` | `admin123` | 全部功能 + 系统运维（账号管理、示例数据、向量索引） |
| 教师 | `teacher` | `teacher123` | 图谱管理、图谱检索、学生管理、题库管理、出题中心 |
| 学生 | `stu1` | `123456` | 我的学习（自己的图谱 + 学情报告 + 错题本）、我的作业（老师发的卷子 + 作答）、图谱检索 |

登录页提供「填入管理员/教师/学生账号」快捷按钮，方便切换角色对比差异。

### 5. 导入示例数据

用管理员登录后打开「图谱管理」，空库时会显示「导入示例数据」按钮：一键载入**小学数学五年级上（人教版）**——13 个知识点、18 条关系、28 道题、1 名学生（含初始掌握度），并自动建立向量索引。

## 五、角色与权限

### 权限矩阵

| 能力 | 管理员 | 教师 | 学生 |
|---|:---:|:---:|:---:|
| 登录 / 登出 / 查看自己的身份 / 改自己的密码 | ✓ | ✓ | ✓ |
| 图谱增删改（含画布编辑、批量导入、模板复制） | ✓ | ✓ | — |
| 题库增删改 | ✓ | ✓ | — |
| 学生名册、新建学生（开户）、删除学生、修正掌握度 | ✓ | ✓ | — |
| 生成自适应出题方案 / 确认（含自动指派） | ✓ | ✓ | — |
| 组卷、定向发送、撤销发送、批改、退回重做 | ✓ | ✓（仅自己的卷子） | — |
| 我的作业、作答、查看自己的答卷与批改结果 | — | — | 仅本人 |
| 掌握度总览 | ✓ | ✓ | 仅本人 |
| 学情报告 / 错题本 / 学生图谱视图 | ✓ | ✓ | 仅本人 |
| 知识点检索 | ✓ | ✓ | ✓ |
| 图分析（路径推荐 / 缺口 / PageRank） | ✓ | ✓ | 路径与缺口仅本人 |
| 账号管理（建号 / 重置密码 / 启停 / 删除） | ✓ | — | — |
| 教师管理（跨教师工作量与批改进度） | ✓ | — | — |
| 系统运维（导入示例数据 / 重建向量索引） | ✓ | — | — |

### 权限怎么落地

**前端**只负责"不给入口"：菜单按角色过滤，路由按角色分发（学生手输 `/graphs`、`/questions`、`/students`、`/admin` 都会被挡回 `/my`）。前端挡住的只是体验，**真正的边界在后端**。

**后端**三层校验，缺一不成：

1. `AuthInterceptor` —— 解析 `Authorization: Bearer <token>`，令牌不存在 / 过期 / 账号停用一律 401，并把 `CurrentUser` 放进 `UserContext`（ThreadLocal，`afterCompletion` 清理，避免线程复用串身份）
2. `@RequireRole` —— 类级或方法级声明可访问角色，方法级优先；不符返回 403。控制器上的分布：
   - `GraphController` / `QuestionController`：`TEACHER, ADMIN`
   - `StudentController`：类级 `TEACHER, ADMIN`，但 `graph` / `report` / `wrong-questions` 三个方法级放开 `STUDENT`
   - `SearchController` / `AnalysisController`：三角色
   - `ExamController`：类级三角色，方法级再收窄 —— 自适应出题与组卷批改是 `TEACHER, ADMIN`；`/my/**` 是 `STUDENT` 且 studentId 一律取自会话，接口不接受传入
   - `AdminController` / 账号管理接口：`ADMIN`
3. **数据归属校验** —— 角色对了不代表数据对了。凡带 `studentId` 的接口都过一遍 `UserContext.checkStudentScope()`：学生传了别人的 id 直接 403（不静默改写，免得掩盖越权尝试），不带参数时 `scopeStudentId()` 收敛到本人

### 登录与口令

- 口令用 **PBKDF2-HMAC-SHA256**（12000 次迭代、16 字节随机盐、256 位输出）存储，格式 `pbkdf2$迭代$盐$哈希`，校验走 `MessageDigest.isEqual` 常量时间比较
- 登录失败时"账号不存在"与"密码错误"返回同一提示，不暴露账号是否注册
- 令牌有效期 24 小时，登录时顺带清理该用户已过期的令牌
- **改密 / 重置密码 / 停用 / 删除账号都会立即删掉该用户的全部令牌**，即所有设备当场掉线
- 系统始终保留至少一个启用状态的管理员，防止把系统锁死；也不能删除当前正在使用的账号

### 学生账号从哪来

推荐在「学生管理 → 新建学生」时一并开户：填了登录名就用，不填自动生成 `stu{学生id}`，初始密码 `123456`。开户口与复制图谱在**同一事务**内提交——登录名重复等问题会导致整体回滚，不会留下"有学生档案却没有账号"的孤儿数据。删除学生时账号与令牌级联清理。

管理员也可以在「系统运维 → 账号管理」补建教师/管理员账号，或重置任何账号的密码。

## 六、核心算法

### 掌握度（EMA 指数移动平均）

```
mastery_new = mastery_old × (1 - α) + α × 得分率
α = 0.3（kg.exam.learning-rate）
```

按知识点聚合本次练习的平均得分率后更新；知识点掌握度 ∈ [0, 1]。

### 出题策略

1. 取学生图谱全部节点掌握度与权重
2. 薄弱节点（mastery < 0.4）按 `优先级 = (1-掌握度)×0.7 + 归一化权重×0.3` 排序
3. **前置约束**：薄弱节点的前置若也薄弱，前置节点被提升进优先池（理由标注"作为前置知识被优先安排"）
4. 配额：80% 补弱 + 20% 巩固（已掌握节点随机抽取，防遗忘）
5. 难度自适应：薄弱出易题（目标难度 2）、掌握中 3、巩固 4
6. 输出结构化方案（每个知识点的选题、理由、掌握度快照），确认后生成练习

### 出题与批改的两条路径

| 路径 | 出题方式 | 谁来批 | 适用 |
|---|---|---|---|
| **自适应练习** | 引擎按该生掌握度自动挑题，确认方案时自动指派给该生 | 机器（全客观题直接定稿） | 日常补弱、个性化练习 |
| **教师组卷** | 老师从题库挑题组卷 → 勾选学生定向发送 | 机器初判 + 老师复核主观题 | 单元测、统考、统一作业 |

两条路径共用 `exam` / `exam_question` / `exam_assignment` / `answer_record`，用 `exam.source` 区分；学生端的「我的作业」是同一个列表，来源标签会写明是"老师布置"还是"系统生成"。

**可见性由指派决定**：`exam_assignment` 里没有你的记录，这份卷子就不存在 —— 列表里不出现，直接按 id 访问接口返回 403。学生取题时后端**不返回 `answer` / `analysis`**，只有批改定稿后才放开，避免把答案发给正在做题的学生。

### 自动判分规则

学生交卷后 `AutoGrader` 立刻逐题判分，不必等老师动手：

| 题型 | 判分方式 | 是否需教师复核 |
|---|---|---|
| 单选 / 判断 | 与答案精确比对（忽略空白、全角半角、大小写） | 否 |
| 多选 | 选项集合完全相等才给满分，多选/漏选不得分 | 否 |
| 填空 | 支持用 `\|` 分隔多个可接受写法，命中任一即满分 | 否 |
| 简答 | **要点覆盖率**：参考答案去标点后取相邻二字组合，统计有多少出现在学生作答中，按比例折算（保留 1 位小数） | 是（恒挂起） |

判分结果写入 `answer_record.auto_score`，`score` 初始等于机器分；教师改分后 `score` 变化、`graded_by` 记为 `MANUAL`。

作业状态流转：`ASSIGNED`（待作答）→ 交卷后全客观题直接 `GRADED`，含简答题则 `SUBMITTED`（待批改）→ 教师提交批改后 `GRADED`。

### 掌握度与改分

- **交卷时**按机器分先更新一次掌握度（EMA），学生马上能看到反馈
- **教师改分后**用差值修正：`mastery += α × (新得分率 − 旧得分率)`。不重放全部答题记录，是因为初始掌握度可能来自系统外（导入的历史学情），重放会把它抹掉；EMA 是线性的，增量修正等价于替换该次得分率后重算

### 图分析

- **学习路径推荐**：目标节点的前置传递闭包 → 剔除已掌握（≥0.7）→ Kahn 拓扑分层（层内可并行）→ 输出学习顺序
- **前置缺口分析**：前置闭包 → 筛出 mastery < 0.4 的节点 → 按层级从下往上分组（下层是上层基础）
- **PageRank**：沿"依赖方向"传播权重（B 依赖 A ⇒ B 向 A 传递），阻尼 0.85，40 次幂迭代；可选 `weightBias` 用节点 weight 作个性化向量。环安全（拓扑排序兜底、PR 迭代天然收敛）

### 图谱编辑器：知识点新增方式

知识点一多，"先建节点、再单独连线"就会变得繁琐。编辑器因此提供两种新增方式：

| 方式 | 入口 | 说明 |
|---|---|---|
| **关联新增** | ① 单击选中节点 → 工具栏「关联新增：<节点名>」<br>② 在节点上**点右键** → 「关联新增知识点」<br>③ 双击节点打开属性抽屉 → 「基于该知识点关联新增」 | 新增节点时直接指定与锚点节点的关系：**前置 / 后置 / 包含 / 被包含 / 相近 / 不关联**，一步完成建点 + 建边 |
| 自由新增 | 工具栏「自由新增」 | 只建节点，关系稍后用连线方式补 |

配套细节：

- 新节点自动落位在锚点节点的对应方位（**前置在上、后置在下、包含在右下、被包含在左上、相近在右**）并带小幅抖动，避免连续新增时重叠
- 添加后**锚点保持选中**，可基于同一锚点连续挂载多个知识点
- 关系方向由后端 `link` 参数统一表达（见 API 一节），前端只按"新节点 ↔ 锚点"的语义选择，不需要手工区分 from/to
- 画布连线补充了箭头（`markerEnd`），方向的语义一眼可辨

### 批量导入格式

**知识点**（图谱编辑器 → 批量导入）：直接粘贴大纲，用**缩进**表达层级，后端自动生成「包含」关系。

```
第3单元 小数除法
  除数是整数的小数除法
  除数是小数的小数除法
    商的近似数
    循环小数
```

- 缩进用 2 空格 / 4 空格 / Tab 都可以，按实际缩进宽度排序推断层级
- 行首的 `1.`、`一、`、`-` 等编号会自动忽略
- 行内可用竖线追加字段：`名称|描述|权重`
- 同名知识点自动跳过，并在结果里列出跳过原因（不覆盖已有内容）
- 可选「顶层挂到」某个已存在节点，用于往某个章节里批量补知识点

**题目**（题库管理 → 批量导入）：从 Excel 复制粘贴，每行一道题，用 `|` 分列，末尾列可省略。

```
题型|题干|知识点|选项|答案|解析|分值|难度
单选|3.6÷0.4 的结果是多少？|除数是小数的除法|9;0.9;90;0.09|9|被除数和除数同时扩大 10 倍|5|2
```

- 题型可写中文（单选/多选/判断/填空/简答）或枚举值
- 知识点、选项多个用 `;` 分隔；分值默认 5、难度默认 3
- 知识点按**名称**在所选图谱内匹配，科目/教材由表单统一指定
- **先全量校验，有错整批不落库**，并把"第几行、什么原因"逐条返回，避免一半进一半不留

## 七、API 一览

```
认证      POST /api/auth/login               登录（唯一免令牌接口）
          POST /api/auth/logout              登出（作废当前令牌）
          GET  /api/auth/me                  当前登录者身份
          PUT  /api/auth/me/password         修改自己的密码
账号      GET/POST /api/auth/accounts                        [管理员] 账号列表 / 建号
          PUT  /api/auth/accounts/{id}/password              [管理员] 重置密码
          PUT  /api/auth/accounts/{id}/enabled?enabled=       [管理员] 启用/停用
          DELETE /api/auth/accounts/{id}                     [管理员] 删除账号
图谱      GET/POST /api/graphs            GET/PUT/DELETE /api/graphs/{id}
          POST /api/graphs/{id}/copy                复制图谱模板（节点+关系）
节点      POST /api/graphs/{id}/nodes     PUT/DELETE /api/graphs/{id}/nodes/{nodeId}
          POST /api/graphs/{id}/nodes/batch         批量导入（缩进层级→CONTAINS 树）
          PUT  /api/graphs/{id}/nodes/positions     批量保存画布坐标
关系      POST /api/graphs/{id}/relations
          PUT/DELETE /api/graphs/{id}/relations/{relationId}   编辑 / 删除关系
检索      GET /api/search/nodes?keyword=&nodeId=&graphId=&mode=all|like|semantic
学生      GET/POST /api/students          GET /api/students/{id}/graph  DELETE /api/students/{id}
          GET /api/students/{id}/report             学情报告
          GET /api/students/{id}/wrong-questions    错题本
          PUT /api/students/{id}/mastery            人工修正/重置掌握度
          PUT /api/students/{id}/password           重置学生登录密码
题库      GET/POST /api/questions         GET/PUT/DELETE /api/questions/{id}
          POST /api/questions/batch                 批量导入题目
出题      POST /api/exams/plans           自适应出题方案（PENDING）
          POST /api/exams/{id}/confirm    确认方案 → 生成练习并指派给该学生
          GET  /api/exams                 自适应练习列表
          GET  /api/exams/mastery?studentId=   掌握度总览
组卷      GET/POST /api/exams/papers                      卷子列表 / 组卷
          GET/DELETE /api/exams/papers/{id}               卷子详情 / 删除
          POST   /api/exams/papers/{id}/assign                       发送给指定学生
          DELETE /api/exams/papers/{id}/assign/{studentId}           撤销发送
          GET  /api/exams/papers/{id}/submissions                    交卷情况
          GET  /api/exams/papers/{id}/submissions/{studentId}        单个学生答卷明细（批改台）
          POST /api/exams/papers/{id}/submissions/{studentId}/grade  批改定稿
          POST /api/exams/papers/{id}/submissions/{studentId}/reset  退回重做
          GET  /api/exams/teacher-workload                           [管理员] 各教师工作量
作业(学生) GET  /api/exams/my                             我的作业
          GET  /api/exams/my/{id}                        取题作答 / 复盘（定稿前不含答案）
          POST /api/exams/my/{id}/submit                 交卷 → 自动批改
图分析    GET /api/analysis/graphs/{graphId}/pagerank?weightBias=&topN=
          GET /api/analysis/students/{studentId}/path?targetNodeId=
          GET /api/analysis/students/{studentId}/gap?nodeId=
管理      POST /api/admin/seed-demo              导入示例数据（仅空库）
          POST /api/admin/vector-index/rebuild   重建向量索引
          POST /api/admin/vector-index/status    查询向量索引状态
```

### 请求与响应约定

- 除 `/api/auth/login` 外，所有 `/api/**` 都需要 `Authorization: Bearer <token>`（也兼容 `X-Token` 头）
- 统一响应体：`{ code: 0, message: "ok", data: ... }`
- **HTTP 状态码与业务码一致**，前端 axios 才能在 error 分支取到提示：
  - `401` 未登录 / 令牌失效（前端自动清会话并跳登录页）
  - `403` 角色不足，或学生访问他人数据
  - `400` 业务校验失败（如"该关系已存在"）与参数校验失败，`message` 形如 `"mastery: 必须小于或等于1.0"`
  - `500` 系统异常
- 账号相关响应**从不返回密码字段**

### 新建节点（支持同时建立关联）

`POST /api/graphs/{id}/nodes` 的请求体在节点字段之外，支持一个**可选的 `link`**：创建节点的同时与某个已有节点（锚点）建立一条关系，两者在**同一事务**内提交，任一步失败则一起回滚（不会残留孤立节点或悬空关系）。

```jsonc
{
  "name": "循环小数的写法",
  "description": "……",
  "code": "MATH-5A-13",
  "weight": 1,
  "posX": 540, "posY": 620,
  "sortOrder": 0,
  "link": {                       // 可选，不传即普通自由新建
    "anchorNodeId": 8,            // 锚点节点 id
    "relationType": "PREREQUISITE", // CONTAINS / SIMILAR / PREREQUISITE
    "anchorIsSource": true        // true=锚点→新节点；false=新节点→锚点；缺省 true
  }
}
```

`anchorIsSource` 让前端不必拼装两个方向的语义：例如"新知识点是锚点的后置"= `PREREQUISITE + anchorIsSource=true`，"新知识点是锚点的前置"= `PREREQUISITE + anchorIsSource=false`。

### 新建学生（同时开户）

```jsonc
{
  "name": "小明",
  "subject": "数学", "textbook": "人教版", "grade": "五年级", "term": "上学期",
  "username": "xiaoming",   // 可选，留空自动生成 stu{学生id}
  "password": "123456"      // 可选，留空用默认初始密码
}
```

响应会带回 `username` 与 `initPassword`，前端直接展示给老师转告学生。

## 八、页面

**管理员**（7 个菜单）

1. 图谱管理（列表 + 新建 + **复制** + 导入示例数据 + 重建索引）
2. 图谱编辑器（React Flow 画布：拖拽布局、双击节点编辑、连线建关系、**点击连线改关系类型/换方向/删除**、**批量导入知识点**、核心知识点排序、关联新增知识点、编辑图谱信息）
3. 图谱检索（ID / 关键词 / 语义三路合并，标注来源与相似度）
4. 学生管理（新建学生 → 选教材复制图谱 + 开户、**重置学生密码**）
5. 题库管理（录入/编辑，题型选项动态表单，关联知识点多选，**批量导入**）
6. 出题中心 —— 两个 Tab：
   - **自适应出题**：选学生 → 生成方案 → 预览理由 → 确认（自动指派）→ 历史记录 → 掌握度
   - **组卷与批改**：从题库挑题组卷 → 弹窗勾选学生定向发送 → 卷子列表看"发送人数 / 已交 / 已批"进度
7. 批改台（`/exams/grading/{examId}`）：已交卷 / 待批改 / 已定稿统计，左侧交卷名单，右侧逐题对照「学生作答 ⇄ 参考答案/解析」改分写评语，支持「全部采用机器分」「退回重做」「提交批改并定稿」
8. 系统运维（账号管理 / **教师管理** / 导入示例数据 / 重建向量索引）

**教师**：同上，唯「系统运维」不可见；教师只能管理自己出的卷子（管理员可跨教师）。

**学生**（3 个菜单）

1. 我的学习 —— 自己的图谱（掌握度热力着色 + 学习分析与路径推荐 + 学情报告 + 错题本）
2. 我的作业 —— 老师发的卷子与系统按掌握度生成的练习，标记待作答 / 待老师批改 / 已批改；点「去作答」只填答案（**页面上看不到参考答案**），交卷后机器立即判分，老师定稿后这里显示出分数、评语与参考答案
3. 图谱检索 —— 浏览知识点（可看不可改）

右上角头像下拉可修改密码或退出登录。

## 九、降级说明

- **Qdrant 不可达 / embedding 模型缺失**：应用正常启动，语义检索自动降级为 LIKE 字面匹配，侧边栏会提示"语义检索不可用"
- **embedding 模型**：`models/bge-small-zh-v1.5.onnx`（94MB，来自 `Xenova/bge-small-zh-v1.5`）+ `models/tokenizer.json`，路径可用 `KG_EMBEDDING_MODEL` / `KG_EMBEDDING_TOKENIZER` 覆盖
