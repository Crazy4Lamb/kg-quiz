# 数学知识图谱教学与自适应练习平台 / Mathematics Knowledge Graph and Adaptive Practice Platform

## 中文介绍

面向管理员、教师和学生的教学 Demo：把知识点关系、题库、练习和学情反馈连接起来，支持教师组织教学与学生针对薄弱知识点练习。示例内容为小学数学。

## English Introduction

A teaching demo for administrators, teachers and students. It connects knowledge relationships, questions, assignments and learning feedback to support targeted practice. The sample content covers primary-school mathematics. The application UI is currently Chinese; these repository guides are bilingual.

## 使用说明 / User Guides

- [中文说明](README.zh-CN.md)
- [English guide](README.en.md)

下方提供完整的中文和英文说明与实际界面截图。 / Full guides and actual screenshots follow.

---

# 中文说明

## 功能

- **知识图谱**：按学科、教材、年级、学期管理；画布编辑知识点及包含、相近、前置关系，支持模板复制和大纲导入。
- **题库与检索**：题目关联知识点，支持批量导入、精确与关键词检索，以及本地 embedding 驱动的语义检索。
- **教学闭环**：学生个人图谱、掌握度追踪、自适应出题、组卷与定向发布、学生答题、自动评分及教师复核。
- **学习分析**：学情报告、错题本、前置缺口分析、学习路径推荐与核心知识点排序。
- **角色权限**：管理员管理账号及系统，教师管理教学，学生访问自己的学习数据；后端校验角色与数据归属。
- **可选 AI**：通过兼容接口辅助内容生成与题目识别。
- **多主题界面**：右上角可切换活力多巴胺、蜜桃派对、薄荷花园、晴空蓝与霓虹夜色；选择自动保存在当前浏览器，登录页、工作台、弹窗与图谱同步换色。

## 当前架构

| 部分 | 技术与职责 |
|---|---|
| 前端 | React 18、Vite 5、Ant Design 5、React Flow 12 |
| 后端 | Java 17、Spring Boot 3.4.5、MyBatis-Plus |
| MySQL 8 | 用户、学生、题目、练习、作业、答题与掌握度等业务数据 |
| Neo4j | 图谱、知识点及关系；Cypher 操作集中在 GraphStore |
| Qdrant | 知识点向量检索 |
| ONNX Runtime | 本地 bge-small-zh-v1.5 中文 embedding，512 维 |

Neo4j 是当前图存储，MySQL 是业务存储。Qdrant、embedding 与大模型能力分别由环境变量控制。

## 快速开始

准备 Java 17、Maven、Node.js/npm、MySQL 8 和可用的 Neo4j 服务；需要语义检索时准备 Qdrant。数据库与服务数据不包含在仓库中。

```sh
git clone https://github.com/Crazy4Lamb/kg-quiz.git
cd kg-quiz
```

### 1. 配置服务

以下为 PowerShell 示例，将密码改成你的实际配置：

```powershell
$env:MYSQL_HOST = "127.0.0.1"
$env:MYSQL_PORT = "3306"
$env:MYSQL_USER = "root"
$env:MYSQL_PASSWORD = "your-mysql-password"
$env:NEO4J_URI = "bolt://127.0.0.1:7687"
$env:NEO4J_USER = "neo4j"
$env:NEO4J_PASSWORD = "your-neo4j-password"
$env:QDRANT_URL = "http://127.0.0.1:6333"
```

MySQL 连接配置默认创建 yt_knowledge_demo 数据库，并执行 schema.sql 与 migration.sql；所用账号需要相应权限。Neo4j 服务与认证需提前配置。

### 2. 启动后端

在上面的终端中：

```powershell
cd backend
mvn spring-boot:run
```

后端端口为 **8090**。从 backend 目录运行时，模型默认从 ../models/ 加载。

### 3. 启动前端

另开终端，在项目根目录执行：

```sh
cd frontend
npm ci --ignore-scripts
npm run dev
```

打开 http://localhost:5173。Vite 将 /api 请求代理到 http://localhost:8090。

