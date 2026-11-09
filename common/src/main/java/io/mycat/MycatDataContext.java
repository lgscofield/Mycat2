package io.mycat;

import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.parser.SQLType;
import io.mycat.beans.mycat.TransactionType;
import io.mycat.beans.mycat.WarningMessage;
import io.mycat.protocol.mysql.type.MySQLIsolation;
import io.mycat.protocol.mysql.type.MySQLServerStatusFlags;
import io.mycat.config.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public interface MycatDataContext extends Wrapper, SessionOpt {
    static final Logger LOGGER = LoggerFactory.getLogger(MycatDataContext.class);

    long getSessionId();

    @Override
    TransactionSession getTransactionSession();

    void switchTransaction(TransactionType transactionSessionType);

    <T> T getVariable(boolean global, String target);

    <T> T getVariable(MycatDataContextEnum name);

    void setVariable(MycatDataContextEnum name, Object value);

    default void setVariable(String target,
                             Object text) {
        MySQLVariablesUtil.setVariable(this, target, text);
    }

    // I-DEBT-54 D2b-A：会话 sql_mode 原始串（去引号后），供回读与向后端 diff-replay 传播。
    // 默认 no-op/null，兼容无会话态的桩实现（如 JdbcMycatDataContext）；活前端 MycatDataContextImpl 以字段覆盖。
    default String getSessionSqlMode() {
        return null;
    }

    default void setSessionSqlMode(String sqlMode) {
    }

    // I-DEBT-69 Blade C：@@warning_count 读的是"上一条语句"结束时的诊断区条数（活体真库：
    // SELECT 1/0; SELECT @@warning_count → 1，而本语句产生的条件不参与本次读取），
    // 故 clearWarnings() 在语句入口须把旧条数归档到这里，再由 @@warning_count 读归档值。
    default int getPreviousWarningCount() {
        return 0;
    }

    // I-DEBT-69 Blade D 余项：@@error_count 读上一条语句结束时的 Error 级条件数（活体真库：
    // 纯告警语句后 @@error_count=0，含 Error 的语句后为 Error 条数）。与 @@warning_count（全量）
    // 解耦，故 clearWarnings() 归档时须按 Level 分别计。
    default int getPreviousErrorCount() {
        return 0;
    }

    // I-DEBT-69 Blade C：会话级 max_error_count 覆盖值（null = 未 SET，回落变量服务默认值）。
    default Integer getSessionMaxErrorCount() {
        return null;
    }

    default void setSessionMaxErrorCount(Integer maxErrorCount) {
    }

    default int serverStatus() {
        MySQLServerStatusFlags.Builder builder = MySQLServerStatusFlags.builder();
        if (isAutocommit()) {
            builder.setAutoCommit();
        }
        if (isInTransaction()) {
            builder.setInTransaction();
        }
        if (isReadOnly()) {
            builder.setInTransReadonly();
        }
        return builder.build();
    }

    public boolean isAutocommit();

    public void setAutoCommit(boolean autoCommit);

    public MySQLIsolation getIsolation();

    public void setIsolation(MySQLIsolation isolation);

    public boolean isInTransaction();

    public void setInTransaction(boolean inTransaction);

    public MycatUser getUser();

    void setUser(MycatUser user);

    void useShcema(String schema);

    String getDefaultSchema();

    int getServerCapabilities();

    void setServerCapabilities(int serverCapabilities);

    /**
     * I-DEBT-70 刀 1：前端握手 CLIENT_CONNECT_ATTRS 段携带的连接属性（{@code _client_name} 等）。
     *
     * <p>未宣告该能力位、或客户端未下发的会话恒为空表（default 实现即今日行为，零回归）。
     * 值语义按真库实测（E5/E8）：键 {@code _client_name} 与 {@code "mysql-connector-net"} 为
     * 大小写敏感的全等比较，MariaDB 服务端据此决定是否种 {@code NO_NULL_COLLATION_IDS}。
     */
    default Map<String, String> getClientConnectAttrs() {
        return java.util.Collections.emptyMap();
    }

    default void setClientConnectAttrs(Map<String, String> clientConnectAttrs) {
    }

    int getWarningCount();

    /**
     * I-DEBT-69 会话告警缓冲：追加一条诊断条件。
     *
     * <p>语义按 2026-10-29 活体真库探针（MySQL 8.0 / 5.7 / MariaDB 10.11 一致）钉死：
     * 诊断区持“最近一条语句”的条件，同语句内多条按产生顺序累积，下一条语句入口清空；
     * 缓冲存全量，{@code max_error_count} 只截断列表（读面行为），不截断计数。
     */
    void addWarning(WarningMessage warning);

    /**
     * 本语句已产生的诊断条件，按产生顺序；返回不可变视图，读面（SHOW WARNINGS）据此出行集。
     */
    List<WarningMessage> getWarnings();

    /**
     * 清空诊断条件缓冲。必须在每条客户端语句入口调用（等价真库“替换诊断区”），
     * 但读回型诊断语句（SHOW WARNINGS 等）自身不得清空，否则读完即失。
     */
    void clearWarnings();

    /**
     * Blade F（I-DEBT-69）：语句失败回 ERR_Packet 时追加一条 Error 级诊断条件。
     *
     * <p>活体真值（2026-11-01 双档探针 C1/C2/D0）：凡语句级失败必进诊断区（Error 级，
     * wc/ec 各计入），与 ERR 包同源同码；message 为 null 时兜底空串（WarningMessage 对
     * message requireNonNull）。
     *
     * <p>MYCAT CHANGE (I-DEBT-94/F4)：会话告警缓冲写入点过滤内部解析伪影错误——
     * {@code CalciteContextException: Column '$$' not found in any table} 一类。活体出处
     * （audit/2026-11-08-capability-audit/logs/E0-05-mycat-boot2.log 16:16:18）：客户端连接探针
     * {@code select $$}（裸 {@code $$} 被 Calcite 校验器当列名）失败后，同会话首条
     * {@code SHOW WARNINGS} 即吐出该 Java 异常文本行（真库直连为空）。真库不会把内部 Java 异常
     * 灌进诊断区，故本类消息不入缓冲；过滤在 recordStatementError 单点收口（server 的
     * VertxSessionImpl 与 proxy 的 MySQLProxyServerSession 两条 ERR 出口的唯一汇点），一处覆盖
     * SHOW WARNINGS / SHOW ERRORS / @@warning_count / @@error_count 全部读腿与计数。ERR 包本身
     * 照发（语句对客户端的失败应答不受影响）；真用户错误（重复键、未知列等）仍照常入诊断区。
     */
    default void recordStatementError(int errorCode, String message) {
        // 活体 C1：失败语句的 Error 与 ERR 包同源同码；null 消息兜底空串，错误出口自身不得反抛 NPE。
        if (isInternalCalciteDollarArtifactError(message)) {
            return;
        }
        addWarning(new WarningMessage("Error", errorCode, message == null ? "" : message));
    }

    /**
     * I-DEBT-94/F4：判 message 是否为内部解析伪影类错误——Calcite 校验器把客户端探针的裸
     * {@code $$} 标识符当列名的失败（列 '$$' not found）。两类生产形态都须命中：
     * recover 腿的 {@code cause.toString()} 单行与外层 catch 的堆栈串（两者首行/cause 链
     * 均含 CalciteContextException 类名与 {@code Column '$$' not found in any table} 判词）。
     * 非伪影（普通用户列名）不命中——过滤面只钉死 {@code $$}，不得扩大到全部
     * CalciteContextException。
     */
    static boolean isInternalCalciteDollarArtifactError(String message) {
        return message != null
                && message.contains("org.apache.calcite.runtime.CalciteContextException")
                && message.contains("Column '$$' not found in any table");
    }

    long getLastInsertId();

    void setLastInsertId(long s);

    Charset getCharset();

    int getCharsetIndex();

    int getLastErrorCode();

    long getAffectedRows();

    void setAffectedRows(long affectedRows);

    String getLastMessage();

    void setLastMessage(String lastMessage);

    void setCharset(int index, String charsetName, Charset defaultCharset);

    AtomicBoolean getCancelFlag();

    default boolean isRunning() {
        return !getCancelFlag().get();
    }

    boolean isReadOnly();

    void close();

    void kill();

    //need catch exception
//    void block(Runnable runnable);

    public String resolveDatasourceTargetName(String targetName);

    public String resolveDatasourceTargetName(String targetName, boolean master);

    Map<Long, PreparedStatement> getPrepareInfo();

    default String setLastMessage(Throwable e) {
        LOGGER.error("", e);
        String string = getThrowableString(e);
        setLastMessage(string);
        return string;
    }

    static String getThrowableString(Throwable e) {
        StringWriter errors = new StringWriter();
        e.printStackTrace(new PrintWriter(errors));
        //去掉重复提示的消息 return MessageFormat.format("{0} \n {1}",e,errors.toString());
        return errors.toString();
    }

    public long nextPrepareStatementId();

    void setCharsetIndex(int characterSet);

    void setLastErrorCode(int errorCode);

    public Map<String, Object> getProcessStateMap();

    public void putProcessStateMap(Map<String, Object> map);

    /**
     * I-DEBT-47：前端 TLS 会话协商到的 cipher/version。属会话生命周期事实，必须独立于
     * {@code MycatdbCommand.execute} 每语句重置的 {@code processStateMap}（否则 SHOW STATUS 执行期永远读到空）。
     * 默认无前端 TLS 信息（非 TLS/后端 JDBC 上下文回报空串，与 MySQL 语义一致）。
     */
    default void setSslSessionInfo(String cipher, String version) {
    }

    default String getSslCipher() {
        return "";
    }

    default String getSslVersion() {
        return "";
    }

    default public int getMergeUnionSize() {
        return ((Number) this.getProcessStateMap().getOrDefault("MERGE_UNION_SIZE", MetaClusterCurrent.exist(ServerConfig.class) ? MetaClusterCurrent.wrapper(ServerConfig.class).getMergeUnionSize() : -1)).intValue();
    }

    boolean isDebug();

    public void setDebug(boolean value);

    boolean isVector();

    public void setVector(boolean value);

    public Integer getLock(String name, long time);

    public Integer releaseLock(String name);

    public Integer isFreeLock(String name);

    void setHolder(Object holder);

    Object getHolder();

    public void setReadyToCloseSQL(String sql);

    public String getReadyToCloseSQL();

    public default boolean checkSQLType(SQLType sqlType, String defaultSchema, SQLStatement sqlStatement) {
        return true;
    }


}