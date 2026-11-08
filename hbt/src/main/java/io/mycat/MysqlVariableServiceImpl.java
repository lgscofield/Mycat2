package io.mycat;

import io.mycat.api.collector.RowBaseIterator;
import io.mycat.calcite.sqlfunction.infofunction.MycatVersionFunction;
import io.mycat.datasource.jdbc.datasource.DefaultConnection;
import io.mycat.datasource.jdbc.datasource.JdbcConnectionManager;
import io.mycat.replica.ReplicaSelectorManager;
import io.mycat.protocol.codec.NameMap;
import io.mycat.util.MySQLDatabaseMetadata;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static io.mycat.MySQLVariablesUtil.toInt;

public class MysqlVariableServiceImpl implements MysqlVariableService {

    //    public final SchemaRepository TABLE_REPOSITORY = new SchemaRepository(DbType.mysql);
    private final NameMap<Object> globalVariables;
    private final NameMap<Object> sessionVariables;

    public MysqlVariableServiceImpl(JdbcConnectionManager jdbcConnectionManager) {

        this.globalVariables = new NameMap<Object>();
        this.sessionVariables = new NameMap<>();

        String prototype = MetadataManager.getPrototype();

        if(MetaClusterCurrent.exist(ReplicaSelectorManager.class)){
            ReplicaSelectorManager replicaSelectorManager = MetaClusterCurrent.wrapper(ReplicaSelectorManager.class);
            prototype = replicaSelectorManager.getDatasourceNameByReplicaName(prototype, true, null);
        }

        if(jdbcConnectionManager.getConfigAsMap().containsKey(prototype)){
            try (DefaultConnection connection = jdbcConnectionManager.getConnection(prototype)) {
                try (RowBaseIterator rowBaseIterator = connection.executeQuery(" SHOW GLOBAL VARIABLES;")) {
                    while (rowBaseIterator.next()) {
                        globalVariables.put(
                                rowBaseIterator.getString(0),
                                rowBaseIterator.getObject(1)
                        );
                    }
                }
                try (RowBaseIterator rowBaseIterator = connection.executeQuery(" SHOW SESSION VARIABLES;")) {
                    while (rowBaseIterator.next()) {
                        sessionVariables.put(
                                rowBaseIterator.getString(0),
                                rowBaseIterator.getObject(1)
                        );
                    }
                }
            }
        }else {
            globalVariables.put("character_set_client", "utf8");
            globalVariables.put("character_set_connection", "utf8");
            globalVariables.put("character_set_results", "utf8");
            globalVariables.put("character_set_server", "utf8");
            globalVariables.put("init_connect", "");
            globalVariables.put("interactive_timeout", "172800");
            globalVariables.put("lower_case_table_names", "1");
            globalVariables.put("max_allowed_packet", "16777216");
            globalVariables.put("net_buffer_length", "8192");
            globalVariables.put("net_write_timeout", "60");
            globalVariables.put("query_cache_size", "0");
            globalVariables.put("query_cache_type", "OFF");
            globalVariables.put("sql_mode", "STRICT_TRANS_TABLES");
            globalVariables.put("system_time_zone", "CST");
            globalVariables.put("time_zone", "SYSTEM");
            globalVariables.put("lower_case_table_names", "1");
            globalVariables.put("wait_timeout", "172800");
            // I-DEBT-86 刀B扩展：补齐 Connector/J 引导查询 22 变量在净室缺省表的缺口
            // （拉取路径失效时这些键原缺 → 引导变量返 null）
            globalVariables.put("auto_increment_increment", "1");
            globalVariables.put("collation_server", "utf8_general_ci");
            globalVariables.put("collation_connection", "utf8_general_ci");
            globalVariables.put("license", "GPL");
            globalVariables.put("performance_schema", "1");


            ////////////////////////////////////session/////////////////////////////////////////////////////////////////
            sessionVariables.put("autocommit","1");

            sessionVariables.put("sql_mode","STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION");

            sessionVariables.put("time_zone","SYSTEM");

            sessionVariables.put("transaction_isolation","REPEATABLE-READ");
            sessionVariables.put("tx_isolation","REPEATABLE-READ");

            sessionVariables.put("tx_read_only","0");
            sessionVariables.put("transaction_read_only","0");


            sessionVariables.put("max_execution_time","0");
            // I-DEBT-86 刀B：session 默认表补 max_allowed_packet——净室启动（mycat-home 缺 clusters/，
            // prototype 拉取路径失效）时 Connector/J 引导查询消费 `@@session.max_allowed_packet`，
            // 缺行 → null → toInt 崩（活体：audit P-L1-01 全 RED）。值取同类 global 缺省(:63) 16777216。
            sessionVariables.put("max_allowed_packet","16777216");
            sessionVariables.put("innodb_lock_wait_timeout","50");
            sessionVariables.put("interactive_timeout","28800");

            // I-DEBT-69 Blade C：兜底表原不带 max_error_count，SHOW SESSION VARIABLES 缺行、
            // SELECT @@max_error_count 回 NULL；按 persona 补真库默认值（下方循环同步到 global）。
            sessionVariables.put("max_error_count", defaultMaxErrorCount());


            /////////////////////////////////////default////////////////////////////////////////////////////////////////
            for (Map.Entry<String, Object> entry : sessionVariables.entrySet()) {
                globalVariables.put(entry.getKey(),entry.getValue());
            }
            // I-DEBT-86 刀B扩展：净室下 session 继承 global 缺省（真库语义：会话建立时自 global 继承），
            // 补齐 Connector/J 引导查询 22 变量的可读性（修复前 ~16 个返 null）
            seedSessionFromGlobal(globalVariables, sessionVariables);
        }

        // I-DEBT-29-EXT：version 键必须与握手 serverVersion / VERSION()（MycatVersionFunction）同源，
        // 按 persona 宣称归一。后端拉表/模板都不含该归一：真值经 @@version 泄露后端版本
        // （如 persona=MYSQL_5_7 却报 8.0.46），GUI 客户端读 @@version 判方言即踩空，
        // 三宣称（握手/VERSION()/@@version）自相矛盾。单一权威取自 MycatVersionFunction.version()。
        String personaVersion = MycatVersionFunction.version();
        globalVariables.put("version", personaVersion);
        sessionVariables.put("version", personaVersion);
    }

