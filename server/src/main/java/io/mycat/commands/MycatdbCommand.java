/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mycat.commands;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLCommentHint;
import com.alibaba.druid.sql.ast.SQLHint;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLNumericLiteralExpr;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLShowStatement;
import com.alibaba.druid.sql.ast.statement.SQLStartTransactionStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlShowStatement;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.alibaba.druid.sql.parser.SQLType;
import com.alibaba.druid.sql.visitor.SQLASTOutputVisitor;
import com.google.common.collect.ImmutableClassToInstanceMap;
import io.mycat.*;
import io.mycat.api.collector.MysqlPayloadObject;
import io.mycat.beans.mycat.MycatErrorCode;
import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.calcite.CodeExecuterContext;
import io.mycat.calcite.DrdsRunnerHelper;
import io.mycat.calcite.MycatHint;
import io.mycat.calcite.spm.*;
import io.mycat.monitor.LogEntryHolder;
import io.mycat.monitor.MycatSQLLogMonitor;
import io.mycat.prototypeserver.mysql.HackRouter;
import io.mycat.sqlhandler.SQLHandler;
import io.mycat.sqlhandler.SQLRequest;
import io.mycat.sqlhandler.ShardingSQLHandler;
import io.mycat.sqlhandler.dcl.*;
import io.mycat.sqlhandler.ddl.*;
import io.mycat.sqlhandler.dml.*;
import io.mycat.sqlhandler.dql.*;
import io.mycat.util.MySQLDatabaseMetadata;
import io.mycat.util.SqlTypeUtil;
import io.reactivex.rxjava3.core.Observable;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.JDBCType;
import java.util.*;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author Junwen Chen
 **/
public enum MycatdbCommand {
    /**/
    INSTANCE;
    final static Logger logger = LoggerFactory.getLogger(MycatdbCommand.class);
    static final ImmutableClassToInstanceMap<SQLHandler> sqlHandlerMap;

    static {
        ImmutableClassToInstanceMap.Builder<SQLHandler> builder = ImmutableClassToInstanceMap.builder();
        try {
            final HashSet<SQLHandler> sqlHandlers = new HashSet<>();
            sqlHandlers.add(new ShardingSQLHandler());
            sqlHandlers.add(new InsertSQLHandler());
            sqlHandlers.add(new DeleteSQLHandler());
            sqlHandlers.add(new UpdateSQLHandler());
            sqlHandlers.add(new TruncateSQLHandler());
            sqlHandlers.add(new ReplaceSQLHandler());
            sqlHandlers.add(new SetSQLHandler());
            sqlHandlers.add(new CommitSQLHandler());
            sqlHandlers.add(new KillSQLHandler());
            sqlHandlers.add(new RollbackSQLHandler());
            sqlHandlers.add(new SavepointSQLHandler());
            sqlHandlers.add(new ReleaseSavepointSQLHandler());
            sqlHandlers.add(new SetTransactionSQLHandler());
            sqlHandlers.add(new StartTransactionSQLHandler());
            sqlHandlers.add(HintHandler.INSTANCE);
            sqlHandlers.add(new UseSQLHandler());
            sqlHandlers.add(new LoadDataInFileSQLHandler());

            sqlHandlers.add(new AlterDatabaseSQLHandler());
            sqlHandlers.add(new AlterTableSQLHandler());
            sqlHandlers.add(new CreateDatabaseSQLHandler());
            sqlHandlers.add(new CreateIndexSQLHandler());
            sqlHandlers.add(new CreateTableSQLHandler());
            sqlHandlers.add(new CreateViewSQLHandler());
            sqlHandlers.add(new DropViewSQLHandler());
            sqlHandlers.add(new DropDatabaseSQLHandler());
            sqlHandlers.add(new DropTableSQLHandler());
            sqlHandlers.add(new DropViewSQLHandler());
            sqlHandlers.add(new RenameTableSQLHandler());
            sqlHandlers.add(new ShowCreateDatabaseHandler());
            //explain
            sqlHandlers.add(new ExplainSQLHandler());

            //show
            sqlHandlers.add(new ShowCharacterSetSQLHandler());
            sqlHandlers.add(new ShowCollationSQLHandler());
            sqlHandlers.add(new ShowColumnsSQLHandler());
            sqlHandlers.add(new ShowCreateTableSQLHandler());
            sqlHandlers.add(new ShowDatabasesHanlder());
            sqlHandlers.add(new ShowPluginsSQLHandler());

            sqlHandlers.add(new ShowEnginesSQLHandler());
            sqlHandlers.add(new ShowErrorsSQLHandler());
            sqlHandlers.add(new ShowIndexesSQLHandler());
            sqlHandlers.add(new ShowProcedureStatusSQLHandler());
            sqlHandlers.add(new ShowProcessListSQLHandler());
            sqlHandlers.add(new ShowStatusSQLHandler());
            sqlHandlers.add(new ShowTablesSQLHandler());
            sqlHandlers.add(new ShowTableStatusSQLHandler());
            sqlHandlers.add(new ShowTriggersSQLHandler());
            sqlHandlers.add(new ShowVariantsSQLHandler());
            sqlHandlers.add(new ShowWarningsSQLHandler());
            sqlHandlers.add(new ShowCreateFunctionHanlder());
            sqlHandlers.add(new CreateTableSQLHandler());
            sqlHandlers.add(new CreateSequenceHandler());
            sqlHandlers.add(new DropSequenceSQLHandler());
            sqlHandlers.add(new ShowFunctionStatusHandler());
            //Analyze
            sqlHandlers.add(new AnalyzeHanlder());
            sqlHandlers.add(new DropIndexSQLHandler());
            sqlHandlers.add(new MySQLCheckHandler());
            sqlHandlers.add(new ShowStatisticHandler());
            sqlHandlers.add(new MysqlShowDatabaseStatusHandler());
            //DDL
            sqlHandlers.add(new SQLDropFunctionHandler());
            sqlHandlers.add(new SQLCreateFunctionHandler());
            //Procedure
            sqlHandlers.add(new SQLCreateProcedureHandler());
            sqlHandlers.add(new SQLCallStatementHandler());
            sqlHandlers.add(new SQLDropProcedureHandler());
            sqlHandlers.add(new CreateUserHandler());
            for (SQLHandler sqlHandler : sqlHandlers) {
                Class statementClass = sqlHandler.getStatementClass();
                Objects.requireNonNull(statementClass);
                builder.put(statementClass, sqlHandler);
            }

        } catch (Throwable e) {
            logger.error("", e);
        }
        sqlHandlerMap = builder.build();
    }

