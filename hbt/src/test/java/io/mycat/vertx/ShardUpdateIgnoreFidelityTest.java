package io.mycat.vertx;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlUpdateStatement;
import io.mycat.Partition;
import io.mycat.calcite.table.NormalTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-74：分片 UPDATE 改写保真锁。
 *
 * <p>根因（druid 1.2.28 源码坐实）：{@code MySqlUpdateStatement} 未覆写 {@code clone()}，
 * 继承的 {@code SQLUpdateStatement.clone()} 降型为通用 {@code SQLUpdateStatement} 且不复制
 * mysql 私有的 {@code ignore}/{@code lowPriority} 字段，而 {@code IGNORE} 子句只由
 * {@code MySqlOutputVisitor.visit(MySqlUpdateStatement)} 按 {@code isIgnore()} 打印，
 * 通用形态走 {@code visit(SQLUpdateStatement)} 永不输出 ⇒ 每片物理语句丢 IGNORE，
 * {@code UPDATE IGNORE} 的「截断降级为 warning 1265」语义退化为 ERROR 1406。
 *
 * <p>对照：{@code MySqlDeleteStatement.clone()} 自行复制了 ignore，DELETE 路径无此症，
 * 本锁不开 DELETE 面（避免伪锁）。夹具用匿名 Partition/NormalTable，不连库。
 */
public class ShardUpdateIgnoreFidelityTest {

    private static final String UPDATE_IGNORE = "UPDATE IGNORE wlk SET note = ? WHERE id = ?";

    /**
     * 根因定性锁（非目标行为锁）：钉住 druid 1.2.28 裸 clone 确实丢 IGNORE 这一实症——
     * 它解释了补偿层为何必须存在；若上游将来覆写 clone 修复此症，本锁变红即提醒可拆补偿。
     * RED 阶段观察到的失败消息即此症状本体：实得 [UPDATE wlk SET note = ? WHERE id = ?]。
     */
    @Test
    public void rawDruidCloneDropsIgnore_compensationIsRequired() {
        MySqlUpdateStatement stmt = (MySqlUpdateStatement) SQLUtils.parseSingleMysqlStatement(UPDATE_IGNORE);
        assertTrue(stmt.isIgnore(), "解析层应识别 IGNORE");
        SQLUpdateStatement copy = stmt.clone();
        String text = copy.toString();
        assertTrue(!(copy instanceof MySqlUpdateStatement), "clone 应降型为通用语句（根因类型面）");
        assertTrue(!text.toUpperCase().contains("IGNORE"), "裸 clone 应丢 IGNORE（实症定性），实得：" + text);
    }

    /** 生产 seam：handleNormal 产出的每片物理 SQL 须含 IGNORE，且 reparse 回 MySqlUpdateStatement 仍 isIgnore。 */
    @Test
    public void handleNormalKeepsIgnoreOnPhysicalSql() throws Exception {
        SQLStatement stmt = SQLUtils.parseSingleMysqlStatement(UPDATE_IGNORE);
        List<VertxExecuter.EachSQL> res = new ArrayList<>();
        VertxUpdateExecuter.handleNormal(stmt, res, Collections.emptyList(), normalTable("ds0", "db1_0", "wlk_0"));

        assertEquals(1, res.size());
        String sql = res.get(0).getSql();
        assertTrue(sql.toUpperCase().contains("IGNORE"), "分片物理 SQL 应含 IGNORE，实得：" + sql);
        assertTrue(sql.contains("wlk_0"), "表名应已改写为物理分片表，实得：" + sql);
        MySqlUpdateStatement reparsed = (MySqlUpdateStatement) SQLUtils.parseSingleMysqlStatement(sql);
        assertTrue(reparsed.isIgnore(), "物理 SQL 重新解析后 IGNORE 应在 AST 层可见");
    }

    /** 同根窄面：LOW_PRIORITY 也是 MySqlUpdateStatement 私有字段，clone 同样会丢，保真机制成套钉住。 */
    @Test
    public void handleNormalKeepsLowPriorityOnPhysicalSql() throws Exception {
        SQLStatement stmt = SQLUtils.parseSingleMysqlStatement("UPDATE LOW_PRIORITY wlk SET a = 1 WHERE id = 1");
        List<VertxExecuter.EachSQL> res = new ArrayList<>();
        VertxUpdateExecuter.handleNormal(stmt, res, Collections.emptyList(), normalTable("ds0", "db1_0", "wlk_0"));

        assertTrue(res.get(0).getSql().toUpperCase().contains("LOW_PRIORITY"));
    }

    /** 负面锁：无 mysql 私有子句的 UPDATE 不得被保真机制凭空注入 IGNORE。 */
    @Test
    public void plainUpdateStaysPlain() throws Exception {
        SQLStatement stmt = SQLUtils.parseSingleMysqlStatement("UPDATE wlk SET a = 1 WHERE id = 1");
        List<VertxExecuter.EachSQL> res = new ArrayList<>();
        VertxUpdateExecuter.handleNormal(stmt, res, Collections.emptyList(), normalTable("ds0", "db1_0", "wlk_0"));

        assertTrue(!res.get(0).getSql().toUpperCase().contains("IGNORE"));
    }

    /** 多片路径：handleTargets 逐片产出均须保真（分片/全局两分支共用此入口）。 */
    @Test
    public void handleTargetsKeepsIgnoreOnEveryShard() throws Exception {
        SQLStatement stmt = SQLUtils.parseSingleMysqlStatement(UPDATE_IGNORE);
        List<VertxExecuter.EachSQL> res = new ArrayList<>();
        VertxUpdateExecuter.handleTargets(stmt, res, Collections.emptyList(),
                List.of(partition("ds0", "db1_0", "wlk_0"), partition("ds0", "db1_1", "wlk_1")));

        assertEquals(2, res.size());
        for (VertxExecuter.EachSQL each : res) {
            assertTrue(each.getSql().toUpperCase().contains("IGNORE"), "每片都须含 IGNORE，实得：" + each.getSql());
        }
    }

    private static NormalTable normalTable(String target, String schema, String table) {
        return new NormalTable(null, partition(target, schema, table), null) {
            @Override
            public Partition getDataNode() {
                return partition(target, schema, table);
            }
        };
    }

    private static Partition partition(String target, String schema, String table) {
        return new Partition() {
            @Override
            public String getTargetName() {
                return target;
            }

            @Override
            public String getSchema() {
                return schema;
            }

            @Override
            public String getTable() {
                return table;
            }

            @Override
            public Integer getDbIndex() {
                return 0;
            }

            @Override
            public Integer getTableIndex() {
                return 0;
            }

            @Override
            public Integer getIndex() {
                return 0;
            }
        };
    }
}
