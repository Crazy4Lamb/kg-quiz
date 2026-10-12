/**
 * 会话存储：令牌与登录用户信息落在 localStorage，刷新页面不丢登录态。
 */
const TOKEN_KEY = 'kgq.token';
const USER_KEY = 'kgq.user';

/** 会话变化订阅：登录/登出/令牌失效时通知路由层重新渲染 */
const listeners = new Set();

export function subscribeSession(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function emit() {
  const current = readSession();
  listeners.forEach((fn) => fn(current));
}

export const ROLE = {
  ADMIN: 'ADMIN',
  ORG_ADMIN: 'ORG_ADMIN',
  TEACHER: 'TEACHER',
  STUDENT: 'STUDENT'
};

export const ROLE_LABEL = {
  ADMIN: '平台管理员',
  ORG_ADMIN: '机构管理员',
  TEACHER: '教师',
  STUDENT: '学生'
};

export const ROLE_COLOR = {
  ADMIN: 'red',
  ORG_ADMIN: 'purple',
  TEACHER: 'blue',
  STUDENT: 'green'
};

/** 拥有教学资源管理权限的角色（教师 / 机构管理员 / 平台管理员） */
export const STAFF_ROLES = [ROLE.ADMIN, ROLE.ORG_ADMIN, ROLE.TEACHER];

export const isStaff = (user) => STAFF_ROLES.includes(user?.role);

/** 能管机构与账号的角色 */
export const ORG_ROLES = [ROLE.ADMIN, ROLE.ORG_ADMIN];

export function readSession() {
  try {
    const token = localStorage.getItem(TOKEN_KEY);
    const raw = localStorage.getItem(USER_KEY);
    if (!token || !raw) return null;
    const user = JSON.parse(raw);
    if (!user || !user.role) return null;
    return { token, user };
  } catch {
    return null;
  }
}

export function saveSession(token, user) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
  emit();
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
  emit();
}

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function isStudent(user) {
  return user?.role === ROLE.STUDENT;
}

/** 各角色的落地首页 */
export function homePathOf(user) {
  if (!user) return '/login';
  if (isStudent(user)) return '/my';
  if (user.role === ROLE.ORG_ADMIN) return '/org';
  return '/graphs';
}