    MycatdbCommand() {

    }

    public Future<Void> executeQuery(String text,
                                     MycatDataContext dataContext,
                                     IntFunction<Response> responseFactory) {
        try {
            if (logger.isDebugEnabled()) {
                logger.debug(text);
            }
            if (text.startsWith("BASELINE ")) {
                //baseline
                Response response = responseFactory.apply(1);
                return handleBaseline(text, dataContext, response);
            }
            if (text.startsWith("IN ")) {
                //IN
                Response response = responseFactory.apply(1);
                String schema = text.split(" ")[1];
                String normalize = SQLUtils.normalize(schema);
                dataContext.useShcema(normalize);
                return response.sendOk();
            }
            LinkedList<SQLStatement> statements = parse(text);
            if (statements.isEmpty()) {
                // 纯注释/无语句查询（如 mariadb-dump 下发的 /*M!100100 SET @@MAX_STATEMENT_TIME=0 */、
                // 或任意 /* ... */ 注释）：真 MySQL/MariaDB 对这类返回 Query OK，而非语法错误。
                // 此前误抛 1105 Illegal syntax 是「空语句表被判非法」的普遍兼容缺口（I-DEBT-57 真根，
                // 非 MariaDB 方言专面）。真·非法 SQL 已由 druid parse() 抛 ParserException 先行拦截
                //（走下方 catch），不会落到此分支，故此处放行 OK 不吞掉真实语法错误。
                Response response = responseFactory.apply(1);
                return response.sendOk();
            }
            Response response = responseFactory.apply(statements.size());
            Future<Void> future = Future.succeededFuture();
            for (SQLStatement sqlStatement : statements) {
                future = future.flatMap(new Function<Void, Future<Void>>() {
                    @Override
                    @SneakyThrows
                    public Future<Void> apply(Void unused) {
                        return execute(dataContext, response, sqlStatement);
                    }
                });
            }
            return future;
        } catch (Throwable e) {
            Response response = responseFactory.apply(1);
            if (isNavicatClientStatusQuery(text) || isReplicationStatusShow(text)) {
                // I-DEBT-53：改名后的复制状态 SHOW（druid 解不了）按原文透传后端
                return response.proxySelectToPrototype(text,
                        Collections.emptyList());
            }
            return Future.failedFuture(e);
        }
    }