    /**
     * I-DEBT-69 Blade C：按 persona 宣称给出 {@code max_error_count} 真库默认值。
     * 活体钉死（2026-10-31）：MySQL 5.7 → 64；MySQL 8.0/8.4/9.0 → 1024；MariaDB 10.6/10.11/11.4 → 64。
     */
    private static String defaultMaxErrorCount() {
        MySQLDatabaseMetadata metadata = MySQLDatabaseMetadata.parse(MycatVersionFunction.version());
        if ("MariaDB".equals(metadata.productName())) {
            return "64";
        }
        return metadata.majorVersion() >= 8 ? "1024" : "64";
    }

    /**
     * I-DEBT-86 刀A：整型族会话变量的 null 设防（包级 static 供回归锁直接调用，对齐 I-DEBT-56 先例）。
     * <p>净室启动（mycat-home 缺 {@code clusters/}，prototype 拉取路径失效）时 session 默认表可能缺
     * Connector/J 引导查询消费的整型变量，{@code null} 进 {@link MySQLVariablesUtil#toInt} →
     * {@code parseInt("null")} → NumberFormatException → 任何 JDBC 客户端无法连接。
     * <p>窄面原则：只对已知整型族变量按内置 global 默认表回落；非整型族 null 仍原样透传（不扩大设防）。
     * <p>有意不采纳 spec §3-C（toInt 全局 null 设防）：toInt 亦被 autocommit/read_only 解析消费，
     * 全局 null→0 属语义变更，风险面大（见 DEBT-QUEUE I-DEBT-86 行）。
     */
    static Object fixIntLikeVariable(String name, Object o) {
        if (o == null) {
            if (name.equalsIgnoreCase("max_allowed_packet")) {
                return 16777216;
            }
            if (name.equalsIgnoreCase("innodb_file_per_table")) {
                return 1;
            }
            if (name.equalsIgnoreCase("FOREIGN_KEY_CHECKS")) {
                return 1;
            }
            return null;
        }
        if (name.equalsIgnoreCase("innodb_file_per_table")) {
            return toInt(o);
        }
        if (name.equalsIgnoreCase("FOREIGN_KEY_CHECKS")) {
            return toInt(o);
        }
        if (name.equalsIgnoreCase("max_allowed_packet")) {
            return toInt(o);
        }
        return o;
    }

