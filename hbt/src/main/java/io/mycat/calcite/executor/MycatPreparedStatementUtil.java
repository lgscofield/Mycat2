package io.mycat.calcite.executor;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLCommentHint;
import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.ast.SQLOrderBy;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr;
import com.alibaba.druid.sql.ast.expr.SQLInSubQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntervalExpr;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.mysql.ast.expr.MySqlCharExpr;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlExportParameterVisitor;
import com.alibaba.druid.sql.visitor.VisitorFeature;
import com.google.common.collect.ImmutableList;
import io.mycat.DrdsSqlWithParams;
import io.mycat.MycatConnection;
import io.mycat.api.collector.RowBaseIterator;
import io.mycat.api.collector.RowIteratorCloseCallback;
import io.mycat.beans.mycat.JdbcRowBaseIterator;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.calcite.MycatHint;
import lombok.SneakyThrows;
import org.apache.calcite.sql.util.SqlString;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

public class MycatPreparedStatementUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(MycatPreparedStatementUtil.class);

//    public static void main(String[] args) {
//        SQLStatement sqlStatement = SQLUtils.parseSingleMysqlStatement("INSERT INTO `sharding` (`user_id`, `user_name`) VALUES \t('123', '323'),  \t('223', '323')");
//        StringBuilder sb = new StringBuilder();
//        ArrayList<Object> objects = new ArrayList<>();
//        outputToParameterized(sqlStatement, sb, new ArrayList<>(), objects);
//        System.out.println("objects = " + objects);
//    }

    /**
     * 将参数改为占位符(?), 同时将占位符替换过的参数, 添加到outputParameters数组中.
     * 例: set name = '123' where id = 10. 则 set name = ? where id = ?. 同时会向数组中添加 '123'和10.
     *
     * @param sqlStatement     语法树
     * @param sb               参数化后的字符串. 返回 set name = ? where id = ?
     * @param inputParameters  输入参数
     * @param outputParameters 输出被参数化的参数
     */
    public static void outputToParameterized(SQLStatement sqlStatement,
                                             String defaultSchema,
                                             StringBuilder sb,
                                             List<Object> inputParameters,
                                             List<Object> outputParameters,
                                             List<MycatHint> hints,
                                             MutableBoolean complex) {
        List<SQLCommentHint> headHintsDirect = sqlStatement.getHeadHintsDirect();
        if (headHintsDirect != null) {
            for (SQLCommentHint sqlCommentHint : headHintsDirect) {
                hints.add(new MycatHint(sqlCommentHint.getText()));
            }
        }

        MySqlExportParameterVisitor parameterVisitor = new MySqlExportParameterVisitor(outputParameters, sb, true) {
            // SQLCommentHint
//           @Override
//           public boolean visit(SQLCommentHint x) {
//          return true;
//           }
            HashSet<String> withEntities = new HashSet<>();

            boolean isWithTable(String tableName) {
                if (tableName == null) return false;
                return !withEntities.isEmpty() && withEntities.contains(SQLUtils.normalize(tableName));
            }

            @Override
            public boolean visit(SQLExprTableSource x) {
                if (x.getTableName() != null && !isWithTable(x.getTableName())) {
                    if (x.getSchema() == null) {
                        if (defaultSchema != null) {
                            x.setSchema("`" + SQLUtils.normalize(defaultSchema) + "`");
                        }
                    }
                }
                return super.visit(x);
            }

            @Override
            public boolean visit(MySqlSelectQueryBlock x) {
                for (SQLCommentHint hint : x.getHints()) {
                    hints.add(new MycatHint(hint.getText()));
                }

                return super.visit(x);
            }

            @Override
            public boolean visit(SQLInSubQueryExpr x) {
                complex.setValue(true);
                return super.visit(x);
            }

            @Override
            public boolean visit(SQLJoinTableSource x) {
                complex.setValue(true);
                return super.visit(x);
            }

            @Override
            public boolean visit(SQLSelectGroupByClause x) {
                complex.setValue(true);
                return super.visit(x);
            }

            @Override
            public boolean visit(SQLLimit x) {
                complex.setValue(true);
                return super.visit(x);
            }

            @Override
            public boolean visit(SQLOrderBy x) {
                complex.setValue(true);
                return super.visit(x);
            }

            @Override
            public boolean visit(SQLAggregateExpr x) {
                complex.setValue(true);
                return super.visit(x);
            }
//            @Override
//            public boolean visit(SQLOrderBy x) {
//                try {
//                    this.parameterized = false;
//                    return super.visit(x);
//                } finally {
//                    this.parameterized = true;
//                }
//            }
//
//            @Override
//            public boolean visit(SQLLimit x) {
//                try {
//                    this.parameterized = false;
//                    return super.visit(x);
//                } finally {
//                    this.parameterized = true;
//                }
//            }
//
//
//            @Override
//            public boolean visit(SQLSelectItem x) {
//                try {
//                    this.parameterized = false;
//                    return super.visit(x);
//                } finally {
//                    this.parameterized = true;
//                }
//            }


            @Override
            public boolean visit(SQLIntervalExpr x) {
                try {
                    this.parameterized = false;
                    return super.visit(x);
                } finally {
                    this.parameterized = true;
                }
            }

            // I-DEBT-54/D3: 带 charset introducer 的字符串字面量（mysqldump 的 _binary '原始字节' 形态）
            // 不得折叠为 ?+String 参数——restore 丢失 introducer 后，非法 UTF-8 载体经后端连接器
            // utf8 再编码变 U+FFFD，bit(8) 列写库 1406/沉默损坏。直接以纯 ASCII hex 形态烤进模板：
            // 字节级保真且不依赖任何连接器的编码行为（JDBC/原生协议两后端全兼容）。
            // 接入点：参数化渲染走双参 visit(ValuesClause, List)（MySqlOutputVisitor.printValuesList 直调，
            // 字节码实证），单参重载会被绕过，故两个重载都拦。
            @Override
            public boolean visit(SQLInsertStatement.ValuesClause x, List<Object> parameters) {
                if (!hasCharsetIntroducer(x)) {
                    // 直通父类双参渲染，不走 renderValuesClause：那里 super.visit(单参) 会委派回本双参形成递归
                    return super.visit(x, parameters);
                }
                // parameterizeOthers=true：非 introducer 值交回 visitor 正常参数化，保证 params 非空，
                // 避免 preParse 的 “params 空即回退 toString” 吞掉本行 hex 渲染。
                return renderValuesClauseWithIntroducer(x, appender, this, true);
            }

            @Override
            public boolean visit(SQLInsertStatement.ValuesClause x) {
                // 仅当 accept 派发路径命中：有 introducer 则自行渲染，否则返 true 继续默认遍历
                return renderValuesClause(x);
            }

            private boolean renderValuesClause(SQLInsertStatement.ValuesClause x) {
                if (!hasCharsetIntroducer(x)) {
                    return super.visit(x);
                }
                return renderValuesClauseWithIntroducer(x, appender, this, true);
            }

            @Override
            public boolean visit(SQLWithSubqueryClause x) {
                try {
                    for (SQLWithSubqueryClause.Entry entry : x.getEntries()) {
                        withEntities.add(SQLUtils.normalize(entry.getAlias()));
                    }
                    this.parameterized = false;
                    return super.visit(x);
                } finally {
                    this.parameterized = true;
                }
            }

        };
        parameterVisitor.setShardingSupport(false);
        // I-DEBT-44: 必须禁止 VALUES 列表合并。多行 VALUES 里不被参数化的 BIT 字面量 b'...'
        // 若允许合并去重，druid 会把首行的 bit 字面量烤进共享模板并丢弃其余行的不同 bit 值，
        // 分片 INSERT 写库即沉默数据损坏（第二行被折成首行的值）。与 INSERT proxy 路径保持一致。
        parameterVisitor.setFeatures(VisitorFeature.OutputParameterizedQuesUnMergeInList.mask |
                        VisitorFeature.OutputParameterizedQuesUnMergeAnd.mask |
                        VisitorFeature.OutputParameterizedUnMergeShardingTable.mask |
                        VisitorFeature.OutputParameterizedQuesUnMergeOr.mask
                        | VisitorFeature.OutputParameterizedQuesUnMergeValuesList.mask
                        | VisitorFeature.OutputParameterized.mask
        );
        if (inputParameters != null && !inputParameters.isEmpty()) {
            parameterVisitor.setInputParameters(inputParameters);
        } else {
            parameterVisitor.setInputParameters(null);
        }

        sqlStatement.accept(parameterVisitor);
    }

    /**
     * 将参数改为占位符(?), 同时将占位符替换过的参数, 添加到outputParameters数组中.
     * 例: set name = '123' where id = 10. 则 set name = ? where id = ?. 同时会向数组中添加 '123'和10.
     *
     * @param sqlStatement     语法树
     * @param sb               参数化后的字符串. 返回 set name = ? where id = ?
     * @param outputParameters 输出被参数化的参数
     */
    public static void outputToParameterized(SQLStatement sqlStatement, StringBuilder sb, List<Object> outputParameters) {
        MySqlExportParameterVisitor parameterVisitor = new MySqlExportParameterVisitor(outputParameters, sb, true) {

        };
        parameterVisitor.setShardingSupport(false);
        parameterVisitor.setInputParameters(Collections.emptyList());
        sqlStatement.accept(parameterVisitor);
    }

    public static DrdsSqlWithParams outputToParameterizedProxySql(MySqlInsertStatement sqlStatement) {
        StringBuilder sb = new StringBuilder();
        List<Object> outputParameters = new ArrayList<>();
        MySqlExportParameterVisitor parameterVisitor = new MySqlExportParameterVisitor(outputParameters, sb, true) {
            // I-DEBT-54/D3：proxy INSERT 通道同样不得折叠 charset-introducer 字面量（与上面主 visitor 同构）。
            // parameterizeOthers=false：本通道整行烤平进模板（outputToParameterizedProxySql 无 toString 回退），
            // 与回归测试 proxyInsertChannelAlsoEmitsHex（params=0）一致。
            @Override
            public boolean visit(SQLInsertStatement.ValuesClause x, List<Object> parameters) {
                if (!hasCharsetIntroducer(x)) {
                    return super.visit(x, parameters);
                }
                return renderValuesClauseWithIntroducer(x, appender, this, false);
            }

            @Override
            public boolean visit(SQLInsertStatement.ValuesClause x) {
                if (!hasCharsetIntroducer(x)) {
                    return true;
                }
                return renderValuesClauseWithIntroducer(x, appender, this, false);
            }
        };
        parameterVisitor.setShardingSupport(false);
        parameterVisitor.setFeatures(VisitorFeature.OutputParameterizedQuesUnMergeInList.mask |
                VisitorFeature.OutputParameterizedQuesUnMergeAnd.mask |
                VisitorFeature.OutputParameterizedUnMergeShardingTable.mask |
                VisitorFeature.OutputParameterizedQuesUnMergeOr.mask
                | VisitorFeature.OutputParameterizedQuesUnMergeValuesList.mask
                | VisitorFeature.OutputParameterized.mask
        );
        parameterVisitor.setInputParameters(Collections.emptyList());
        sqlStatement.accept(parameterVisitor);
        return new DrdsSqlWithParams(sb.toString(), outputParameters, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    /** I-DEBT-54/D3：VALUES 行内是否含带 charset introducer 的字符串字面量。 */
    static boolean hasCharsetIntroducer(SQLInsertStatement.ValuesClause x) {
        for (com.alibaba.druid.sql.ast.SQLExpr e : x.getValues()) {
            if (e instanceof MySqlCharExpr
                    && ((MySqlCharExpr) e).getCharset() != null
                    && !((MySqlCharExpr) e).getCharset().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * I-DEBT-54/D3：含 introducer 的 VALUES 行自行渲染：introducer 字面量输出纯 ASCII 十六进制字面量 {@code x'HEX'}。
     * <p>不加 {@code _charset} 前缀：druid MySQL 词法不接受 {@code _charset x'..'} 形态（proxy 通道会对生成的
     * SQL 再解析，实测报 {@code ParserException: token IDENTIFIER x}）；而裸 hex 字面量在 MySQL 语义中隐含
     * binary 字符集，与 {@code _binary} 等价且字节保真，对 bit/binary 列及任何后端连接器都无编码歧义。
     * <p>非 introducer 值：{@code parameterizeOthers=true} 时交回 visitor（{@code e.accept(visitor)}）走默认参数化
     * （输出 {@code ?} 并将参数加入 visitor 的参数列表），主 outputToParameterized 路径靠此保证 params 非空；
     * {@code =false}（proxy 通道）时字面量原样烤进模板、不参数化。
     */
    static boolean renderValuesClauseWithIntroducer(SQLInsertStatement.ValuesClause x,
                                                   StringBuilder appender,
                                                   com.alibaba.druid.sql.visitor.SQLASTVisitor visitor,
                                                   boolean parameterizeOthers) {
        appender.append("(");
        for (int i = 0; i < x.getValues().size(); i++) {
            if (i > 0) {
                appender.append(", ");
            }
            com.alibaba.druid.sql.ast.SQLExpr e = x.getValues().get(i);
            if (e instanceof MySqlCharExpr && hasIntroducerText((MySqlCharExpr) e)) {
                MySqlCharExpr c = (MySqlCharExpr) e;
                // druid 把字面量内的 MySQL 反斜杠转义存为原文（mysqldump 的 _binary '\0' 是两字符 \和0），
                // 必须按 MySQL 字符串字面量语义展开后再 encode，否则转义序列被当普通字节写入即沉默损坏。
                byte[] bytes = io.mycat.util.MySQLSqlTextCodec.encode(unescapeMySqlLiteral(c.getText()));
                StringBuilder hex = new StringBuilder(2 * bytes.length);
                for (byte b : bytes) {
                    hex.append(String.format("%02X", b));
                }
                // 裸 hex 字面量 x'HEX'：等价 _binary 且 druid/MySQL 全可解析，字节级保真
                appender.append("x'").append(hex).append("'");
            } else if (parameterizeOthers) {
                // 交回 visitor：非 introducer 值按默认规则参数化（? + 加入参数列表），保持行内 ? 与 params 同构
                e.accept(visitor);
            } else {
                appender.append(SQLUtils.toSQLString(e, com.alibaba.druid.DbType.mysql));
            }
        }
        appender.append(")");
        return false;
    }

    private static boolean hasIntroducerText(MySqlCharExpr c) {
        return c.getText() != null;
    }

    /** 按 MySQL 字符串字面量语义展开反斜杠转义（\0→NUL、\n、\t、\\、\' 等；未知转义保留后一字符）。 */
    static String unescapeMySqlLiteral(String text) {
        if (text.indexOf('\\') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != '\\' || i + 1 >= text.length()) {
                sb.append(ch);
                continue;
            }
            char n = text.charAt(++i);
            switch (n) {
                case '0':
                    sb.append('\0');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'Z':
                    sb.append((char) 26);
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '\'':
                    sb.append('\'');
                    break;
                case '"':
                    sb.append('"');
                    break;
                case '%':
                    sb.append("\\%");
                    break;
                case '_':
                    sb.append("\\_");
                    break;
                default:
                    sb.append(n);
                    break;
            }
        }
        return sb.toString();
    }

    public static ExecuteBatchInsert batchInsert(String sql, Group value, Connection connection, String targetName) {
        if (LOGGER.isDebugEnabled()) {
            for (List<Object> arg : value.getArgs()) {
                LOGGER.debug("batchInsert targetName:{} sql:{} parameters:{}", targetName, sql, arg);
            }
        }
        ExecuteBatchInsert executeBatchInsert = new ExecuteBatchInsert(sql, value, connection);
        return executeBatchInsert.invoke();
    }

    public static class ExecuteBatchInsert {
        private long lastInsertId;
        private long affected;
        private String sql;
        private Group value;
        private Connection connection;

        public ExecuteBatchInsert(String sql, Group value, Connection connection) {
            this.lastInsertId = 0;
            this.affected = 0;
            this.sql = sql;
            this.value = value;
            this.connection = connection;
        }

        public long getLastInsertId() {
            return lastInsertId;
        }

        public long getAffected() {
            return affected;
        }

        @SneakyThrows
        public ExecuteBatchInsert invoke() {
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                for (List<Object> objects : value.args) {
                    setParams(preparedStatement, objects);
                    preparedStatement.addBatch();
                }
                int[] ints = preparedStatement.executeBatch();
                for (int anInt : ints) {
                    affected += anInt;
                }
                ResultSet generatedKeys = preparedStatement.getGeneratedKeys();
                while (generatedKeys.next()) {
                    BigDecimal aLong = generatedKeys.getBigDecimal(1);
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("batch parameterizedSql:{} args:{} lastInsertId:{}", sql, value.args, aLong);
                    }
                    lastInsertId = aLong.longValue();
                }
            } catch (Exception e) {
                throw e;
            }
            return this;
        }
    }

    public static PreparedStatement setParams(PreparedStatement preparedStatement, List<Object> objects) throws SQLException {
        int index = 1;
        for (Object object : objects) {
            preparedStatement.setObject(index, object);
            index++;
        }
        return preparedStatement;
    }

    @SneakyThrows
    public static RowBaseIterator executeQuery(Connection mycatConnection,
                                               MycatConnection connection,
                                               MycatRowMetaData calciteRowMetaData,
                                               SqlString value,
                                               List<Object> params,
                                               RowIteratorCloseCallback closeCallback) {
        String sql = value.getSql();
        try {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("sql:{} {}", sql, (params).toString());
            }
            PreparedStatement preparedStatement = mycatConnection.prepareStatement(sql);
            ImmutableList<Integer> dynamicParameters = value.getDynamicParameters();
            List<Object> outoutParams = extractParams(params, dynamicParameters);
            MycatPreparedStatementUtil.setParams(preparedStatement, outoutParams);
            ResultSet resultSet = preparedStatement.executeQuery();
            return new JdbcRowBaseIterator(calciteRowMetaData, connection, preparedStatement, resultSet, closeCallback, sql);
        } catch (Throwable throwable) {
            LOGGER.error("sql:{} {}", sql, (params).toString(), throwable);
            throw throwable;
        }
    }

    public static List<Object> extractParams(List<Object> params, ImmutableList<Integer> dynamicParameters) {
        List<Object> outoutParams;
        if (dynamicParameters != null && !dynamicParameters.isEmpty()) {
            outoutParams = dynamicParameters.stream().map(i -> params.get(i)).collect(Collectors.toList());
        } else {
            outoutParams = Collections.emptyList();
        }
        return outoutParams;
    }

}