Windows 可使用 start-backend.bat 与 start-frontend.bat，但后端脚本的 JAVA_HOME 和 Maven 路径目前为原开发电脑路径，换电脑时需调整，或使用上述命令。

### 4. 体验教学流程

首次启动的演示账号：管理员 admin / admin123，教师 teacher / teacher123。演示学生 stu1 / 123456 需要相应学生数据已创建。

管理员导入示例数据后，可查看图谱、学生和题目；教师组卷并发布，学生作答，教师复核并查看掌握度变化。共享部署前应修改演示密码。

## 模型文件

仓库已包含 models/bge-small-zh-v1.5.onnx（94,851,877 字节，约 90.46 MiB）与 models/tokenizer.json，普通 git clone 会下载完整模型，无需 Git LFS 或另行下载。

原开发说明标注模型来自 Xenova/bge-small-zh-v1.5。可通过 KG_EMBEDDING_MODEL、KG_EMBEDDING_TOKENIZER 覆盖路径；替换模型时需匹配 tokenizer 和 embedding 维度。

## 常用配置

| 环境变量 | 用途 |
|---|---|
| MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD | MySQL 连接 |
| NEO4J_URI / NEO4J_USER / NEO4J_PASSWORD / NEO4J_DATABASE | Neo4j 连接 |
| QDRANT_URL / QDRANT_ENABLED | 向量服务地址与开关 |
| KG_EMBEDDING_ENABLED / KG_EMBEDDING_MODEL / KG_EMBEDDING_TOKENIZER | 本地模型开关与路径 |
| LLM_ENABLED / LLM_BASE_URL / LLM_API_KEY / LLM_MODEL | 可选大模型能力 |

配置默认值见 backend/src/main/resources/application.yml。dev profile 包含原开发环境的内网服务地址，新环境请覆盖。密钥使用环境变量，不写入 Git。

Qdrant 不可达或模型无法加载时，语义检索可降级为关键词匹配；这不代表 MySQL 或 Neo4j 可以省略。

## 目录与开发

```text
backend/       Java API、权限、业务逻辑与数据库脚本
frontend/      React 网页
models/        ONNX 模型与 tokenizer
docs/          开发说明与检查结果
start-*.bat    原开发电脑的 Windows 启动脚本
```

前端构建：在 frontend 目录执行 npm run build。后端检查：在 backend 目录执行 mvn test。根目录的验证脚本可能依赖运行中的服务、演示数据或原电脑路径，运行前请阅读脚本。

[历史开发说明](docs/legacy-demo-guide.zh-CN.md)保留详细流程与接口示例，其中旧架构描述不应作为当前部署依据。

## 图解使用说明

### 1. 登录与选择主题

![登录与主题入口](docs/images/login.png)

使用管理员、教师或老师分配的学生账号登录。右上角的主题按钮可选择活力多巴胺、蜜桃派对、薄荷花园、晴空蓝、霓虹夜色，选择保存在当前浏览器，登录页和工作台同步换色。

### 2. 管理知识图谱

![图谱管理工作台](docs/images/graphs.png)

管理员或教师在「图谱管理」中按教材与年级选择图谱，点击「编辑图谱」。空库可通过「导入示例数据」体验；已有教学数据时可直接使用现有图谱。

![知识点与关系编辑](docs/images/editor.png)

- 拖动知识点调整位置，点击「保存布局」保存。
- 双击节点编辑属性；从节点连接点拖线建立关系。
- 点击连线调整包含、相近或前置关系及方向。
- 选中节点后用「关联新增」补充知识点；「批量导入」可粘贴缩进大纲。

### 3. 完成一次教学闭环

| 角色 | 操作 | 结果 |
|---|---|---|
| 管理员 / 教师 | 新建学生，选择教材并创建账号 | 生成学生个人知识图谱 |
| 教师 | 维护题库并关联知识点 | 为练习与组卷准备题目 |
| 教师 | 在出题中心自适应出题，或组卷并定向发布 | 学生收到练习或作业 |
| 学生 | 打开「我的作业」，作答并提交 | 系统执行自动评分 |
| 教师 | 在批改台复核主观题并定稿 | 学生查看最终评分与评语 |
| 教师 / 学生 | 查看「我的学习」、学情报告和错题本 | 按薄弱点继续练习 |