    @Override
    public Object getGlobalVariable(String name) {
        name = name.startsWith("@@") ? name.substring(2) : name;
        Object o = globalVariables.get(name, false);
        return fixValue(name, o);
    }

    @Nullable
    private Object fixValue(String name, Object o) {
        return fixIntLikeVariable(name, o);
    }

    /**
     * I-DEBT-86 刀B扩展：session 继承 global（不覆写已有 session 值）。包级 static 供回归锁直接调用
     * （对齐 I-DEBT-56 先例）。键面 = Connector/J 引导查询消费的 22 变量（{@link #CLEANROOM_SESSION_INHERIT_KEYS}）。
     */
    static void seedSessionFromGlobal(NameMap<Object> global, NameMap<Object> session) {
        for (String k : CLEANROOM_SESSION_INHERIT_KEYS) {
            Object v = global.get(k, false);
            if (v != null && session.get(k, false) == null) {
                session.put(k, v);
            }
        }
    }

    /**
     * I-DEBT-86：Connector/J 9.x 连接引导查询（{@code SELECT @@session.auto_increment_increment, ...}）
     * 消费的变量面，即净室配置下必须可读的最小集合。
     */
    static final String[] CLEANROOM_SESSION_INHERIT_KEYS = {
            "auto_increment_increment", "character_set_client", "character_set_connection",
            "character_set_results", "character_set_server", "collation_server", "collation_connection",
            "init_connect", "interactive_timeout", "license", "lower_case_table_names",
            "max_allowed_packet", "net_write_timeout", "performance_schema", "query_cache_size",
            "query_cache_type", "sql_mode", "system_time_zone", "time_zone",
            "transaction_isolation", "wait_timeout"
    };

    @Override
    public Object getSessionVariable(String name) {
        // I-DEBT-69 Blade C：生产读腿会传 "@@session.max_error_count"（ShowWarningsSQLHandler），
        // NameMap 只做大小写不敏感、不剥前缀，故与 getGlobalVariable 对齐剥去 @@ / session. / local.。
        name = normalizeSessionName(name);
        return fixValue(name, sessionVariables.get(name, false));
    }

    private static String normalizeSessionName(String name) {
        if (name == null) {
            return null;
        }
        String t = name;
        while (t.startsWith("@@")) {
            t = t.substring(2);
        }
        String lower = t.toLowerCase();
        if (lower.startsWith("session.")) {
            return t.substring("session.".length());
        }
        if (lower.startsWith("local.")) {
            return t.substring("local.".length());
        }
        return t;
    }

    @Override
    public List<Object[]> getGlobalVariables() {
        LinkedList<Object[]> variables = new LinkedList<>();
        for (Map.Entry<String, Object> e : globalVariables.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            variables.add(new Object[]{key,value});
        }
        return variables;
    }

    @Override
    public List<Object[]> getSessionVariables() {
        LinkedList<Object[]> variables = new LinkedList<>();
        for (Map.Entry<String, Object> e : sessionVariables.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            variables.add(new Object[]{key,value});
        }

        return variables;
    }
}
