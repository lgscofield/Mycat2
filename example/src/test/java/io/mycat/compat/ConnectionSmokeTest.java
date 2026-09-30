package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M1 兼容性冒烟测试：验证"MySQL/MariaDB 客户端眼中的 L1 连接是否达标"。
 * 不断言分片 CRUD（那由现有 example 测试覆盖），只钉住连接层最小面：
 * 连上 Mycat(8066) -> SELECT 1 -> SHOW DATABASES 非空。
 * 版本来自 -Dcompat.db（CI 矩阵注入），仅用于报告标识。
 */
public class ConnectionSmokeTest implements MycatTest {

    @Test
    public void connectAndBasicRoundTrip() throws Exception {
        String db = System.getProperty("compat.db", "unknown");
        System.out.println("[compat-smoke] target db = " + db + ", mycat url = " + DB_MYCAT);

        try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
            assertNotNull(mycat, "无法连上 Mycat 前端");

            List<Map<String, Object>> one = executeQuery(mycat, "SELECT 1");
            assertFalse(one.isEmpty(), "SELECT 1 无结果");

            List<Map<String, Object>> dbs = executeQuery(mycat, "SHOW DATABASES");
            assertFalse(dbs.isEmpty(), "SHOW DATABASES 返回空 —— 前端握手/元数据未通");
        }
    }
}
