package io.mycat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.mycat.calcite.DrdsRunnerHelper;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.schema.SchemaPlus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * I-DEBT-94 F13：解析失败路径日志伪影——{@code DrdsSqlCompiler.getRelRoot} 的失败现场日志
 * {@code sqlStatement:SELECT $$ AS $$} 会把读者引向"内部占位 {@code $$} 被当真实 SQL 重解析"的误判。
 *
 * <p>活体取证（audit/2026-11-08-capability-audit/logs/E0-05-mycat-boot2.log:21212 等）钉死的事实链：
 * 客户端连接探针原样下发 {@code select $$}（{@code MycatdbCommand.executeQuery:184} 的 DEBUG 行可证），
 * druid 解析为裸标识符 {@code $$}，{@code MycatCalciteMySqlNodeVisitor} 的 select 项隐式别名逻辑
 * （selectItem.expr 为裸 {@link com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr} 时
 * {@code setAlias(同名)}）重渲染出 {@code SELECT $$ AS $$}，Calcite 校验器遂抛
 * {@code Column '$$' not found in any table}。仓库内不存在任何内部 {@code select $$} 生产点
 * （全源 grep 仅 hbt RelNodeConvertor 的 HBT explain 前缀 {@code "$$0"}，与 SQL 文本无关）。
 *
 * <p>修复（最小面）：失败现场日志补记 {@code getParameterizedSQL()} 的客户端原文于行首
 * （{@code sql:select $$}），现场自释——原始语句、druid 重渲染、Calcite sqlNode 三层同屏，
 * 伪影不再误导。本测试以空库会话完整复刻活体失败链（空 schema 下 {@code select $$} 校验必败，
 * 与全新会话探针失败同构），从 logback 捕获真实 {@code log.error} 输出做锁。
 */
public class CompileFailureLogRawSqlTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(DrdsSqlCompiler.class);
    private Level oldLevel;

    @BeforeEach
    public void attachCapture() {
        oldLevel = logger.getLevel();
        logger.setLevel(Level.ERROR);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    public void detachCapture() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(oldLevel);
    }

    @Test
    public void compileFailureLogCarriesRawClientSql() {
        DrdsSqlCompiler compiler = Mockito.mock(DrdsSqlCompiler.class,
                Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        SchemaPlus rootSchema = CalciteSchema.createRootSchema(false).plus();
        DrdsSql drdsSql = new DrdsSql("select $$", false, Collections.emptyList(), Collections.emptyList());

        assertThrows(Throwable.class, () -> compiler.getRelRoot(rootSchema, drdsSql),
                "空库会话下 select $$ 应复刻活体的 Calcite 校验失败");

        List<String> logged = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());

        assertTrue(logged.stream().anyMatch(m -> m.contains("sql:select $$")),
                "失败现场日志必须携带客户端原文 sql:select $$（F13），实际日志：" + logged);

        assertTrue(logged.stream().anyMatch(m -> m.contains("sqlStatement:SELECT $$ AS $$")),
                "druid AST 重渲染字段保留（与原文同屏自释三层形态），实际日志：" + logged);
    }

    /** 生产日志串构造的唯一出口：断言走纯函数面，避免测试内复刻格式。 */
    @Test
    public void rawSqlLeadsTheFailureLogFormat() {
        String log = DrdsSqlCompiler.compileFailureLog("select $$", "SELECT $$ AS $$", null, null, null, null);
        assertTrue(log.startsWith("sql:select $$ "), "原文必须置于行首，实际：" + log);
        assertTrue(log.contains("sqlStatement:SELECT $$ AS $$"), "druid 重渲染字段保留，实际：" + log);
    }
}
