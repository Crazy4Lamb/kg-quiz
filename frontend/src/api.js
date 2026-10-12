import axios from 'axios';
import { getToken, clearSession } from './auth.js';

const http = axios.create({ baseURL: '/api', timeout: 60000 });

// 自动带上登录令牌
http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  (resp) => resp.data,
  (err) => {
    const status = err.response?.status;
    const msg = err.response?.data?.message || err.message || '请求失败';
    if (status === 401) {
      clearSession();
      // 已在登录页就不要反复跳，避免刷屏
      if (!window.location.pathname.startsWith('/login')) {
        window.location.replace('/login?expired=1');
      }
    }
    return Promise.reject(new Error(msg));
  }
);

export const api = {
  // 认证与账号
  login: (data) => http.post('/auth/login', data),
  logout: () => http.post('/auth/logout'),
  me: () => http.get('/auth/me'),
  changePassword: (data) => http.put('/auth/me/password', data),
  listAccounts: () => http.get('/auth/accounts'),
  createAccount: (data) => http.post('/auth/accounts', data),
  resetAccountPassword: (id, data) => http.put(`/auth/accounts/${id}/password`, data),
  setAccountEnabled: (id, enabled) =>
    http.put(`/auth/accounts/${id}/enabled`, null, { params: { enabled } }),
  deleteAccount: (id) => http.delete(`/auth/accounts/${id}`),

  // 机构管理平台（机构 → 校区 → 班级 → 教师 / 学生）
  listOrgs: (params) => http.get('/orgs', { params }),
  orgOverview: (id) => http.get(`/orgs/${id}/overview`),
  createOrg: (data) => http.post('/orgs', data),
  updateOrg: (id, data) => http.put(`/orgs/${id}`, data),
  setOrgStatus: (id, status) => http.put(`/orgs/${id}/status`, { status }),
  listCampuses: (orgId) => http.get(`/orgs/${orgId}/campuses`),
  createCampus: (data) => http.post('/orgs/campuses', data),
  updateCampus: (id, data) => http.put(`/orgs/campuses/${id}`, data),
  deleteCampus: (id) => http.delete(`/orgs/campuses/${id}`),
  listClazzes: (params) => http.get('/orgs/clazzes', { params }),
  createClazz: (data) => http.post('/orgs/clazzes', data),
  updateClazz: (id, data) => http.put(`/orgs/clazzes/${id}`, data),
  deleteClazz: (id) => http.delete(`/orgs/clazzes/${id}`),
  listTeachers: (params) => http.get('/orgs/teachers', { params }),
  createTeacher: (data) => http.post('/orgs/teachers', data),
  updateTeacher: (userId, data) => http.put(`/orgs/teachers/${userId}`, data),
  setTeacherEnabled: (userId, enabled) => http.put(`/orgs/teachers/${userId}/enabled`, { enabled }),
  resetTeacherPassword: (userId, data) => http.put(`/orgs/teachers/${userId}/password`, data),
  deleteTeacher: (userId) => http.delete(`/orgs/teachers/${userId}`),
  listOrgStudents: (orgId, params) => http.get(`/orgs/${orgId}/students`, { params }),
  moveStudent: (studentId, clazzId) => http.put(`/orgs/students/${studentId}/clazz`, { clazzId }),

  // 图谱
  listGraphs: (params) => http.get('/graphs', { params }),
  createGraph: (data) => http.post('/graphs', data),
  graphDetail: (id) => http.get(`/graphs/${id}`),
  updateGraph: (id, data) => http.put(`/graphs/${id}`, data),
  copyGraph: (id, data) => http.post(`/graphs/${id}/copy`, data),
  deleteGraph: (id) => http.delete(`/graphs/${id}`),
  createNode: (graphId, data) => http.post(`/graphs/${graphId}/nodes`, data),
  batchNodes: (graphId, data) => http.post(`/graphs/${graphId}/nodes/batch`, data),
  updateNode: (graphId, nodeId, data) => http.put(`/graphs/${graphId}/nodes/${nodeId}`, data),
  deleteNode: (graphId, nodeId) => http.delete(`/graphs/${graphId}/nodes/${nodeId}`),
  savePositions: (graphId, items) => http.put(`/graphs/${graphId}/nodes/positions`, { items }),
  createRelation: (graphId, data) => http.post(`/graphs/${graphId}/relations`, data),
  updateRelation: (graphId, relationId, data) => http.put(`/graphs/${graphId}/relations/${relationId}`, data),
  deleteRelation: (graphId, relationId) => http.delete(`/graphs/${graphId}/relations/${relationId}`),

  // 检索
  searchNodes: (params) => http.get('/search/nodes', { params }),

  // 学生
  listStudents: () => http.get('/students'),
  createStudent: (data) => http.post('/students', data),
  studentGraph: (id) => http.get(`/students/${id}/graph`),
  studentReport: (id) => http.get(`/students/${id}/report`),
  wrongQuestions: (id) => http.get(`/students/${id}/wrong-questions`),
  adjustMastery: (id, data) => http.put(`/students/${id}/mastery`, data),
  resetStudentPassword: (id, data) => http.put(`/students/${id}/password`, data),
  deleteStudent: (id) => http.delete(`/students/${id}`),

  // 题库
  listQuestions: (params) => http.get('/questions', { params }),
  questionDetail: (id) => http.get(`/questions/${id}`),
  createQuestion: (data) => http.post('/questions', data),
  batchQuestions: (data) => http.post('/questions/batch', data),
  updateQuestion: (id, data) => http.put(`/questions/${id}`, data),
  deleteQuestion: (id) => http.delete(`/questions/${id}`),

  // 自适应出题引擎
  generatePlan: (data) => http.post('/exams/plans', data),
  confirmPlan: (examId) => http.post(`/exams/${examId}/confirm`),
  listExams: (params) => http.get('/exams', { params }),
  examDetail: (examId) => http.get(`/exams/${examId}`),
  mastery: (studentId) => http.get('/exams/mastery', { params: { studentId } }),

  // 组卷与批改（教师 / 管理员）
  listPapers: (params) => http.get('/exams/papers', { params }),
  createPaper: (data) => http.post('/exams/papers', data),
  paperDetail: (examId) => http.get(`/exams/papers/${examId}`),
  deletePaper: (examId) => http.delete(`/exams/papers/${examId}`),
  assignPaper: (examId, studentIds) => http.post(`/exams/papers/${examId}/assign`, { studentIds }),
  unassignPaper: (examId, studentId) => http.delete(`/exams/papers/${examId}/assign/${studentId}`),
  paperSubmissions: (examId) => http.get(`/exams/papers/${examId}/submissions`),
  submissionDetail: (examId, studentId) =>
    http.get(`/exams/papers/${examId}/submissions/${studentId}`),
  gradeSubmission: (examId, studentId, items) =>
    http.post(`/exams/papers/${examId}/submissions/${studentId}/grade`, { items }),
  resetSubmission: (examId, studentId) =>
    http.post(`/exams/papers/${examId}/submissions/${studentId}/reset`),
  teacherWorkload: () => http.get('/exams/teacher-workload'),

  // 学生：老师发给我的作业
  myAssignments: () => http.get('/exams/my'),
  myPaper: (examId) => http.get(`/exams/my/${examId}`),
  submitMyPaper: (examId, items) => http.post(`/exams/my/${examId}/submit`, { items }),
  practiceNow: (params) => http.post('/exams/my/practice', null, { params }),

  // 图分析（应用层图算法）
  pageRank: (graphId, params) => http.get(`/analysis/graphs/${graphId}/pagerank`, { params }),
  recommendPath: (studentId, targetNodeId) =>
    http.get(`/analysis/students/${studentId}/path`, { params: { targetNodeId } }),
  gapAnalysis: (studentId, nodeId) =>
    http.get(`/analysis/students/${studentId}/gap`, { params: { nodeId } }),

  // 管理
  seedDemo: () => http.post('/admin/seed-demo'),
  rebuildIndex: () => http.post('/admin/vector-index/rebuild'),
  indexStatus: () => http.post('/admin/vector-index/status'),

  // 入学诊断（诊）
  generateDiagnosis: (data) => http.post('/diagnosis/generate', data),
  diagnosisReport: (studentId) => http.get(`/diagnosis/${studentId}/report`),
  myDiagnosisReport: () => http.get('/diagnosis/my/report'),
  startMyDiagnosis: (params) => http.post('/diagnosis/my/generate', null, { params }),

  // 知识点教学内容（学）
  contentStatus: () => http.get('/contents/status'),
  contentsByNode: (nodeId) => http.get(`/contents/node/${nodeId}`),
  contentsByGraph: (graphId) => http.get(`/contents/graph/${graphId}`),
  publishedContents: (nodeId) => http.get(`/contents/node/${nodeId}/published`),
  generateContent: (data) => http.post('/contents/generate', data),
  batchGenerateContent: (data) => http.post('/contents/batch', data),
  updateContent: (id, data) => http.put(`/contents/${id}`, data),
  setContentStatus: (id, status) => http.put(`/contents/${id}/status`, { status }),
  deleteContent: (id) => http.delete(`/contents/${id}`),

  // 学习优先级推荐
  listRecommendRules: (params) => http.get('/recommend/rules', { params }),
  saveRecommendRule: (data) => http.post('/recommend/rules', data),
  deleteRecommendRule: (id) => http.delete(`/recommend/rules/${id}`),
  recommendPreview: (params) => http.get('/recommend/preview', { params }),
  myRecommend: (params) => http.get('/recommend/my', { params }),

  // 学习任务流
  myTasksToday: () => http.get('/tasks/my/today'),
  regenerateMyTasks: () => http.post('/tasks/my/regenerate'),
  completeTask: (taskId) => http.post(`/tasks/my/${taskId}/complete`),
  reopenTask: (taskId) => http.post(`/tasks/my/${taskId}/reopen`),
  startTask: (taskId) => http.post(`/tasks/my/${taskId}/start`),
  studentTasks: (studentId) => http.get(`/tasks/student/${studentId}`),
  regenerateStudentTasks: (studentId) => http.post(`/tasks/student/${studentId}/regenerate`),
  assignTeacherTask: (data) => http.post('/tasks/teacher', data),
  deleteTask: (taskId) => http.delete(`/tasks/${taskId}`),

  // 题目知识点自动识别
  suggestMapping: (data) => http.post('/questions/mapping/suggest', data),
  batchSuggestMapping: (data) => http.post('/questions/mapping/batch', data),
  autoMapping: (data) => http.post('/questions/mapping/auto', data),
  applyMapping: (data) => http.post('/questions/mapping/apply', data),
  removeMapping: (questionId, nodeId) => http.delete(`/questions/mapping/${questionId}/${nodeId}`)
};

export const unwrap = (r) => r?.data ?? r;