    private Future<Void> handleBaseline(String text, MycatDataContext dataContext, Response response) {
        QueryPlanCache queryPlanCache = MetaClusterCurrent.wrapper(QueryPlanner.class).getPlanCache();
        if (text.startsWith("BASELINE ")) {
            text = text.substring("BASELINE".length()).trim();
            boolean fix = false;
            if (text.startsWith("ADD") || (fix = text.startsWith("FIX"))) {
                text = text.substring("ADD".length()).trim();

                SQLStatement sqlStatement = SQLUtils.parseSingleMysqlStatement(text);
                DrdsSqlWithParams drdsSqlWithParams = DrdsRunnerHelper.preParse(sqlStatement, dataContext.getDefaultSchema());
                PlanResultSet planResultSet = queryPlanCache.add(fix, drdsSqlWithParams);
                //BASELINE_ID | STATUS
                ResultSetBuilder builder = ResultSetBuilder.create();
                builder.addColumnInfo("BASELINE_ID", JDBCType.VARCHAR);
                builder.addColumnInfo("STATUS", JDBCType.VARCHAR);
                builder.addObjectRowPayload(Arrays.asList(String.valueOf(planResultSet.getBaselineId()), String.valueOf(planResultSet.isOk() ? "OK" : "ERROR")));
                return response.sendResultSet(builder.build());
            }
            if (text.startsWith("LIST") || text.equalsIgnoreCase("showAllPlans") || text.startsWith("ALL")) {
                ResultSetBuilder builder = ResultSetBuilder.create();
                builder.addColumnInfo("BASELINE_ID", JDBCType.VARCHAR)
                        .addColumnInfo("PARAMETERIZED_SQL", JDBCType.VARCHAR)
                        .addColumnInfo("PLAN_ID", JDBCType.VARCHAR)
                        .addColumnInfo("EXTERNALIZED_PLAN", JDBCType.VARCHAR)
                        .addColumnInfo("FIXED", JDBCType.VARCHAR)
                        .addColumnInfo("ACCEPTED", JDBCType.VARCHAR);
                for (Baseline baseline : queryPlanCache.list()) {
                    for (BaselinePlan baselinePlan : baseline.getPlanList()) {
                        String BASELINE_ID = String.valueOf(baselinePlan.getBaselineId());
                        String PARAMETERIZED_SQL = String.valueOf(baselinePlan.getSql());
                        String PLAN_ID = String.valueOf(baselinePlan.getId());
                        String EXTERNALIZED_PLAN = "";
                        try {
                            CodeExecuterContext attach = (CodeExecuterContext) baselinePlan.attach();
                            EXTERNALIZED_PLAN = new PlanImpl(attach.getMycatRel(), attach, Collections.emptyList()).dumpPlan();
                        } catch (Throwable throwable) {
                            logger.error("", throwable);
                        }
                        String FIXED = Optional.ofNullable(baseline.getFixPlan()).filter(i -> i.getId() == baselinePlan.getId())
                                .map(u -> "true").orElse("false");
                        String ACCEPTED = "true";

                        builder.addObjectRowPayload(Arrays.asList(BASELINE_ID, PARAMETERIZED_SQL, PLAN_ID, EXTERNALIZED_PLAN, FIXED, ACCEPTED));
                    }
                }
                return response.sendResultSet(() -> builder.build());
            }
            if (text.startsWith("LOAD_PLAN")) {
                text = text.substring("LOAD_PLAN".length()).trim();
                queryPlanCache.loadPlan(Long.parseLong(text));
                return response.sendOk();
            }
            if (text.startsWith("LOAD_ALL_BASELINES")) {
                queryPlanCache.loadBaselines();
                return response.sendOk();
            }
            if (text.startsWith("LOAD")) {
                text = text.substring("LOAD".length()).trim();
                queryPlanCache.loadBaseline(Long.parseLong(text));
                return response.sendOk();
            }
            if (text.startsWith("PERSIST_ALL_BASELINES")) {
                queryPlanCache.saveBaselines();
                return response.sendOk();
            }
            if (text.startsWith("PERSIST_PLAN")) {
                text = text.substring("PERSIST_PLAN".length()).trim();
                queryPlanCache.persistPlan(Long.parseLong(text));
                return response.sendOk();
            }
            if (text.startsWith("PERSIST")) {
                text = text.substring("PERSIST".length()).trim();
                queryPlanCache.persistBaseline(Long.parseLong(text));
                return response.sendOk();
            }
            if (text.startsWith("CLEAR_PLAN")) {
                text = text.substring("CLEAR_PLAN".length()).trim();
                queryPlanCache.clearPlan(Long.parseLong(text));
                return response.sendOk();
            }
            if (text.startsWith("CLEAR")) {
                text = text.substring("CLEAR".length()).trim();
                queryPlanCache.clearBaseline(Long.parseLong(text));
                return response.sendOk();
            }
            if (text.startsWith("DELETE_PLAN")) {
                text = text.substring("DELETE_PLAN".length()).trim();
                queryPlanCache.deletePlan(Long.parseLong(text));
                return response.sendOk();
            }
            if (text.startsWith("DELETE")) {
                text = text.substring("DELETE".length()).trim();
                queryPlanCache.deleteBaseline(Long.parseLong(text));
                return response.sendOk();
            }
            if (text.startsWith("UNFIX")) {
                text = text.substring("UNFIX".length()).trim();
                queryPlanCache.unFix(Long.parseLong(text));
                return response.sendOk();
            }
            if (text.startsWith("PARAMETERIZE")) {
                text = text.substring("PARAMETERIZE".length()).trim();
                DrdsSqlWithParams drdsSqlWithParams = DrdsRunnerHelper.preParse(text, dataContext.getDefaultSchema());
                String parameterizedSQL = drdsSqlWithParams.getParameterizedSQL();
                String info = drdsSqlWithParams.toString();
                ResultSetBuilder builder = ResultSetBuilder.create();
                builder.addColumnInfo("PARAMETERIZED_SQL", JDBCType.VARCHAR)
                        .addColumnInfo("INFO", JDBCType.VARCHAR);
                builder.addObjectRowPayload(Arrays.asList(parameterizedSQL, info));
                return response.sendResultSet(builder.build());
            }
        }
        return Future.failedFuture("unknown baseline cmd " + text);
    }