截图来自当前项目界面；展示的是数学演示图谱，不包含真实学生记录。

## Agent 管理与分镜讲解

- **Agent 管理**：新增 Agent 配置、工具注册表与工具绑定、运行预演及调用记录。工具定义由代码提供，编排配置保存在数据库；数据权限仍由后端按当前用户实时校验。
- **教学内容**：新增结构化讲解分镜及逐段阅读组件，将段落旁白与视觉动作关联；教师可在内容管理中查看分镜预览，学生在知识点内容中逐段阅读。
- **数据库更新**：新增 Agent 配置、工具绑定与调用日志相关表，并扩展教学内容字段。启动前备份已有数据库，确认服务账号有执行 schema.sql / migration.sql 的权限。

使用时，管理员在 Agent 管理入口查看配置与工具，按权限维护编排；教师在教学内容中生成或查看知识点讲解，学生在知识点页面阅读。具体可见入口和可操作范围由登录角色决定。AI 生成继续使用后端 LLM 环境变量，真实 API Key 不应写入源码或提交。

新增 verify-storyboard.py 与 e2e-storyboard.js 用于开发验证，可能依赖运行服务和演示数据；执行前请检查脚本配置。docs/verify-storyboard-result.txt 是已有验证记录，不代表新部署环境自动通过检查。

## 大模型配置与机构分层管理

- **大模型配置管理**：新增管理页面与配置表，支持供应商预设、协议、鉴权方式、接口地址、模型及采样参数。数据库中启用的配置优先于启动环境变量，可在管理台调整；密钥回显使用掩码，保存空密钥时保留原值。
- **多协议接入**：配置支持 OpenAI 兼容、Anthropic、Gemini、Ollama、Volcengine 等协议选项；具体地址和鉴权按供应商要求设置，不同协议需使用匹配的模型与端点。
- **机构层级**：新增校区管理员、班级所属校区及学生批量导入相关能力；平台、机构、校区、教师与学生的可见范围由后端校验。
- **Agent 生效范围**：支持平台、机构、校区、班级层级的编排配置与范围总览。配置范围不代替数据权限，实际调用仍按当前登录身份授权。

使用流程：有权限的管理员维护机构、校区与班级 → 创建相应管理账号或导入学生 → 配置大模型连接 → 在 Agent 管理中选择作用范围、绑定工具并检查预演／调用记录。

迁移涉及 llm_config、组织层级和 Agent 范围字段，升级前备份数据库并检查 schema.sql / migration.sql。真实密钥只应放在部署环境或受控数据库，不应写进源码、验证记录或 Git；仓库不包含运行数据库。

新增 verify-llm.py、verify-agent-scope.py、verify-org-hierarchy.py 及对应 e2e 脚本用于开发验证，依赖项和服务地址请按实际环境调整。solution-cube-line-plane-angle.html 为独立几何讲解示例，可在浏览器打开。

---

# English Guide

## Features

- Visual knowledge graphs organized by subject, textbook, grade and term; containment, similarity and prerequisite relationships; template copying and outline imports.
- Questions linked to knowledge points, bulk imports, exact/keyword search and local embedding-based semantic search.
- Personal student graphs, mastery tracking, adaptive practice, teacher-composed papers and targeted assignments.
- Student submissions, automatic scoring and teacher review of subjective answers.
- Learning reports, incorrect-answer review, prerequisite gap analysis, learning paths and knowledge-point ranking.
- Administrator, teacher and student roles with backend role and ownership checks.
- Optional AI-assisted content generation and question recognition through a compatible API.
- Five selectable color themes saved per browser, synchronized across login, workspace, dialogs and graphs.

## Architecture

| Component | Technology and responsibility |
|---|---|
| Frontend | React 18, Vite 5, Ant Design 5, React Flow 12 |
| Backend | Java 17, Spring Boot 3.4.5, MyBatis-Plus |
| MySQL 8 | Accounts, students, questions, practice, assignments, answers and mastery data |
| Neo4j | Graphs, knowledge points and relationships; Cypher operations are centralized in GraphStore |
| Qdrant | Knowledge-point vector search |
| ONNX Runtime | Local bge-small-zh-v1.5 Chinese embeddings, 512 dimensions |

