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


            ////////////////////////////////////session/////////////////////////////////////////////////////////////////
            sessionVariables.put("autocommit","1");

            sessionVariables.put("sql_mode","STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION");

            sessionVariables.put("time_zone","SYSTEM");

            sessionVariables.put("transaction_isolation","REPEATABLE-READ");
            sessionVariables.put("tx_isolation","REPEATABLE-READ");

            sessionVariables.put("tx_read_only","0");
            sessionVariables.put("transaction_read_only","0");


            sessionVariables.put("max_execution_time","0");
            sessionVariables.put("innodb_lock_wait_timeout","50");
            sessionVariables.put("interactive_timeout","28800");

            // I-DEBT-69 Blade C：兜底表原不带 max_error_count，SHOW SESSION VARIABLES 缺行、
            // SELECT @@max_error_count 回 NULL；按 persona 补真库默认值（下方循环同步到 global）。
            sessionVariables.put("max_error_count", defaultMaxErrorCount());


            /////////////////////////////////////default////////////////////////////////////////////////////////////////
            for (Map.Entry<String, Object> entry : sessionVariables.entrySet()) {
                globalVariables.put(entry.getKey(),entry.getValue());
            }
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

    @Override
    public Object getGlobalVariable(String name) {
        name = name.startsWith("@@") ? name.substring(2) : name;
        Object o = globalVariables.get(name, false);
        return fixValue(name, o);
    }

    @Nullable
    private Object fixValue(String name, Object o) {
        if (name.equalsIgnoreCase("innodb_file_per_table")){
            return toInt(o);
        }
        if (name.equalsIgnoreCase("FOREIGN_KEY_CHECKS")){
            return toInt(o);
        }
        if (name.equalsIgnoreCase("max_allowed_packet")){
            return toInt(o);
        }
        return o;
    }

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
