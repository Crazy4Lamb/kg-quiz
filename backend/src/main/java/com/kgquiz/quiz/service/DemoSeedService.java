package com.kgquiz.quiz.service;

import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import com.kgquiz.quiz.service.neo4j.IdAllocator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 示例数据导入：仅允许在空库执行，导入后自动重建向量索引。
 *
 * <p>图域（图谱 / 节点 / 关系）灌进 Neo4j（{@code demo-seed-graph.cypher}），
 * 业务域（题库 / 关联 / 学生 / 掌握度）灌进 MySQL（{@code demo-seed.sql}），
 * 两边靠节点 uid 对齐。导入后把 {@code id_seq} 顶到种子的最大 uid，避免后续新建撞号。</p>
 *
 * <p>多租户：示例数据必须有归属机构，导入前先确保存在一个机构，
 * 并把图谱（Cypher 里的 {@code ${orgId}} 占位符）与业务数据（导入后统一 UPDATE）都挂上去。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoSeedService {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final GraphStore graphStore;
    private final IdAllocator idAllocator;
    private final VectorIndexService vectorIndexService;
    private final AuthService authService;

    public Map<String, Object> importDemo() {
        long graphCount = graphStore.countGraphs();
        if (graphCount > 0) {
            throw new BizException("图谱已有 " + graphCount + " 个，示例数据仅支持在空库导入");
        }
        Integer questionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM question", Integer.class);
        if (questionCount != null && questionCount > 0) {
            throw new BizException("题库已有 " + questionCount + " 道题，示例数据仅支持在空库导入");
        }

        // 0. 示例数据得有归属机构树（机构 → 校区 → 班级），与默认账号用同一套
        AuthService.OrgScope scope = authService.ensureDefaultScope();

        // 1. 图域 → Neo4j（把脚本里的 ${orgId} 换成真实机构 id）
        String cypher = readResource("demo-seed-graph.cypher")
                .replace("${orgId}", String.valueOf(scope.orgId()));
        int graphStatements = graphStore.executeScript(cypher);

        // 2. 业务域 → MySQL
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("demo-seed.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);

        // 3. 归属机构：种子 SQL 里不写 org_id，统一在这里补齐，
        //    否则机构账号登录后看不到这批数据（平台管理员不受影响）。
        jdbcTemplate.update("UPDATE question SET org_id = ? WHERE org_id IS NULL", scope.orgId());
        jdbcTemplate.update("UPDATE student SET org_id = ?, campus_id = ?, clazz_id = ? WHERE org_id IS NULL",
                scope.orgId(), scope.campusId(), scope.clazzId());
        jdbcTemplate.update("UPDATE exam SET org_id = ? WHERE org_id IS NULL", scope.orgId());
        jdbcTemplate.update("UPDATE sys_user SET org_id = ?, campus_id = COALESCE(campus_id, ?) "
                        + "WHERE org_id IS NULL AND role <> 'ADMIN'",
                scope.orgId(), scope.campusId());

        // 4. 序列顶到种子最大 uid
        graphStore.maxUids().forEach(idAllocator::bumpTo);

        // 5. 给种子学生补登录账号（bootstrap 只在启动时跑，刚灌进来的学生还没账号）
        authService.bootstrapAccounts();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orgId", scope.orgId());
        result.put("orgName", AuthService.DEFAULT_ORG_NAME);
        result.put("graphStatements", graphStatements);
        result.put("graphs", graphStore.countGraphs());
        result.put("nodes", graphStore.countNodes());
        result.put("relations", graphStore.countRelations());
        result.put("questions", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM question", Integer.class));
        result.put("students", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM student", Integer.class));

        // 图谱数据就绪后重建向量索引，让语义检索立即可用
        if (vectorIndexService.available()) {
            try {
                result.put("vectorIndex", vectorIndexService.rebuild());
            } catch (Exception e) {
                log.warn("示例数据导入后重建向量索引失败: {}", e.getMessage());
                result.put("vectorIndex", "failed: " + e.getMessage());
            }
        } else {
            result.put("vectorIndex", "skipped (语义检索不可用)");
        }
        log.info("示例数据导入完成: {}", result);
        return result;
    }

    private static String readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException("读取种子脚本失败: " + path + " — " + e.getMessage());
        }
    }
}
