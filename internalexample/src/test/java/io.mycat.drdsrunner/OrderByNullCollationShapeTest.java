package io.mycat.drdsrunner;

import org.junit.Assert;
import org.junit.Test;

/**
 * I-DEBT-55 形状锁：裸 ORDER BY 的下推 SQL 不得带 (col IS NULL) 模拟前缀
 * （MySQL 方言 NullCollation 已为 LOW，FIRST==原生，无须模拟；出现前缀即为
 * collation 吃了 Calcite 默认 HIGH 的运行时铁证）。
 */
public class OrderByNullCollationShapeTest extends DrdsTest {

    /** 裸 ASC：修复前必红（当前 collation=LAST → deparse 发 (`user_id` IS NULL), `user_id`）。 */
    @Test
    public void bareAscPushdownHasNoIsNullEmulation() throws Exception {
        String sqls = parse("select id, user_id from db1.sharding order by user_id")
                .specificSql().toString();
        // 校准自 Task 1 RED 实跑形状：下推列带表限定符 `sharding`.`user_id`
        Assert.assertTrue("裸 ASC 应下推裸序: " + sqls, sqls.contains("ORDER BY `sharding`.`user_id`"));
        Assert.assertFalse("裸 ASC 不得带模拟前缀: " + sqls, sqls.contains("IS NULL"));
    }

    /** 裸 DESC：同理无模拟。 */
    @Test
    public void bareDescPushdownHasNoIsNullEmulation() throws Exception {
        String sqls = parse("select id from db1.sharding order by days desc")
                .specificSql().toString();
        Assert.assertTrue(sqls, sqls.contains("ORDER BY `sharding`.`days` DESC"));
        Assert.assertFalse(sqls, sqls.contains("IS NULL"));
    }

    /** 守卫（防过宽）：显式 NULLS LAST 须尊重原意，模拟前缀必须保留——修复前后都应绿。 */
    @Test
    public void explicitNullsLastKeepsEmulation() throws Exception {
        String sqls = parse("select id from db1.sharding order by user_id nulls last")
                .specificSql().toString();
        Assert.assertTrue("显式 NULLS LAST 应保留模拟: " + sqls,
                sqls.contains("(`sharding`.`user_id` IS NULL)"));
    }
}
