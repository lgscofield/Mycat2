package io.mycat;

import io.mycat.prototypeserver.mysql.VisualTableHandler;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-ME-3 · performance_schema 仿真完整性回归锁（进程内半区）。
 * <p>
 * 列面判据逐条锚定 <b>官网 mysql/mysql-server 标签 mysql-8.0.42 的
 * {@code storage/perfschema/table_*.cc} 源码</b>（Plugin_table m_table_def 的 Definition 字面量，非臆测；
 * 本地源码缓存 ps-*.cc/rgm8042.cc）：
 * <ul>
 * <li>{@code table_replication_group_members.cc}：8 列 = CHANNEL_NAME/MEMBER_ID/MEMBER_HOST(CHAR(255)
 * CHARACTER SET ASCII)/MEMBER_PORT/MEMBER_STATE/MEMBER_ROLE/MEMBER_VERSION/<b>MEMBER_COMMUNICATION_STACK</b>
 * ——现仿表缺末列且 MEMBER_HOST 宽度/字符集不符；</li>
 * <li>{@code table_global_variables.cc}/{@code table_session_variables.cc}：VARIABLE_NAME VARCHAR(64) NOT NULL +
 * VARIABLE_VALUE VARCHAR(1024)；</li>
 * <li>{@code table_global_status.cc}/{@code table_session_status.cc}：同上列面，数据源=官方 status 变量集；</li>
 * <li>{@code table_variables_info.cc}：VARIABLE_NAME/VARIABLE_SOURCE(10 值枚举)/VARIABLE_PATH/MIN_VALUE/MAX_VALUE/SET_TIME/SET_USER/SET_HOST；</li>
 * <li>{@code table_setup_consumers.cc}：NAME/ENABLED（+官方行集 COUNT_SETUP_CONSUMERS=16）。</li>
 * </ul>
 * 面消费方实证（非臆测需求）：
 * <ul>
 * <li>Mycat 自身活路径 {@code replica/.../MGRHeartBeatStrategy.CHECK_SQL}（三探针列：
 * replication_group_members.MEMBER_STATE、global_variables.VARIABLE_NAME/VARIABLE_VALUE、
 * replication_connection_status.RECEIVED_TRANSACTION_SET——connection_status 21 列已核对齐）；</li>
 * <li>mysqldump 不消费 p_s（I-ME-2 三引擎源码取证），故本格面 = 工具面变量/状态/复制探针。</li>
 * </ul>
 * 变量/状态行面与 information_schema 同源（MysqlVariableService/静态默认集），本锁钉"p_s 面不得恒空"。
 */
public class PerformanceSchemaRegressionTest {

    /** 从 PERFORMANCE_SCHEMA_INFO 的官方对齐 DDL 建 handler（与 addVisualTableIfAbsent 同构造路径）。 */
    private static VisualTableHandler psHandler(String table) {
        String ddl = MysqlMetadataManager.PERFORMANCE_SCHEMA_INFO.get(table);
        assertNotNullDdl(table, ddl);
        return VisualTableHandler.createByMySQL(ddl, () -> io.reactivex.rxjava3.core.Observable.empty());
    }

    private static void assertNotNullDdl(String table, String ddl) {
        assertTrue(ddl != null && !ddl.isEmpty(), "performance_schema 未定义表: " + table);
    }

