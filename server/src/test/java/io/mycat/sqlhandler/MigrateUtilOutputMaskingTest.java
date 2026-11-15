/*
 * I-DEBT-126 离线锁：MIGRATE_LIST / show() INFO 列凭据回显封堵。
 *
 * 活体点位（修复前）：MigrateUtil.MigrateJdbcOutput 持有 password 字段且挂了 @ToString 意图
 * （实际 import 的是 groovy.transform.ToString——对 Java 类是惰性注解，javac 不生成 toString）。
 * MigrateScheduler.toString()（show() 的 INFO 列，MigrateUtil.java:78）与 register() 成功后的
 * LOGGER.info(rowMap)（:113-115）都会把 output 拼进日志/结果集——一旦有人把 import 换成 lombok
 * 的"顺手修复"，password 立刻明文外泄。本锁钉两件事：
 *  1) 输出面：MigrateJdbcOutput/MigrateJdbcAnyOutput 必须渲染真实诊断串（字段名=值 形态），
 *     且串内永不含明文 password（lombok @ToString + @ToString.Exclude）；
 *  2) 聚合面：MigrateScheduler.toString()（即 show() INFO 列内容）同样不含明文 password。
 */
package io.mycat.sqlhandler;

import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MigrateUtilOutputMaskingTest {

    private static final String SECRET = "Migrate#2026";

    @Test
    public void migrateJdbcOutputToStringRendersFieldsButNeverPassword() {
        MigrateUtil.MigrateJdbcOutput output = new MigrateUtil.MigrateJdbcOutput();
        output.setUsername("app");
        output.setPassword(SECRET);
        output.setUrl("jdbc:mysql://127.0.0.1:3306/db");

        String s = output.toString();
        assertTrue(s.contains("username=app"), "toString 必须是真实诊断串（非惰性注解下的 Object 默认形态）");
        assertTrue(s.contains("jdbc:mysql://127.0.0.1:3306/db"), "非敏感字段保留");
        assertFalse(s.contains(SECRET), "toString 不得泄露明文 password");
    }

    @Test
    public void migrateJdbcAnyOutputToStringNeverPassword() {
        MigrateUtil.MigrateJdbcAnyOutput output = new MigrateUtil.MigrateJdbcAnyOutput();
        output.setUsername("app");
        output.setPassword(SECRET);
        output.setUrl("jdbc:mysql://127.0.0.1:3306/db");

        String s = output.toString();
        assertTrue(s.contains("username=app"), "toString 必须是真实诊断串");
        assertFalse(s.contains(SECRET), "toString 不得泄露明文 password");
    }

    @Test
    public void migrateSchedulerInfoColumnNeverContainsPassword() throws Exception {
        MigrateUtil.MigrateJdbcOutput output = new MigrateUtil.MigrateJdbcOutput();
        output.setUsername("app");
        output.setPassword(SECRET);

        MigrateUtil.MigrateController controller = new MigrateUtil.MigrateController() {
            @Override
            public Future<Void> getFuture() {
                return Future.succeededFuture();
            }

            @Override
            public void stop() {
            }
        };
        MigrateUtil.MigrateScheduler scheduler = MigrateUtil.MigrateScheduler.of(
                "m1", Collections.emptyList(), output, controller);

        String s = scheduler.toString();
        assertTrue(s.contains("MigrateScheduler"), "scheduler toString 保留诊断形态");
        assertFalse(s.contains(SECRET), "show() INFO 列（scheduler.toString）不得泄露明文 password");
    }
}
