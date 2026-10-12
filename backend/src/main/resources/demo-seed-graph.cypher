// ============================================================
// 示例图谱（Neo4j 侧）：小学数学 · 人教版 · 五年级上（数与代数）
//
// 这里的 uid 与 demo-seed.sql 中的 question_node.node_id /
// student_node_state.source_node_id 一一对应，**不能改**。
// 导入后 DemoSeedService 会把 id_seq 推到 12 / 17，避免后续新建撞号。
// ============================================================

// ---------- 图谱本体 ----------
// ${orgId} 由 DemoSeedService 在导入前替换为真实机构 id（多租户归属）
CREATE (g:KgGraph {
    uid: 1,
    orgId: ${orgId},
    name: '小学数学五年级上（人教版）',
    subject: '数学',
    textbook: '人教版',
    grade: '五年级',
    term: '上学期',
    description: '数与代数领域：小数乘法、小数除法、简易方程',
    createdAt: toString(datetime()),
    updatedAt: toString(datetime())
});

// ---------- 知识点节点（weight 重要性权重，posX/posY 画布坐标） ----------
UNWIND [
    {uid: 1,  name: '小数乘整数',           description: '小数乘整数的计算方法与算理，先按整数乘法计算，再根据小数位数点小数点',                 code: 'MATH-5A-01', weight: 1.50, posX: 60,  posY: 60,  sortOrder: 1},
    {uid: 2,  name: '小数乘小数',           description: '小数乘小数的计算法则，积的小数位数等于两个因数小数位数之和',                         code: 'MATH-5A-02', weight: 1.80, posX: 60,  posY: 220, sortOrder: 2},
    {uid: 3,  name: '积的近似数',           description: '用四舍五入法求积的近似值，根据要求保留指定的小数位数',                               code: 'MATH-5A-03', weight: 1.00, posX: 300, posY: 140, sortOrder: 3},
    {uid: 4,  name: '小数乘法应用题',       description: '运用小数乘法解决购物、面积、行程等实际问题',                                         code: 'MATH-5A-04', weight: 1.20, posX: 300, posY: 300, sortOrder: 4},
    {uid: 5,  name: '除数是整数的小数除法', description: '小数除以整数，商的小数点与被除数小数点对齐，不够除时补 0 继续除',                     code: 'MATH-5A-05', weight: 1.50, posX: 60,  posY: 400, sortOrder: 5},
    {uid: 6,  name: '除数是小数的除法',     description: '利用商不变性质把除数转化为整数，再按小数除法计算',                                   code: 'MATH-5A-06', weight: 1.80, posX: 60,  posY: 560, sortOrder: 6},
    {uid: 7,  name: '商的近似数',           description: '求商的近似数需要多除一位再四舍五入，与求积的近似数方法对比',                         code: 'MATH-5A-07', weight: 1.00, posX: 300, posY: 480, sortOrder: 7},
    {uid: 8,  name: '循环小数',             description: '小数部分从某一位起，一个或几个数字依次不断重复出现的小数',                           code: 'MATH-5A-08', weight: 0.80, posX: 540, posY: 480, sortOrder: 8},
    {uid: 9,  name: '小数四则混合运算',     description: '小数四则混合运算的顺序与整数相同，灵活运用运算定律简算',                             code: 'MATH-5A-09', weight: 1.60, posX: 540, posY: 220, sortOrder: 9},
    {uid: 10, name: '用字母表示数',         description: '用字母表示数量关系、运算定律与计算公式',                                             code: 'MATH-5A-10', weight: 1.20, posX: 760, posY: 60,  sortOrder: 10},
    {uid: 11, name: '解简易方程',           description: '等式的性质与解方程的方法，会检验方程的解',                                           code: 'MATH-5A-11', weight: 1.60, posX: 760, posY: 220, sortOrder: 11},
    {uid: 12, name: '实际问题与方程',       description: '列方程解决实际问题：找等量关系、设未知数、列方程、解方程、检验',                     code: 'MATH-5A-12', weight: 1.80, posX: 760, posY: 400, sortOrder: 12}
] AS row
CREATE (n:KgNode {
    uid: row.uid,
    graphId: 1,
    orgId: ${orgId},
    name: row.name,
    description: row.description,
    code: row.code,
    weight: row.weight,
    posX: row.posX,
    posY: row.posY,
    sortOrder: row.sortOrder,
    createdAt: toString(datetime()),
    updatedAt: toString(datetime())
});

// ---------- 前置关系：from 是前置，to 是后置 ----------
// 后置关系不冗余存储，由 PREREQUISITE 反向查询得到
UNWIND [
    {uid: 1,  fromUid: 1,  toUid: 2},
    {uid: 2,  fromUid: 2,  toUid: 3},
    {uid: 3,  fromUid: 2,  toUid: 4},
    {uid: 4,  fromUid: 1,  toUid: 5},
    {uid: 5,  fromUid: 5,  toUid: 6},
    {uid: 6,  fromUid: 6,  toUid: 7},
    {uid: 7,  fromUid: 7,  toUid: 8},
    {uid: 8,  fromUid: 3,  toUid: 9},
    {uid: 9,  fromUid: 4,  toUid: 9},
    {uid: 10, fromUid: 6,  toUid: 9},
    {uid: 11, fromUid: 10, toUid: 11},
    {uid: 12, fromUid: 11, toUid: 12},
    {uid: 13, fromUid: 9,  toUid: 12}
] AS row
MATCH (a:KgNode {uid: row.fromUid}), (b:KgNode {uid: row.toUid})
CREATE (a)-[:PREREQUISITE {uid: row.uid, graphId: 1, createdAt: toString(datetime())}]->(b);

// ---------- 相近关系（对称，反向不重复存储） ----------
UNWIND [
    {uid: 14, fromUid: 3, toUid: 7},
    {uid: 15, fromUid: 1, toUid: 5},
    {uid: 16, fromUid: 2, toUid: 6}
] AS row
MATCH (a:KgNode {uid: row.fromUid}), (b:KgNode {uid: row.toUid})
CREATE (a)-[:SIMILAR {uid: row.uid, graphId: 1, createdAt: toString(datetime())}]->(b);

// ---------- 包含关系：小数四则混合运算 包含 小数乘法应用题 ----------
UNWIND [
    {uid: 17, fromUid: 9, toUid: 4}
] AS row
MATCH (a:KgNode {uid: row.fromUid}), (b:KgNode {uid: row.toUid})
CREATE (a)-[:CONTAINS {uid: row.uid, graphId: 1, createdAt: toString(datetime())}]->(b);