    private static boolean isNavicatClientStatusQuery(String text) {
        if (Objects.equals(
                "SELECT STATE AS `状态`, ROUND(SUM(DURATION),7) AS `期间`, CONCAT(ROUND(SUM(DURATION)/*100,3), '%') AS `百分比` FROM INFORMATION_SCHEMA.PROFILING WHERE QUERY_ID= GROUP BY STATE ORDER BY SEQ",
                text)) {
            return true;
        }
        if (Objects.equals("SHOW /*!50002 GLOBAL */ STATUS", text)) {
            return true;
        }
        return false;
    }

    /**
     * I-DEBT-53：识别 MySQL 8.4 改名后、druid 解析层即拒绝的复制状态 SHOW。
     * <p>MySQL 8.4 移除 {@code SHOW MASTER STATUS}（改 {@code SHOW BINARY LOG STATUS}）
     * 与 {@code SHOW SLAVE STATUS}（改 {@code SHOW REPLICA STATUS}）；两条新语法无 druid AST，
     * 解析抛 ParserException 冒泡至本类 catch。旧语法（MASTER/SLAVE）能解析、走
     * {@code instanceof MySqlShowStatement} 的 AST 透传分支，不经此处。为对 8.4+/9.0 后端
     * 无缝替代（现代 mysqldump 只发新语法），此处按文本识别并透传原文，
     * 不改 druid（依“不 fork druid、回归官方依赖”裁定）。
     */
    static boolean isReplicationStatusShow(String text) {
        return text != null && REPLICATION_STATUS_SHOW.matcher(text.trim()).matches();
    }

    private static final java.util.regex.Pattern REPLICATION_STATUS_SHOW = java.util.regex.Pattern
            .compile("(?i)^SHOW\\s+(?:BINARY\\s+LOG|REPLICA)\\s+STATUS(?:\\s+FOR\\s+CHANNEL\\s+'[^']*')?\\s*;?$");

