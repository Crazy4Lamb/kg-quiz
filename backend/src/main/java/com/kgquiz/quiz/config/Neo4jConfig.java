package com.kgquiz.quiz.config;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Neo4j 驱动装配。
 *
 * <p>{@code GraphDatabase.driver()} 是惰性的，不会在创建时就连库；真正的连通性校验
 * 放在 {@link com.kgquiz.quiz.service.neo4j.GraphStore} 的初始化里，便于给出明确报错。</p>
 */
@Slf4j
@Configuration
public class Neo4jConfig {

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(KgProperties kg) {
        KgProperties.Neo4j cfg = kg.getNeo4j();
        log.info("初始化 Neo4j 驱动: uri={}, database={}", cfg.getUri(), cfg.getDatabase());
        return GraphDatabase.driver(
                cfg.getUri(),
                AuthTokens.basic(cfg.getUsername(), cfg.getPassword()),
                Config.builder()
                        .withMaxConnectionPoolSize(20)
                        .withConnectionAcquisitionTimeout(10, TimeUnit.SECONDS)
                        .withConnectionTimeout(10, TimeUnit.SECONDS)
                        .build());
    }
}
