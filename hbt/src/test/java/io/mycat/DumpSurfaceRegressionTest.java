package io.mycat;

import io.mycat.prototypeserver.mysql.VisualTableHandler;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-ME-2 · mysqldump 查询面列覆盖回归锁（进程内半区）。
 * <p>
 * 判据逐条锚定官网 mysqldump 源码（非臆测）：
 * <ul>
 * <li>MySQL 8.0 {@code client/mysqldump.cc}（github mysql/mysql-server 8.0，缓存 mysqldump80.cc）：
 * SHOW FIELDS 失败回退列查询 {@code SELECT ... FROM `INFORMATION_SCHEMA`.`COLUMNS`}（:2790-2807）、
 * 视图收尾 {@code SELECT CHECK_OPTION, DEFINER, SECURITY_TYPE, CHARACTER_SET_CLIENT,
 * COLLATION_CONNECTION FROM information_schema.views}（:5882）、
 * {@code --column-statistics} 查 COLUMN_STATISTICS（:3652）、
 * {@code --tablespaces} 查 FILES（:4363-4462）与 PARTITIONS（:4287）；</li>
 * <li>MariaDB 11.4 {@code client/mysqldump.cc}（缓存 mariadb-dump114.cc）：
 * system-versioned 探针查 TABLES.TABLE_TYPE（:3157）、
 * {@code SELECT column_name, extra, generation_expression, data_type FROM information_schema.columns}（:3383）、
 * {@code SELECT TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS}（:3914）。</li>
 * </ul>
 * e2e 半区（真 dump 往返）见 {@code example} 侧 DumpSurfaceMatrixTest 建格，待 infra 真跑。
 * 8.0/8.4 源码均未引用 CHECK_CONSTRAINTS/TABLE_CONSTRAINTS——约束走 SHOW CREATE TABLE，本锁不虚构该缺口。
 */
public class DumpSurfaceRegressionTest {

    private static Set<String> columnsOf(VisualTableHandler handler) {
        return handler.getColumns().stream()
                .map(c -> c.getColumnName().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private static void assertContainsAll(String table, VisualTableHandler handler, String... required) {
        Set<String> have = columnsOf(handler);
        for (String col : required) {
            assertTrue(have.contains(col.toUpperCase(Locale.ROOT)),
                    table + " 缺 mysqldump 消费列: " + col + "（现有: " + have + "）");
        }
    }

    /** 8.0 SHOW FIELDS 回退投影 + MariaDB 生成列查询（mysqldump80.cc:2790-2807 / mariadb:3383）。 */
    @Test
    public void columnsTableCoversDumpFieldProjection() {
        assertContainsAll("COLUMNS", MysqlMetadataManager.COLUMNS_TABLE_HANDLER,
                "TABLE_SCHEMA", "TABLE_NAME", "COLUMN_NAME", "ORDINAL_POSITION",
                "COLUMN_DEFAULT", "IS_NULLABLE", "DATA_TYPE", "COLUMN_TYPE",
                "COLUMN_KEY", "EXTRA", "COLUMN_COMMENT", "GENERATION_EXPRESSION");
    }

    /** 8.0 视图收尾查询六列（mysqldump80.cc:5882）。 */
    @Test
    public void viewsTableCoversDumpViewFinalizationQuery() {
        assertContainsAll("VIEWS", MysqlMetadataManager.VIEW_TABLE_HANDLER,
                "TABLE_SCHEMA", "TABLE_NAME", "CHECK_OPTION", "DEFINER",
                "SECURITY_TYPE", "CHARACTER_SET_CLIENT", "COLLATION_CONNECTION");
    }

    /** --column-statistics 查询四列（mysqldump80.cc:3652，i_s.COLUMN_STATISTICS 官方列集）。 */
    @Test
    public void columnStatisticsCoversHistogramQuery() {
        assertContainsAll("COLUMN_STATISTICS", MysqlMetadataManager.COLUMN_STATISTICS_TABLE_HANDLER,
                "SCHEMA_NAME", "TABLE_NAME", "COLUMN_NAME", "HISTOGRAM");
    }

    /** --tablespaces 查询（mysqldump80.cc:4363-4462）；TOTAL_EXTENTS/INITIAL_SIZE 官网 i_s.FILES 页在列，现仿表缺 → 本用例当前应为红。 */
    @Test
    public void filesTableCoversTablespaceQuery() {
        assertContainsAll("FILES", MysqlMetadataManager.FILE_TABLE_HANDLER,
                "TABLESPACE_NAME", "FILE_TYPE", "LOGFILE_GROUP_NAME", "FILE_NAME",
                "ENGINE", "TOTAL_EXTENTS", "INITIAL_SIZE", "MAXIMUM_SIZE", "AUTOEXTEND_SIZE");
    }

    /** PARTITIONS 派生表空间名（mysqldump80.cc:4287）。 */
    @Test
    public void partitionsTableCoversTablespaceNameQuery() {
        assertContainsAll("PARTITIONS", MysqlMetadataManager.PARTITIONS_TABLE_HANDLER,
                "TABLE_SCHEMA", "TABLE_NAME", "TABLESPACE_NAME");
    }

    /** MariaDB system-versioned 探针 + 表列举（mariadb-dump114.cc:3157/4672）。 */
    @Test
    public void tablesTableCoversMariaDbProbes() {
        assertContainsAll("TABLES", MysqlMetadataManager.TABLES_TABLE_HANDLER,
                "TABLE_SCHEMA", "TABLE_NAME", "TABLE_TYPE");
    }

    /** MariaDB 触发器列举（mariadb-dump114.cc:3914）。 */
    @Test
    public void triggersTableCoversMariaDbTriggerListing() {
        assertContainsAll("TRIGGERS", MysqlMetadataManager.TRIGGERS_TABLE_HANDLER,
                "TRIGGER_SCHEMA", "TRIGGER_NAME");
    }

    /** 行形状守卫：不依赖后端 schema DI 的仿表，scanAll 各行宽度 = 声明列数（错位行=协议层错列）。
     * COLUMNS/TABLES/STATISTICS 的 scanAll 需 MetaClusterCurrent 注册 MetadataManager，不列入本守卫（e2e 半区覆盖）。 */
    @Test
    public void dumpSurfaceRowsHaveDeclaringWidth() {
        Arrays.asList(MysqlMetadataManager.VIEW_TABLE_HANDLER,
                MysqlMetadataManager.COLUMN_STATISTICS_TABLE_HANDLER,
                MysqlMetadataManager.FILE_TABLE_HANDLER,
                MysqlMetadataManager.PARTITIONS_TABLE_HANDLER,
                MysqlMetadataManager.TRIGGERS_TABLE_HANDLER)
                .forEach(h -> {
                    int declared = h.getColumns().size();
                    h.scanAll().toList().blockingGet().forEach(r ->
                            assertTrue(r.length == declared,
                                    h.getTableName() + " 行宽 " + r.length + " != 声明列数 " + declared));
                });
    }
}
