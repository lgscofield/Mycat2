package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import io.mycat.hint.MigrateHint;
import io.mycat.hint.MigrateListHint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * M4/I-CI-4 · 迁移轴兼容矩阵格（MigrateHint 流水线端到端）——M4「MigrateTest 矩阵绿」判据的 harness 侧建格。
 *
 * <p>核心验证路径：建源表→灌数据→建目标表→MIGRATE hint→轮询 COMPLETE→比对行数。
 * 覆盖 normal→normal 最基本迁移场景；sharding/global 变体由原有 {@link io.mycat.migrate.MigrateTest} 覆盖。
 *
 * <p><b>激活条件（当前 NO_DATA，绝不假绿）</b>：需 {@code run-matrix} 起 Mycat 实例 + 可用后端 MySQL。
 * 无 8066 → 探测连接被拒 → {@link org.junit.jupiter.api.Assumptions#abort} 记 NO_DATA（须响，不得静默当绿）。
 *
 * <p>persona 维度由 {@code -Dmycat.persona} 驱动 run-matrix 起对应画像后端。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MigrateMatrixTest implements MycatTest {

    private static final String PLAIN_URL = "jdbc:mysql://127.0.0.1:8066/mysql"
            + "?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true"
            + "&connectTimeout=3000&socketTimeout=30000&sslMode=DISABLED";
    private static final String USER = "root";
    private static final String PW = "123456";

    private static boolean infraDown = false;

    @org.junit.jupiter.api.BeforeAll
    public void probeInfra() {
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            // 探测即可
        } catch (Exception e) {
            infraDown = true;
            System.out.println("[NO_DATA] 8066 无 Mycat 基础设施 → 迁移矩阵格待 run-matrix 起 mycat+后端：" + e.getMessage());
        }
    }

    private void assumeInfraUp() {
        if (infraDown) {
            abort("8066 无 Mycat 基础设施 → 迁移矩阵格 NO_DATA（待 run-matrix 起 mycat+后端）");
        }
    }

    /**
     * 最基本迁移场景：normal→normal，63 行数据，验证 COMPLETE=1 且目标表行数=源表行数。
     */
    @Test
    public void migrateNormalToNormalCompletes() throws Exception {
        assumeInfraUp();
        try (Connection connection = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            execute(connection, RESET_CONFIG);
            execute(connection, "CREATE DATABASE IF NOT EXISTS db1");

            // 源表
            execute(connection, "CREATE TABLE IF NOT EXISTS db1.`mig_src` (\n" +
                    "  `id` bigint NOT NULL,\n" +
                    "  `val` varchar(100) DEFAULT NULL,\n" +
                    "  PRIMARY KEY (`id`)\n" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8");
            execute(connection, "DELETE FROM db1.mig_src");
            for (int i = 1; i <= 63; i++) {
                execute(connection, "INSERT INTO db1.mig_src (id, val) VALUES(" + i + ", 'v" + i + "')");
            }

            // 目标表
            execute(connection, "CREATE TABLE IF NOT EXISTS db1.`mig_dst` (\n" +
                    "  `id` bigint NOT NULL,\n" +
                    "  `val` varchar(100) DEFAULT NULL,\n" +
                    "  PRIMARY KEY (`id`)\n" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8");
            execute(connection, "DELETE FROM db1.mig_dst");

            // 触发迁移
            MigrateHint.Input input = new MigrateHint.Input();
            input.setSchemaName("db1");
            input.setTableName("mig_src");

            MigrateHint.Output output = new MigrateHint.Output();
            output.setSchemaName("db1");
            output.setTableName("mig_dst");

            execute(connection, MigrateHint.create("matrixNormalToNormal", input, output).build());

            // 轮询完成（超时 10s）
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
            boolean complete = false;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
                List<Map<String, Object>> tasks = executeQuery(connection, MigrateListHint.create().build());
                String res = tasks.toString();
                if (res.contains("NAME=matrixNormalToNormal") && res.contains("COMPLETE=1")) {
                    complete = true;
                    break;
                }
            }
            assertTrue(complete, "迁移应在 10s 内完成，MigrateList 应显示 COMPLETE=1");

            // 验证目标表行数
            long srcCount = countRows(connection, "db1", "mig_src");
            long dstCount = countRows(connection, "db1", "mig_dst");
            assertEquals(srcCount, dstCount,
                    "目标表行数应等于源表行数（迁移完整性）: src=" + srcCount + " dst=" + dstCount);
        }
    }

    private long countRows(Connection c, String schema, String table) throws Exception {
        List<Map<String, Object>> rows = executeQuery(c, "SELECT COUNT(*) AS cnt FROM " + schema + "." + table);
        return ((Number) rows.get(0).get("cnt")).longValue();
    }
}
