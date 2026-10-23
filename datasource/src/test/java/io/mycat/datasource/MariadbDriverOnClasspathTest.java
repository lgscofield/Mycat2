package io.mycat.datasource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * I-AU-6 Task 1 锁：mariadb-java-client 必须作为 datasource 模块的 compile 依赖在 classpath 上，
 * 否则 MariaDB 后端（含 client_ed25519 认证）永远加载不到驱动，只能落到 mysql-connector-j 报
 * {@code Unable to load authentication plugin}。版本由根 pom dependencyManagement（3.5.3）提供。
 */
class MariadbDriverOnClasspathTest {

    @Test
    void mariadbDriverIsLoadable() throws Exception {
        Class<?> driver = Class.forName("org.mariadb.jdbc.Driver");
        assertNotNull(driver);
    }
}
