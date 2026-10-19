package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * I-DEBT-58 回归锁：{@code SHOW CREATE DATABASE} 必须回读物理后端的<b>真实库级定义</b>
 * （含 {@code DEFAULT CHARACTER SET ...}），而非 Mycat 自拼的裸 {@code CREATE DATABASE `db`;}。
 *
 * <p><b>真根</b>：Mycat2 是薄透传代理，schema 级字符集的权威唯一来源在物理后端
 * （{@code CreateDatabaseSQLHandler.onPhysics} 已把 {@code CREATE DATABASE ... CHARACTER SET ...}
 * 原样下发物理库），Mycat 自身配置不持 charset。旧仿真面 {@code ShowCreateDatabaseHandler} 只
 * {@code new SQLCreateDatabaseStatement().setDatabase()} 从不挂 charset → 恒出裸串，既丢字符集，
 * 又无法匹配真 MySQL 随版本变的条件注释方言（5.7 省略 COLLATE、8.0 带 ENCRYPTION）。
 *
 * <p><b>修法（Design A）</b>：与仓内 7 个 SHOW 命令 + I-DEBT-53 复制状态 SHOW 同惯例，把
 * {@code SHOW CREATE DATABASE} 透传 prototype 后端读回真实定义，失败才退回裸仿真兜底。
 *
 * <p><b>判据（版本无关）</b>：显式以 {@code CHARACTER SET utf8mb4} 建库（显式覆盖任何后端默认，
 * 确定性成立），断言 {@code SHOW CREATE DATABASE} 返回的定义串含 {@code utf8mb4}。只验字符集名、
 * 不验 collation 名（collation 随 MySQL/MariaDB 版本变，恰是 Design A「交后端自报方言」的立论）。
 *
 * <p><b>激活门控（绝不假绿/假红）</b>：连不上 8066 前端或建库面不通 → {@link #abort} 记 NO_DATA，
 * 待 CI compat 矩阵 / run-matrix 起 infra 后逐档真跑。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ShowCreateDatabaseCharsetTest implements MycatTest {

    private static final String SCHEMA = "idebt58_cs";

    /** infra 探测失败置位：各格 abort 记 NO_DATA，不得静默 0 tests。 */
    private static boolean infraDown = false;

    @BeforeAll
    public void prepareSchema() throws Exception {
        try (Connection c = getMySQLConnection(DB_MYCAT)) {
            // 探测 Mycat 前端握手
            executeQuery(c, "SELECT 1");
            execute(c, RESET_CONFIG);
            execute(c, "drop database if exists " + SCHEMA);
            execute(c, "create database " + SCHEMA + " character set utf8mb4");
        } catch (Exception e) {
            infraDown = true;
            System.out.println("[NO_DATA] 8066/建库面未通 → SHOW CREATE DATABASE 字符集格待 infra：" + e.getMessage());
        }
    }

    @AfterAll
    public void purgeSchema() {
        if (infraDown) {
            return;
        }
        try (Connection c = getMySQLConnection(DB_MYCAT)) {
            execute(c, "drop database if exists " + SCHEMA);
            execute(c, RESET_CONFIG);
        } catch (Exception ignored) {
            // infra 未起时 @BeforeAll 已 abort，清理尽力而为
        }
    }

    @Test
    public void showCreateDatabaseReturnsRealCharsetNotBare() throws Exception {
        if (infraDown) {
            abort("8066 无 Mycat/后端 infra → SHOW CREATE DATABASE 字符集格 NO_DATA");
        }
        try (Connection c = getMySQLConnection(DB_MYCAT)) {
            List<Map<String, Object>> rows = executeQuery(c, "SHOW CREATE DATABASE `" + SCHEMA + "`");
            assertTrue(!rows.isEmpty(), "SHOW CREATE DATABASE 无结果集");
            String definition = pickCreateDefinition(rows.get(0));
            assertTrue(definition.toLowerCase().contains("utf8mb4"),
                    "SHOW CREATE DATABASE 未回读库级字符集（疑似裸仿真）—— 应含 utf8mb4，实得：" + definition);
        }
    }

    /** 从结果行取「建库定义」串：优先 {@code Create Database} 列，兜底扫描含 CREATE 的列值（透传后列名取后端）。 */
    private String pickCreateDefinition(Map<String, Object> row) {
        Object def = row.get("Create Database");
        if (def != null) {
            return def.toString();
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String v = String.valueOf(e.getValue());
            if (v.toUpperCase().contains("CREATE DATABASE")) {
                return v;
            }
        }
        return row.values().stream().findFirst().map(String::valueOf).orElse("");
    }
}
