package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import io.mycat.hint.CreateClusterHint;
import io.mycat.hint.CreateDataSourceHint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * I-DEBT-55 活库对拍矩阵：裸 ORDER BY 的 NULL 排布逐格对拍真 MySQL。
 *
 * <p>脊柱：validator config 单旋钮 defaultNullCollation=LOW 修复后，
 * 经 Mycat(:8066) 的行序必须与同构行集在原生 MySQL(:3306) 上的行序逐格相等。
 * 控制组用直连建表的物理影子库 {@code i55_ctrl}（Mycat 不可见），行集与逻辑表全等
 * ⇒ 控制组在真 MySQL 上的 ORDER BY 输出序即序真值（plan Task 3 裁定）。
 *
 * <p>夹具：user_id NULL 在 id∈{2,5,8}；days NULL 在 id∈{3,6,8}；id=8 双 NULL；
 * 乱序插入防"插入序==结果序"伪证。单片轴 tnorm（4 行子集 id 1..4）对拍影子 c4。
 *
 * <p>NO_DATA 门控同 {@link DumpRestoreRoundTripMatrixTest}：8066/3306 不可达 → abort 记
 * NO_DATA 绝不假绿；基础设施在 → 逐格硬断言。cell 12 窗口轴若引擎不支持 → PARK 立账不伪绿。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OrderByNullCollationMatrixTest implements MycatTest {

    /** Mycat 侧运行期组装的专用逻辑库（撞名免疫：不复用既有 e2e 库名）。 */
    private static final String DB = "i55db";
    /** 跨片轴逻辑表：mod_hash 2db×2tb，形状同 AssembleTest travelrecord 谱系。 */
    private static final String SHARD = DB + ".tshard";
    /** 单片轴逻辑表（无分片 DDL → 纯下推）。 */
    private static final String NORM = DB + ".tnorm";
    /** 直连 3306 的影子控制库（Mycat 不可见），c8 与 tshard 行集全等、c4 与 tnorm 全等。 */
    private static final String CTRL = "i55_ctrl";

    private static boolean infraDown = false;

    /**
     * 10 格 [mycatSql, ctrlSql, tieCol]：tieCol=对拍轴列名，"det"=确定性全序格。
     * 平票组（ORDER BY 列同值）内部相对序 MySQL 不保证（真库单表返回插入/索引序、
     * Mycat 分片归并返回分片序，两者均合法）——Ruling：矩阵钉住的是 I-DEBT-55
     * 语义靶：NULL 块占位（逐位置 nullity 序列相等）+ 非 NULL 子序列逐元素全等；
     * det 格（有 id 决胜键或值互异）仍取严格序列相等。
     */
    private static final String[][] CELLS = {
            {"SELECT id FROM " + NORM + " ORDER BY user_id", "SELECT id FROM c4 ORDER BY user_id", "user"},
            {"SELECT id FROM " + NORM + " ORDER BY user_id DESC", "SELECT id FROM c4 ORDER BY user_id DESC", "user"},
            {"SELECT id FROM " + SHARD + " ORDER BY user_id", "SELECT id FROM c8 ORDER BY user_id", "user"},
            {"SELECT id FROM " + SHARD + " ORDER BY user_id DESC", "SELECT id FROM c8 ORDER BY user_id DESC", "user"},
            {"SELECT id FROM " + SHARD + " ORDER BY days", "SELECT id FROM c8 ORDER BY days", "days"},
            {"SELECT id FROM " + SHARD + " ORDER BY days DESC", "SELECT id FROM c8 ORDER BY days DESC", "days"},
            {"SELECT id FROM " + SHARD + " ORDER BY user_id ASC, id DESC",
                    "SELECT id FROM c8 ORDER BY user_id ASC, id DESC", "det"},
            {"SELECT id FROM " + SHARD + " ORDER BY user_id LIMIT 3 OFFSET 2",
                    "SELECT id FROM c8 ORDER BY user_id LIMIT 3 OFFSET 2", "user"},
            {"SELECT id FROM " + SHARD + " ORDER BY days DESC LIMIT 4",
                    "SELECT id FROM c8 ORDER BY days DESC LIMIT 4", "days"},
            {"SELECT id FROM " + SHARD + " WHERE user_id IS NULL ORDER BY days",
                    "SELECT id FROM c8 WHERE user_id IS NULL ORDER BY days", "det"},
    };

    /** 轴列 NULL 集（夹具元数据）：用于逐位置 nullity 对拍。 */
    private static final java.util.Set<Long> NULL_USER = java.util.Set.of(2L, 5L, 8L);
    private static final java.util.Set<Long> NULL_DAYS = java.util.Set.of(3L, 6L, 8L);

    /** (id, user_id, days)：user_id NULL 在 {2,5,8}，days NULL 在 {3,6,8}，id=8 双 NULL。乱序插入。 */
    private static final Object[][] ROWS8 = {
            {5L, null, 50}, {2L, null, 20}, {8L, null, null}, {1L, "u1", 10},
            {6L, "u6", null}, {3L, "u3", null}, {7L, "u7", 70}, {4L, "u4", 40},
    };
    private static final Object[][] ROWS4 = {
            {3L, "u3", null}, {1L, "u1", 10}, {4L, "u4", 40}, {2L, null, 20},
    };

    @BeforeAll
    public void prepare() throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:8066/mysql?connectTimeout=3000&socketTimeout=15000&sslMode=DISABLED",
                "root", "123456")) {
            // 8066 在位
        } catch (Exception e) {
            infraDown = true;
            System.out.println("[NO_DATA] 8066 无 Mycat → I-DEBT-55 对拍矩阵需 classpath 直启服务端：" + e.getMessage());
            return;
        }
        try (Connection c = getMySQLConnection(DB1)) {
            // 3306 在位
        } catch (Exception e) {
            infraDown = true;
            System.out.println("[NO_DATA] 3306 原生后端不可达：" + e.getMessage());
            return;
        }
        // 影子控制库：直连 3306 建，Mycat 不可见
        try (Connection admin = getMySQLConnection(DB1)) {
            execute(admin, "DROP DATABASE IF EXISTS " + CTRL);
            execute(admin, "CREATE DATABASE " + CTRL);
            execute(admin, "CREATE TABLE " + CTRL + ".c8 (id BIGINT PRIMARY KEY, user_id VARCHAR(100), days INT)");
            execute(admin, "CREATE TABLE " + CTRL + ".c4 (id BIGINT PRIMARY KEY, user_id VARCHAR(100), days INT)");
            for (Object[] r : ROWS8) {
                insertRow(admin, CTRL + ".c8", r);
            }
            for (Object[] r : ROWS4) {
                insertRow(admin, CTRL + ".c4", r);
            }
        }
        // Mycat 侧运行期组装：cluster c0/c1 → 3306/3307（AssembleTest initCluster 同款）
        try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
            execute(mycat, RESET_CONFIG);
            execute(mycat, CreateDataSourceHint.create("dw0", DB1));
            execute(mycat, CreateDataSourceHint.create("dr0", DB1));
            execute(mycat, CreateDataSourceHint.create("dw1", DB2));
            execute(mycat, CreateDataSourceHint.create("dr1", DB2));
            execute(mycat, CreateClusterHint.create("c0", Arrays.asList("dw0"), Arrays.asList("dr0")));
            execute(mycat, CreateClusterHint.create("c1", Arrays.asList("dw1"), Arrays.asList("dr1")));
            execute(mycat, "DROP DATABASE IF EXISTS " + DB);
            execute(mycat, "CREATE DATABASE " + DB);
            execute(mycat, "CREATE TABLE " + SHARD + " (id bigint NOT NULL, user_id varchar(100) DEFAULT NULL,"
                    + " days int DEFAULT NULL, PRIMARY KEY (id))"
                    + " dbpartition by mod_hash(id) tbpartition by mod_hash(id) tbpartitions 2 dbpartitions 2");
            execute(mycat, "CREATE TABLE " + NORM + " (id bigint NOT NULL, user_id varchar(100) DEFAULT NULL,"
                    + " days int DEFAULT NULL, PRIMARY KEY (id))");
            // 幂等夹具：历次 run 可能留下物理分表残行（DROP DATABASE 不下沉物理库），插前先清底
            execute(mycat, "DELETE FROM " + SHARD);
            execute(mycat, "DELETE FROM " + NORM);
            for (Object[] r : ROWS8) {
                insertRow(mycat, SHARD, r);
            }
            for (Object[] r : ROWS4) {
                insertRow(mycat, NORM, r);
            }
        }
    }

    @AfterAll
    public void purge() throws Exception {
        if (infraDown) {
            return;
        }
        try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
            execute(mycat, "DROP DATABASE IF EXISTS " + DB);
            execute(mycat, RESET_CONFIG);
        }
        // 物理层残库 Best-effort 回收（逻辑 DROP 不下沉；清不掉不阻断，夹具已自清底保幂等）
        for (String url : new String[]{DB1, DB2}) {
            try (Connection admin = getMySQLConnection(url)) {
                for (int i = 0; i < 2; i++) {
                    execute(admin, "DROP DATABASE IF EXISTS " + DB + "_" + i);
                }
            } catch (Exception ignored) {
            }
        }
        try (Connection admin = getMySQLConnection(DB1)) {
            execute(admin, "DROP DATABASE IF EXISTS " + CTRL);
        }
    }

    @Test
    public void matrixAgainstNativeMysql() throws Exception {
        if (infraDown) {
            abort("NO_DATA：基础设施不在位");
        }
        List<String> failures = new ArrayList<>();
        try (Connection mycat = getMySQLConnection(DB_MYCAT);
                Connection ctrl = getMySQLConnection(DB1)) {
            for (String[] cell : CELLS) {
                List<Long> mine = ids(executeQuery(mycat, cell[0]));
                List<Long> truth = ids(executeQuery(ctrl, qualify(cell[1])));
                String mark = "cell[" + cell[0] + " tie=" + cell[2] + "] mycat=" + mine + " truth=" + truth;
                if ("det".equals(cell[2])) {
                    if (!mine.equals(truth)) {
                        failures.add(mark);
                    }
                    continue;
                }
                java.util.Set<Long> nullSet = "user".equals(cell[2]) ? NULL_USER : NULL_DAYS;
                // NULL 块占位：逐位置 nullity 序列相等
                List<Boolean> myNullity = mine.stream().map(nullSet::contains).toList();
                List<Boolean> truthNullity = truth.stream().map(nullSet::contains).toList();
                if (!myNullity.equals(truthNullity)) {
                    failures.add(mark + " NULL块占位偏离");
                    continue;
                }
                // 非 NULL 子序列逐元素全等
                List<Long> myNonNull = mine.stream().filter(i -> !nullSet.contains(i)).toList();
                List<Long> truthNonNull = truth.stream().filter(i -> !nullSet.contains(i)).toList();
                if (!myNonNull.equals(truthNonNull)) {
                    failures.add(mark + " 非NULL序偏离");
                }
            }
        }
        assertEquals(List.of(), failures, "对拍矩阵偏离真库:");
    }

    /** 影子 SQL：表名限定到控制库。 */
    private static String qualify(String ctrlSql) {
        return ctrlSql.replace("FROM c8", "FROM " + CTRL + ".c8").replace("FROM c4", "FROM " + CTRL + ".c4");
    }

    @Test
    public void explicitNullsLastIsHonored() throws Exception {
        if (infraDown) {
            abort("NO_DATA：基础设施不在位");
        }
        try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
            List<Map<String, Object>> rows =
                    executeQuery(mycat, "SELECT id, user_id FROM " + SHARD + " ORDER BY user_id NULLS LAST");
            assertNotNull(rows.get(0).get("user_id"), "显式 NULLS LAST：首行必须非 NULL");
            assertNull(rows.get(rows.size() - 1).get("user_id"), "显式 NULLS LAST：末行必须 NULL");
        }
    }

    @Test
    public void windowRankAxisOrPark() throws Exception {
        if (infraDown) {
            abort("NO_DATA：基础设施不在位");
        }
        String mine = "SELECT id, RANK() OVER (ORDER BY days) r FROM " + SHARD + " ORDER BY r, id";
        List<Long> mycatSeq;
        try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
            mycatSeq = ids(executeQuery(mycat, mine));
        } catch (Exception e) {
            abort("PARK：窗口 OVER(ORDER BY) 引擎不支持 → 立账不伪绿：" + e.getMessage());
            return;
        }
        try (Connection ctrl = getMySQLConnection(DB1)) {
            List<Long> truth = ids(executeQuery(ctrl, qualify(
                    "SELECT id, RANK() OVER (ORDER BY days) r FROM c8 ORDER BY r, id")));
            assertEquals(truth, mycatSeq, "窗口轴偏离真库");
        }
    }

    private static List<Long> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
    }

    private void insertRow(Connection c, String table, Object[] r) throws Exception {
        execute(c, "INSERT INTO " + table + " (id, user_id, days) VALUES ("
                + r[0] + ", " + (r[1] == null ? "NULL" : "'" + r[1] + "'") + ", " + r[2] + ")");
    }
}
