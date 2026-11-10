/**
 * Copyright (C) <2021>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.datasource.jdbc.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * MYCAT CHANGE (I-DEBT-F7): 会话 sql_mode 向 JDBC 腿后端物理连接的借出时传导。
 *
 * <p>活体证据（audit/2026-11-08-capability-audit P-B-06）：前端会话 {@code SET sql_mode=''} 后
 * {@code SELECT @@sql_mode} 已返空（mycat 内存层已记，I-DEBT-54 D2b-A），但随后的截断 INSERT 在后端
 * 仍报严格模式错误 1406（{@code com.mysql.cj.jdbc.exceptions.MysqlDataTruncation}——Connector/J 异常类，
 * 证明走的是 JDBC 腿且该物理连接仍是服务器默认 STRICT_TRANS_TABLES）。断点层：JDBC 腿连接借出点
 * {@link JdbcConnectionManager#getConnection} 只从池里取物理连接，从不把会话 sql_mode 下发——
 * 会话态止步于 mycat 内存层。
 *
 * <p>传导机制（借出时一次，归还无需还原——池连接复用时下个借出者会再对齐）：
 * <ol>
 * <li><b>会话值载体</b>：执行器在"持有会话且借出同步可达"的窗口（{@code VertxExecuter.simpleUpdate}
 * 的 DML 下推借出、{@code AsyncMycatDataContextImpl.getConnection} 的 SELECT 下推借出）用
 * {@link #beginSessionBorrow(String)}/{@link #endSessionBorrow()} 携带会话 sql_mode 原始串。
 * 刻意不在语句入口全程携带——否则同线程的元数据借出（变量表/SPM/统计）会被误对齐。
 * 窗口外借出（心跳、元数据、原型服务）载体为 null，一律不动后端。</li>
 * <li><b>连接级标记</b>：按物理连接（identity 语义）记录"上次对齐的 sql_mode"，与本次会话值一致则
 * 不发 SET——避免每次 getConnection 无条件发 SET 的开销。物理连接被池回收/淘汰后标记随弱引用消失，
 * 新连接自然重新对齐。</li>
 * <li><b>值语义</b>：会话 {@code getSessionSqlMode()==null}（从未 SET）→ 不动后端（存量会话零变化）；
 * {@code ""} 是合法 sql_mode（清空全部模式）→ 必须对齐。捕获层（MySQLVariablesUtil）只保证剥引号，
 * 不改模式 token 内容。</li>
 * </ol>
 *
 * <p>native 腿（Vertx NetSocket 池）有同构缺口但无活体证据，本债不修（另见报告设计要点：
 * 优先 Vitess 式 per-query SET_VAR hint）。
 */
public final class SessionSqlModeAligner {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionSqlModeAligner.class);

    /**
     * 当前借出窗口携带的会话 sql_mode 原始串；null = 本线程借出不属于任何会话（心跳/元数据/原型服务）
     * 或会话从未 SET sql_mode——两种情况都不动后端。
     */
    private static final ThreadLocal<String> SESSION_BORROW_SQL_MODE = new ThreadLocal<>();

    /**
     * 连接级标记：该物理连接上次对齐到的 sql_mode。key 用 identity 语义的弱引用包装，
     * 连接被池淘汰后条目随 GC 消失，不泄漏、也不误匹配新物理连接。
     */
    private static final Map<ConnectionIdentity, String> LAST_ALIGNED =
            Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private SessionSqlModeAligner() {
    }

    /**
     * 借出窗口入口：执行器在持有会话且借出同步可达的窗口内携带会话 sql_mode。
     * 调用方必须在 finally 中配对 {@link #endSessionBorrow()}（borrow 同步发生在窗口内）。
     */
    public static void beginSessionBorrow(String sessionSqlMode) {
        SESSION_BORROW_SQL_MODE.set(sessionSqlMode);
    }

    /** 借出窗口出口：必须与 {@link #beginSessionBorrow(String)} 在 finally 中配对，防止线程复用串味。 */
    public static void endSessionBorrow() {
        SESSION_BORROW_SQL_MODE.remove();
    }

    /**
     * 连接借出点回调（{@code JdbcConnectionManager.getConnection} 物理连接到手后调用）：
     * 若当前处于会话借出窗口且该物理连接上次对齐值与会话 sql_mode 不一致，
     * 向该物理连接发 {@code SET sql_mode='<会话值>'} 并记录标记。
     *
     * @return true = 本次借出实际下发了 SET（测试/诊断用）
     * @throws SQLException SET 失败时原样上抛（借出失败，让语句拿到响亮的错误而非静默跑错模式）
     */
    public static boolean alignIfSessionBorrowed(Connection connection, boolean mysqlType) throws SQLException {
        if (connection == null || !mysqlType) {
            return false;
        }
        String sessionSqlMode = SESSION_BORROW_SQL_MODE.get();
        if (sessionSqlMode == null) {
            return false;
        }
        ConnectionIdentity key = new ConnectionIdentity(connection);
        if (Objects.equals(LAST_ALIGNED.get(key), sessionSqlMode)) {
            return false;
        }
        String sql = "SET sql_mode='" + escapeLiteral(sessionSqlMode) + "'";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
        LAST_ALIGNED.put(key, sessionSqlMode);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("I-DEBT-F7 aligned backend connection sql_mode: {}", sql);
        }
        return true;
    }

    /** 单引号字符串字面量安全转义（sql_mode 值来自客户端原串，不得逃出字面量）。 */
    static String escapeLiteral(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\'') {
                sb.append("''");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** identity 语义的弱引用 key：equals/hashCode 只看 referent 身份，包装本身被 WeakHashMap 弱持有。 */
    private static final class ConnectionIdentity extends WeakReference<Connection> {
        private final int identityHashCode;

        ConnectionIdentity(Connection referent) {
            super(referent);
            this.identityHashCode = System.identityHashCode(referent);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ConnectionIdentity)) {
                return false;
            }
            Connection mine = get();
            return mine != null && mine == ((ConnectionIdentity) obj).get();
        }

        @Override
        public int hashCode() {
            return identityHashCode;
        }
    }
}