    @NotNull
    private static Map<String, Object> getHintRoute(SQLStatement sqlStatement) {
        HashMap<String, Object> map = new HashMap<>();
        List<SQLHint> hints = new LinkedList<>();
        MySqlASTVisitorAdapter mySqlASTVisitorAdapter = new MySqlASTVisitorAdapter() {
            @Override
            public boolean visit(SQLSelect x) {
                hints.addAll(x.getHints());
                hints.addAll(x.getFirstQueryBlock().getHints());
                return false;
            }
        };

        sqlStatement.accept(mySqlASTVisitorAdapter);
        hints.addAll(Optional.ofNullable(sqlStatement.getHeadHintsDirect()).orElse(Collections.emptyList()));
        for (SQLHint hint : hints) {
            MycatHint mycatHint = null;
            if (hint instanceof SQLCommentHint) {
                String text = ((SQLCommentHint) hint).getText();
                mycatHint = new MycatHint(text);
            }
            if (mycatHint != null) {
                map.put("REP_BALANCE_TYPE", ReplicaBalanceType.NONE);
                for (MycatHint.Function function : mycatHint.getFunctions()) {
                    String name = function.getName();
                    switch (name.toUpperCase()) {
                        case "MERGE_UNION_SIZE": {
                            MycatHint.Argument argument = function.getArguments().get(0);
                            SQLNumericLiteralExpr value = (SQLNumericLiteralExpr) argument.getValue();
                            map.put("MERGE_UNION_SIZE", value.getNumber());
                        }
                        case "EXECUTE_TIMEOUT": {
                            MycatHint.Argument argument = function.getArguments().get(0);
                            SQLNumericLiteralExpr value = (SQLNumericLiteralExpr) argument.getValue();
                            long time = value.getNumber().longValue();//milliseconds毫秒
                            map.put("EXECUTE_TIMEOUT", time);
                            continue;
                        }
                        case "VECTOR": {
                            map.put("VECTOR", null);
                            continue;
                        }
                        case "MASTER": {
                            map.put("REP_BALANCE_TYPE", ReplicaBalanceType.MASTER);
                            continue;
                        }
                        case "SLAVE": {
                            map.put("REP_BALANCE_TYPE", ReplicaBalanceType.SLAVE);
                            continue;
                        }
                        case "INDEX": {
                            map.put("INDEX", function.getArguments().stream().map(i -> i.toString()).collect(Collectors.toList()));
                            continue;
                        }
//                        case "SCAN": {
//                            List<MycatHint.Argument> arguments = function.getArguments();
//                            Map<String, List<String>> collect = arguments.stream().collect(Collectors.groupingBy(m -> SQLUtils.toSQLString(m.getName()),
//                                    Collectors.mapping(v -> SQLUtils.toSQLString(v.getValue()), Collectors.toList())));
//                            NameMap<List<String>> nameMap = NameMap.immutableCopyOf(collect);
//                            List<String> condition = Optional.ofNullable(nameMap.get("CONDITION", false)).orElse(Collections.emptyList());
//                            List<String> logicalTables = Optional.ofNullable(nameMap.get("TABLE", false)).orElse(Collections.emptyList());
//                            List<String> physicalTables = Optional.ofNullable(nameMap.get("PHYSICAL_TABLE", false)).orElse(Collections.emptyList());
//                            List<String> targets = Optional.ofNullable(nameMap.get("TARGET", false)).orElse(Collections.emptyList());
//                            map.put("SCAN_TABLE", logicalTables);
//                            map.put("SCAN_DATANODE", physicalTables);
//                            map.put("SCAN_TARGET", targets.stream().flatMap(i-> Arrays.stream(i.split(",")).map(j->SQLUtils.normalize(j.toString()))).collect(Collectors.toList()));
//                            map.put("SCAN_CONDITION", condition);
//                            continue;
//                        }
                        case "DATANODE":
                        case "TARGET": {
                            List<MycatHint.Argument> arguments = function.getArguments();
                            map.put("TARGET", arguments.stream().map(i -> SQLUtils.toSQLString(i.getValue())).collect(Collectors.toList()));
                            continue;
                        }
                        case "SCHEMA": {
                            List<MycatHint.Argument> arguments = function.getArguments();
                            arguments.stream().map(i -> SQLUtils.toSQLString(i.getValue())).findFirst()
                                    .ifPresent(s -> map.put("SCHEMA", SQLUtils.normalize(s)));
                            continue;
                        }
                    }
                }
                return map;
            }
        }
        return map;
    }