The current implementation uses Neo4j for graph storage and MySQL for business data. Vector search, embeddings and LLM features have separate configuration switches.

## Getting started

Install Java 17, Maven, Node.js/npm and MySQL 8, and configure an accessible Neo4j service. Add Qdrant for semantic search. Database/service data is not included in this repository.

```sh
git clone https://github.com/Crazy4Lamb/kg-quiz.git
cd kg-quiz
```

### 1. Configure services

PowerShell example; replace passwords with your own service credentials:

```powershell
$env:MYSQL_HOST = "127.0.0.1"
$env:MYSQL_PORT = "3306"
$env:MYSQL_USER = "root"
$env:MYSQL_PASSWORD = "your-mysql-password"
$env:NEO4J_URI = "bolt://127.0.0.1:7687"
$env:NEO4J_USER = "neo4j"
$env:NEO4J_PASSWORD = "your-neo4j-password"
$env:QDRANT_URL = "http://127.0.0.1:6333"
```

The default MySQL connection creates yt_knowledge_demo and executes schema.sql and migration.sql; the account needs appropriate permissions. Configure Neo4j and its authentication beforehand.

### 2. Start the backend

In the configured terminal:

```sh
cd backend
mvn spring-boot:run
```

The backend runs on port **8090**. When launched from backend/, model paths resolve to ../models/ by default.

### 3. Start the frontend

Open another terminal at the repository root:

```sh
cd frontend
npm ci --ignore-scripts
npm run dev
```

Open http://localhost:5173. Vite proxies /api to http://localhost:8090.

Windows launch scripts are included. start-backend.bat currently contains the original developer's Java/Maven paths; update them on another machine or use the commands above.

### 4. Try the teaching workflow

Initial demo accounts: administrator admin / admin123 and teacher teacher / teacher123. The demo student stu1 / 123456 requires the corresponding student record.

Import sample data as administrator, inspect graphs and questions, publish a paper as teacher, submit as student, then review grades and mastery changes. Change demo passwords before shared deployment.

## Included model

models/bge-small-zh-v1.5.onnx is included in ordinary Git: **94,851,877 bytes (~90.46 MiB)**. models/tokenizer.json is included as well. A normal git clone downloads both; Git LFS and a separate model download are unnecessary.

The original development notes identify Xenova/bge-small-zh-v1.5 as the model source. Override paths with KG_EMBEDDING_MODEL and KG_EMBEDDING_TOKENIZER. Replacement models must match the tokenizer and configured embedding dimensions.

## Configuration

| Environment variables | Purpose |
|---|---|
| MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD | MySQL connection |
| NEO4J_URI / NEO4J_USER / NEO4J_PASSWORD / NEO4J_DATABASE | Neo4j connection |
| QDRANT_URL / QDRANT_ENABLED | Vector service endpoint and switch |
| KG_EMBEDDING_ENABLED / KG_EMBEDDING_MODEL / KG_EMBEDDING_TOKENIZER | Embedding switch and model paths |
| LLM_ENABLED / LLM_BASE_URL / LLM_API_KEY / LLM_MODEL | Optional LLM features |

See backend/src/main/resources/application.yml for defaults. The dev profile contains the original development network addresses; override them for your environment. Keep actual secrets in environment variables.

Unavailable Qdrant or embeddings can fall back to keyword search. MySQL and Neo4j are still needed for the corresponding core functionality.

## Layout and development

- backend/: Java API, authentication, business logic and database scripts.
- frontend/: React UI.
- models/: ONNX model and tokenizer.
- docs/: development notes and check results.
- start-*.bat: Windows scripts for the original development machine.

Build the frontend with npm run build from frontend/. Run backend checks with mvn test from backend/. Root-level verification scripts may require running services, seeded data or machine-specific paths; inspect them first.