    private static Set<String> columnsOf(VisualTableHandler handler) {
        return handler.getColumns().stream()
                .map(c -> c.getColumnName().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private static void assertColumnsExact(String table, VisualTableHandler handler, String... required) {
        Set<String> have = columnsOf(handler);
        Set<String> want = Arrays.stream(required).map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        assertEquals(want, have, "performance_schema." + table + " 列面与官网 mysql-8.0.42 源码不一致");
    }

    /** table_replication_group_members.cc（8.0.42）8 列全面；MEMBER_COMMUNICATION_STACK 现缺 → 本用例当前应为红。 */
    @Test
    public void replicationGroupMembersMatchesOfficialColumns() {
        assertColumnsExact("replication_group_members", psHandler("replication_group_members"),
                "CHANNEL_NAME", "MEMBER_ID", "MEMBER_HOST", "MEMBER_PORT",
                "MEMBER_STATE", "MEMBER_ROLE", "MEMBER_VERSION", "MEMBER_COMMUNICATION_STACK");
    }

    /** MGR 心跳探针消费列（MGRHeartBeatStrategy.CHECK_SQL 实证面）逐一在列。 */
    @Test
    public void mgrHeartbeatProbeColumnsPresent() {
        assertColumnsExact("replication_connection_status", psHandler("replication_connection_status"),
                "CHANNEL_NAME", "GROUP_NAME", "SOURCE_UUID", "THREAD_ID", "SERVICE_STATE",
                "COUNT_RECEIVED_HEARTBEATS", "LAST_HEARTBEAT_TIMESTAMP", "RECEIVED_TRANSACTION_SET",
                "LAST_ERROR_NUMBER", "LAST_ERROR_MESSAGE", "LAST_ERROR_TIMESTAMP",
                "LAST_QUEUED_TRANSACTION", "LAST_QUEUED_TRANSACTION_ORIGINAL_COMMIT_TIMESTAMP",
                "LAST_QUEUED_TRANSACTION_IMMEDIATE_COMMIT_TIMESTAMP",
                "LAST_QUEUED_TRANSACTION_START_QUEUE_TIMESTAMP",
                "LAST_QUEUED_TRANSACTION_END_QUEUE_TIMESTAMP",
                "QUEUEING_TRANSACTION", "QUEUEING_TRANSACTION_ORIGINAL_COMMIT_TIMESTAMP",
                "QUEUEING_TRANSACTION_IMMEDIATE_COMMIT_TIMESTAMP",
                "QUEUEING_TRANSACTION_START_QUEUE_TIMESTAMP");
    }

    /** table_{global,session}_variables.cc 列面（VARIABLE_NAME/VARIABLE_VALUE）。 */
    @Test
    public void variableTablesMatchOfficialColumns() {
        assertColumnsExact("global_variables", psHandler("global_variables"), "VARIABLE_NAME", "VARIABLE_VALUE");
        assertColumnsExact("session_variables", psHandler("session_variables"), "VARIABLE_NAME", "VARIABLE_VALUE");
    }

    /** table_{global,session}_status.cc 列面。 */
    @Test
    public void statusTablesMatchOfficialColumns() {
        assertColumnsExact("global_status", psHandler("global_status"), "VARIABLE_NAME", "VARIABLE_VALUE");
        assertColumnsExact("session_status", psHandler("session_status"), "VARIABLE_NAME", "VARIABLE_VALUE");
    }

    /** table_variables_info.cc 8 列面（含 10 值 VARIABLE_SOURCE 枚举）。 */
    @Test
    public void variablesInfoMatchesOfficialColumns() {
        assertColumnsExact("variables_info", psHandler("variables_info"),
                "VARIABLE_NAME", "VARIABLE_SOURCE", "VARIABLE_PATH", "MIN_VALUE",
                "MAX_VALUE", "SET_TIME", "SET_USER", "SET_HOST");
    }

    /** table_setup_consumers.cc 列面 NAME/ENABLED。 */
    @Test
    public void setupConsumersMatchesOfficialColumns() {
        assertColumnsExact("setup_consumers", psHandler("setup_consumers"), "NAME", "ENABLED");
    }

    /** 工具面行供应不得恒空：p_s session_variables/global_variables 与 i_s 同源（现恒 Observable.empty() → 本用例当前应为红）。 */
    @Test
    public void toolFacingTablesAreRowBackedByVariableService() {
        assertTrue(!MysqlMetadataManager.PS_SESSION_VARIABLES_TABLE_HANDLER.scanAll().toList().blockingGet().isEmpty(),
                "performance_schema.session_variables 应出变量行（与 i_s.SESSION_VARIABLES 同源）");
        // i_s 面同源锚：p_s 行集与 i_s handler 行集一致（同一供应器）
        assertEquals(MysqlMetadataManager.SESSION_VARIABLES_TABLE_HANDLER.scanAll().toList().blockingGet().size(),
                MysqlMetadataManager.PS_SESSION_VARIABLES_TABLE_HANDLER.scanAll().toList().blockingGet().size(),
                "p_s.session_variables 行数应与 i_s.SESSION_VARIABLES 同源一致");
    }

    /** setup_consumers 官方静态行集 COUNT_SETUP_CONSUMERS=16（table_setup_consumers.cc:43），非恒空。 */
    @Test
    public void setupConsumersHasOfficialRowSet() {
        long rows = MysqlMetadataManager.PS_SETUP_CONSUMERS_TABLE_HANDLER.scanAll().toList().blockingGet().size();
        assertEquals(16, rows, "performance_schema.setup_consumers 官方行集 = 16（COUNT_SETUP_CONSUMERS）");
    }
}