    public static Future<Void> execute(MycatDataContext dataContext, Response receiver, SQLStatement sqlStatement) {


        String sql = sqlStatement.toString();
        SQLType sqlType = SQLParserUtils.getSQLType(sql, DbType.mysql);
        if (!dataContext.checkSQLType(sqlType,dataContext.getDefaultSchema(),sqlStatement)) {
            return receiver.sendError(String.format("%s is sqlType:%s,for the user %s, it is forbidden.", sqlStatement, sqlType, dataContext.getUser().getUserName()), MycatErrorCode.ERR_NOT_SUPPORT);
        }
        MycatSQLLogMonitor logMonitor = MetaClusterCurrent.wrapper(MycatSQLLogMonitor.class);
        //////////////////////////////////////////////////////////////////////////////////////
        dataContext.putProcessStateMap(Collections.emptyMap());
        sqlStatement.setAfterSemi(false);//remove semi
        boolean existSqlResultSetService = MetaClusterCurrent.exist(SqlResultSetService.class);
        //////////////////////////////////apply transaction///////////////////////////////////
        TransactionSession transactionSession = dataContext.getTransactionSession();
        Future future = transactionSession.openStatementState();
        LogEntryHolder logRecord = logMonitor.startRecord(dataContext, null, sqlType, sql);
        dataContext.setHolder(logRecord);
        future = future.flatMap(unused -> {
            try {
                String readyToCloseSQL = dataContext.getReadyToCloseSQL();
                if (readyToCloseSQL != null) {
                    if (readyToCloseSQL.equalsIgnoreCase(sql)) {
                        MycatServer mycatServer = MetaClusterCurrent.wrapper(MycatServer.class);
                        mycatServer.kill(Collections.singletonList(dataContext.getSessionId()));
                        return Future.succeededFuture();
                    }
                }
                //////////////////////////////////////////////////////////////////////////////////////
                if (existSqlResultSetService && !transactionSession.isInTransaction() && sqlStatement instanceof SQLSelectStatement) {
                    SqlResultSetService sqlResultSetService
                            = MetaClusterCurrent.wrapper(SqlResultSetService.class);
                    if (sqlResultSetService.isWorking()) {
                        Optional<Observable<MysqlPayloadObject>> baseIteratorOptional = sqlResultSetService.get((SQLSelectStatement) sqlStatement);
                        if (baseIteratorOptional.isPresent()) {
                            return receiver.sendResultSet(baseIteratorOptional.get());
                        }
                    }
                }
                Map<String, Object> hintRoute = getHintRoute(sqlStatement);
                if (!hintRoute.isEmpty()) {
                    dataContext.putProcessStateMap(hintRoute);
                    Object targetArray = hintRoute.getOrDefault("TARGET", null);
                    if (targetArray != null) {
                        String sqlText = sqlStatement.toString();
                        boolean select = !SqlTypeUtil.isDml(sqlType);
                        if (!(targetArray instanceof List)) {
                            targetArray = Collections.singletonList(targetArray.toString());
                        }
                        if (select) {
                            return receiver.proxySelect((List) targetArray, sqlText, Collections.emptyList());
                        }
                        return receiver.proxyUpdate((List) targetArray, sqlText, Collections.emptyList());
                    }
                }
                if (HackRouter.PUSH_SHOW && sqlStatement instanceof SQLShowStatement) {
                    return receiver.proxySelectToPrototype(sqlStatement.toString(), Collections.emptyList());
                }
                SQLRequest<SQLStatement> request = new SQLRequest<>(sqlStatement);

                Class aClass = sqlStatement.getClass();
                SQLHandler instance = sqlHandlerMap.getInstance(aClass);
                if (instance != null) {
                    return instance.execute(request, dataContext, receiver);
                } else {
                    if (sqlStatement instanceof MySqlShowStatement) {
                        logger.warn("ignore SQL prototype statement:{}", sqlStatement);
                        return receiver.proxySelectToPrototype(sqlStatement.toString(), Collections.emptyList());
                    } else {
                        logger.warn("ignore SQL statement:{}", sqlStatement);
                        return receiver.sendOk();
                    }
                }
            } catch (Throwable throwable) {
                logger.error("", throwable);
                return Future.failedFuture(throwable);
            }
        });

        future = future.onComplete((Handler<AsyncResult>) event -> {
            dataContext.setHolder(null);
            if (event.succeeded()) {
                logRecord.recordSQLEnd(true, Collections.emptyMap(), "");
            } else {
                String localizedMessage = Optional.ofNullable(event.cause()).map(i -> i.getLocalizedMessage()).orElse("");
                logRecord.recordSQLEnd(false, Collections.emptyMap(), localizedMessage);
            }
        });
        return future;
    }

