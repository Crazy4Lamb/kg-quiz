# Mathematics Knowledge Graph and Adaptive Practice Platform

[项目首页 / Overview](README.md) | [中文说明](README.zh-CN.md) | [English guide](README.en.md)

A teaching demo for administrators, teachers and students. It connects knowledge relationships, questions, assignments and learning feedback to support targeted practice. The sample content covers primary-school mathematics. The application UI is currently Chinese; these repository guides are bilingual.

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
