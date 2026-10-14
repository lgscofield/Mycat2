package io.mycat.assemble;

import io.mycat.hint.CreateClusterHint;
import io.mycat.hint.CreateDataSourceHint;
import org.junit.Assert;
import org.junit.Test;

import javax.annotation.concurrent.NotThreadSafe;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;

/**
 * I-DEBT-43 红锁：Mycat 代理 {@code DROP TABLE IF EXISTS} 语义须与 MySQL 一致——
 * 后端物理表存在即删，不依赖 Mycat 元数据是否登记过该表。
 *
 * <p>现场取证（本次会话）：{@code DropTableSQLHandler.onExecute} 目前实现只在
 * {@code metadataManager.getTable(schema, table) != null} 分支向 datanode 下发 DROP；
 * 未登记分支直接 {@code response.sendOk()} 但后端物理表纹丝不动。同文件 {@code onPhysics}
 * 是 fallback 死码从未被调用。
 *
 * <p>本测试绕过 Mycat 直连后端建一具体表（Mycat 元数据不掌握此表），随后经 Mycat 代理
 * 发 {@code DROP TABLE IF EXISTS}，再绕过 Mycat 断言后端已消失。修前 RED，修后 GREEN。
 */
@NotThreadSafe
@net.jcip.annotations.NotThreadSafe
public class MycatDdlDropRoutingTest implements MycatTest {

    private static final String TEST_TBL = "i_debt_43_ghost";

    @Test
    public void dropTableIfExistsShouldReachBackendWhenMycatMetadataLacksHandler() throws Exception {
        // 1) Mycat 侧准备一个可路由的 schema db1（与 HbtTest 同族：dw0 → DB1，prototype 集群指向 dw0）
        try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
            execute(mycat, RESET_CONFIG);
            execute(mycat, CreateDataSourceHint.create("dw0", DB1));
            execute(mycat, CreateClusterHint.create("prototype",
                    Arrays.asList("dw0"), Collections.emptyList()));
            execute(mycat, "CREATE DATABASE db1");

            // 2) 绕过 Mycat，直连 DB1 后端建物理表（Mycat 元数据里不存在 TableHandler）
            try (Connection backend = getMySQLConnection(DB1);
                 Statement st = backend.createStatement()) {
                st.execute(String.format("DROP TABLE IF EXISTS db1.%s", TEST_TBL));
                st.execute(String.format("CREATE TABLE db1.%s (id int PRIMARY KEY)", TEST_TBL));
            }
            try (Connection backend = getMySQLConnection(DB1)) {
                Assert.assertTrue("前置：后端物理表应存在（若否本测试无效）",
                        existTable(backend, "db1", TEST_TBL));
            }

            // 3) 经 Mycat 代理执行 DROP TABLE IF EXISTS——预期不再"假 OK"，须真正下沉后端
            execute(mycat, "USE `db1`");
            execute(mycat, String.format("DROP TABLE IF EXISTS db1.%s", TEST_TBL));

            // 4) 绕回后端断言物理表已消失（I-DEBT-43 修前此处必红）
            try (Connection backend = getMySQLConnection(DB1)) {
                Assert.assertFalse(
                        "I-DEBT-43: Mycat 代理 DROP TABLE IF EXISTS 须下沉后端物理删除，"
                                + "即使 Mycat 元数据未登记该表（对齐 MySQL IF EXISTS 语义）",
                        existTable(backend, "db1", TEST_TBL));
            }

            // 5) 幂等复验：表已不存在时再 DROP 不应报错（MySQL 语义）
            execute(mycat, String.format("DROP TABLE IF EXISTS db1.%s", TEST_TBL));
        }
    }
}