The [historical Chinese development guide](docs/legacy-demo-guide.zh-CN.md) preserves detailed workflows and API examples, but its older architecture descriptions are superseded by these READMEs.

## Illustrated User Guide

### 1. Sign in and choose a theme

![Login and theme selector](docs/images/login.png)

Sign in as administrator, teacher or with a student account assigned by a teacher. The top-right theme button offers Dopamine, Peach, Mint, Sky and Midnight palettes. Your choice is saved in the current browser and applied across the login page and workspace.

### 2. Edit a knowledge graph

![Graph management workspace](docs/images/graphs.png)

Administrators and teachers select a textbook/grade graph in Graph Management (图谱管理), then choose Edit Graph (编辑图谱). An empty database offers a sample-data import; existing installations can use their current graphs.

![Knowledge points and relationship editor](docs/images/editor.png)

- Drag nodes, then click Save Layout (保存布局).
- Double-click a node to edit it; drag between connection handles to create relationships.
- Click an edge to adjust its containment, similarity or prerequisite type/direction.
- Select a node and use Related Add (关联新增), or import an indented outline with Bulk Import (批量导入).

### 3. Complete a teaching cycle

| Role | Action | Outcome |
|---|---|---|
| Administrator / teacher | Create a student, select a textbook and assign an account | A personal knowledge graph is created |
| Teacher | Add questions and link knowledge points | Content is ready for practice and papers |
| Teacher | Generate adaptive practice or publish a paper to selected students | Students receive assignments |
| Student | Open My Assignments (我的作业), answer and submit | Automatic scoring runs |
| Teacher | Review subjective answers and finalize grades | Students see final scores and comments |
| Teacher / student | Review learning reports and incorrect answers | Continue targeted practice |

Screenshots show the current application and a mathematics demo graph, without real student records.

## Agent Management and Storyboard Explanations

- **Agent management:** configuration, a code-defined tool catalog, tool bindings, dry runs and call logs. Orchestration is stored as data; backend authorization continues to enforce the current user's data scope.
- **Teaching content:** structured explanation storyboards and a segment-by-segment reader connect narration to visual actions. Teachers can preview storyboards in content management; students read them in knowledge-point content.
- **Database changes:** new agent configuration, binding and call-log tables, plus teaching-content fields. Back up existing databases and ensure the service account can execute schema.sql and migration.sql before updating.

Administrators inspect agents/tools and maintain permitted orchestration settings. Teachers generate or inspect explanations; students read the resulting segments. Visible controls and actions depend on role permissions. AI generation uses backend LLM environment variables; keep actual API keys out of source and Git.

verify-storyboard.py and e2e-storyboard.js are development checks that may require running services and demo data. Review their settings first. docs/verify-storyboard-result.txt is an existing verification record, not proof that a new deployment passes automatically.

## LLM Configuration and Organization Hierarchy

- **LLM settings:** a management page and configuration table support provider presets, protocol/authentication, endpoints, models and sampling parameters. Enabled database settings override startup environment defaults. Keys are masked in management responses; saving an empty key preserves the existing value.
- **Protocol choices:** OpenAI-compatible, Anthropic, Gemini, Ollama and Volcengine options are available. Use the provider's matching endpoint, authentication and model settings.
- **Organization hierarchy:** campus administrators, campus-linked classes and bulk student import extend organization management. Platform, organization, campus, teacher and student data scopes are enforced by the backend.
- **Agent scope:** orchestration can target platform, organization, campus or class levels, with scope overviews. Configuration scope does not replace runtime authorization.

Workflow: authorized administrators maintain organizations/campuses/classes, create management accounts or import students, configure LLM access, then choose an Agent scope, bind tools and inspect dry runs/call logs.

Database changes include llm_config, organization hierarchy and Agent scope fields. Back up databases and review schema.sql / migration.sql before upgrading. Keep real credentials in deployment environments or a controlled database, never source code, verification reports or Git. Runtime databases are not included in the repository.

verify-llm.py, verify-agent-scope.py, verify-org-hierarchy.py and their e2e counterparts are development checks requiring suitable services and settings. solution-cube-line-plane-angle.html is a standalone geometry explanation example that can be opened in a browser.