    @NotNull
    public LinkedList<SQLStatement> parse(String text) {
        text = unwrapVersionComments(text).trim();
        LinkedList<SQLStatement> resStatementList = new LinkedList<>();
        if (text.startsWith("begin") || text.startsWith("BEGIN")) {
            SQLStartTransactionStatement sqlStartTransactionStatement = new SQLStartTransactionStatement();
            resStatementList.add(sqlStartTransactionStatement);
            text = text.substring("begin".length());
            text = text.trim();
            if (text.startsWith(";")) {
                text = text.substring(1);
            }
            text = text.trim();
            if (text.isEmpty()) {
                return resStatementList;
            }
        }
        return parseMySQLString(text, resStatementList);
    }

    /**
     * MYCAT CHANGE (I-DEBT-54 D2b): MySQL 服务端 lexer 对版本注释 {@code /*!NNNNNN body *&#47;}
     * 的语义是“自述版本达标则剥掉标记、把 body 当普通语句执行”；druid 把整条当纯注释解出
     * 空语句表，mysqldump 预处理首语句 {@code /*!40100 SET @@SQL_MODE='' *&#47;} 因此被
     * 空表→Query OK 分支静默吞掉（I-DEBT-57 现状），会话 sql_mode 未切宽松，后续
     * i_s.FILES tablespaces 形态又被本地严格分组校验拒绝（活体 mysqldump stderr 铁证）。
     * 本方法在 parse 前按真 MySQL 语义解包版本注释：版本号 MMSSDD 数值比较，未达标保留
     * 注释原文；MariaDB 方言 {@code /*M!} 与非版本纯注释不触碰（I-DEBT-57 路径不变）。
     */
    public String unwrapVersionComments(String text) {
        if (text.indexOf("/*!") < 0) {
            // 快速路径：无版本注释标记，零开销返回
            return text;
        }
        final int serverCode = serverVersionCode();
        final Matcher m = VERSION_COMMENT_PATTERN.matcher(text);
        final StringBuilder sb = new StringBuilder(text.length());
        while (m.find()) {
            final String replacement =
                    Integer.parseInt(m.group(1)) <= serverCode ? m.group(2) : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 版本注释：{@code /*!} 紧跟 1-6 位版本号（非 MariaDB 的 {@code M!} 字母前缀），非贪婪至首个 {@code *&#47;}。 */
    private static final Pattern VERSION_COMMENT_PATTERN =
            Pattern.compile("/\\*!(\\d{1,6})(.*?)\\*/", Pattern.DOTALL);

    /** 自述版本数值（MMSSDD）：经 DI 取 VersionProfile 画像串；画像未就绪时保守取 5.7.0 地板。 */
    private static int serverVersionCode() {
        io.mycat.config.VersionProfile profile = null;
        try {
            profile = MetaClusterCurrent.wrapper(io.mycat.config.VersionProfile.class);
        } catch (Throwable ignored) {
            // DI 未就绪（如单测环境）：落地板版本
        }
        final MySQLDatabaseMetadata meta = MySQLDatabaseMetadata.parse(
                profile != null ? profile.serverVersionString() : "5.7.0");
        return meta.majorVersion() * 10000 + meta.minorVersion() * 100 + meta.microVersion();
    }

    @NotNull
    private String convertSql(String text, DbType type) {
        SQLStatementParser parser = SQLParserUtils.createSQLStatementParser(text, type, true);
        List<SQLStatement> sqlStatements = parser.parseStatementList();
        StringBuilder out = new StringBuilder();
        SQLASTOutputVisitor outputVisitor = SQLUtils.createOutputVisitor(out, type);
        for (SQLStatement sqlStatement : sqlStatements) {
            sqlStatement.accept(outputVisitor);
        }
        return out.toString();
    }


    private LinkedList<SQLStatement> parseMySQLString(String text, LinkedList<SQLStatement> statementList) {
        SQLStatementParser parser = SQLParserUtils.createSQLStatementParser(text, DbType.mysql, true);
        parser.parseStatementList(statementList, -1, null);
        return statementList;
    }

}