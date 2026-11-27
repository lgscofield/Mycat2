package io.mycat.commands;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import io.mycat.BindValue;
import io.mycat.calcite.DrdsRunnerHelper;
import io.mycat.DrdsSqlCompiler;
import io.mycat.DrdsSqlWithParams;
import io.mycat.MetaClusterCurrent;
import io.mycat.MetadataManager;
import io.mycat.MycatDataContext;
import io.mycat.MySQLPacketUtil;
import io.mycat.PreparedStatement;
import io.mycat.calcite.MycatCalciteSupport;
import io.mycat.calcite.MycatRel;
import io.mycat.calcite.RelNodeContext;
import io.mycat.calcite.resultset.CalciteRowMetaData;
import io.mycat.proxy.session.MySQLServerSession;
import io.mycat.util.MySQLSqlTextCodec;
import io.vertx.core.Future;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultCommandHandlerPrepareTest {

    private Map<Class, Object> previousContext;
    private MycatDataContext dataContext;
    private MySQLServerSession session;
    private DrdsSqlCompiler compiler;
    private RelDataType rowType;
    private final List<byte[]> payloads = new ArrayList<>();
    private final List<Boolean> endFlags = new ArrayList<>();

    @BeforeEach
    void setUp() {
        previousContext = MetaClusterCurrent.copyContext();
        dataContext = mock(MycatDataContext.class);
        when(dataContext.getDefaultSchema()).thenReturn("mycat");
        when(dataContext.nextPrepareStatementId()).thenReturn(7L);
        when(dataContext.getPrepareInfo()).thenReturn(new HashMap<>());

        rowType = rowType("value", SqlTypeName.INTEGER);
        compiler = mock(DrdsSqlCompiler.class);
        RelNodeContext defaultRelContext = relContext(rowType);
        when(compiler.getRelRoot(any(DrdsSqlWithParams.class))).thenReturn(defaultRelContext);
        MetaClusterCurrent.register(MetadataManager.class, mock(MetadataManager.class));
        MetaClusterCurrent.register(DrdsSqlCompiler.class, compiler);

        session = mock(MySQLServerSession.class, CALLS_REAL_METHODS);
        when(session.getDataContext()).thenReturn(dataContext);
        when(session.getWarningCount()).thenReturn(0);
        when(session.getServerStatusValue()).thenReturn(2);
        when(session.writeBytes(any(byte[].class), anyBoolean())).thenAnswer(invocation -> {
            payloads.add(invocation.getArgument(0));
            endFlags.add(invocation.getArgument(1));
            return Future.succeededFuture();
        });
    }

    @AfterEach
    void restoreContext() {
        MetaClusterCurrent.register(previousContext);
    }

    @Test
    void parameterAndColumnDefinitionsAreBothSentWithDeprecateEof() {
        when(session.isDeprecateEOF()).thenReturn(true);

        Future<Void> result = prepare("select ?");

        assertTrue(result.succeeded());
        assertEquals(3, payloads.size());
        assertCounts(1, 1);
        assertArrayEquals(parameterPayload(0), payloads.get(1));
        assertArrayEquals(fieldPayload(rowType, 0), payloads.get(2));
        assertEquals(List.of(false, false, true), endFlags);
    }

    @Test
    void parameterAndColumnDefinitionsUseBothEofPacketsInTraditionalMode() {
        Future<Void> result = prepare("select ?");

        assertTrue(result.succeeded());
        assertEquals(5, payloads.size());
        assertCounts(1, 1);
        assertArrayEquals(parameterPayload(0), payloads.get(1));
        assertArrayEquals(MySQLPacketUtil.generateEof(0, 2), payloads.get(2));
        assertArrayEquals(fieldPayload(rowType, 0), payloads.get(3));
        assertArrayEquals(MySQLPacketUtil.generateEof(0, 2), payloads.get(4));
        assertEquals(List.of(false, false, false, false, true), endFlags);
    }

    @Test
    void selectWithoutParametersSendsFieldDefinitionAndTraditionalEof() {
        Future<Void> result = prepare("select 1");

        assertTrue(result.succeeded());
        assertEquals(3, payloads.size());
        assertCounts(1, 0);
        assertArrayEquals(fieldPayload(rowType, 0), payloads.get(1));
        assertArrayEquals(MySQLPacketUtil.generateEof(0, 2), payloads.get(2));
        assertEquals(List.of(false, false, true), endFlags);
    }

    @Test
    void dmlParametersKeepCompleteDefinitionSequenceInTraditionalMode() {
        Future<Void> result = prepare("update t set v=? where id=?");

        assertTrue(result.succeeded());
        assertEquals(4, payloads.size());
        assertCounts(0, 2);
        assertArrayEquals(parameterPayload(0), payloads.get(1));
        assertArrayEquals(parameterPayload(1), payloads.get(2));
        assertArrayEquals(MySQLPacketUtil.generateEof(0, 2), payloads.get(3));
        assertEquals(List.of(false, false, false, true), endFlags);
        PreparedStatement statement = dataContext.getPrepareInfo().get(7L);
        assertEquals(2, statement.getParametersNumber());
    }

    @Test
    void statementWithoutParametersOrColumnsEndsWithPrepareOk() {
        when(session.isDeprecateEOF()).thenReturn(true);

        Future<Void> result = prepare("set @debt127_prepare = 1");

        assertTrue(result.succeeded(), () -> String.valueOf(result.cause()));
        assertEquals(1, payloads.size());
        assertCounts(0, 0);
        assertEquals(List.of(true), endFlags);
    }

    @Test
    void selectMetadataComesFromCalciteRowType() {
        RelDataType twoColumns = MycatCalciteSupport.TypeFactory.builder()
                .add("value", MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.INTEGER))
                .add("name", MycatCalciteSupport.TypeFactory.createTypeWithNullability(
                        MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.VARCHAR), true))
                .build();
        RelNodeContext twoColumnRelContext = relContext(twoColumns);
        when(compiler.getRelRoot(any(DrdsSqlWithParams.class))).thenReturn(twoColumnRelContext);
        when(session.isDeprecateEOF()).thenReturn(true);

        Future<Void> result = prepare("select 1, 'x'");

        assertTrue(result.succeeded());
        assertEquals(3, payloads.size());
        assertCounts(2, 0);
        assertArrayEquals(fieldPayload(twoColumns, 0), payloads.get(1));
        assertArrayEquals(fieldPayload(twoColumns, 1), payloads.get(2));
        assertEquals(List.of(false, false, true), endFlags);
    }

    @Test
    void chineseSqlIsDecodedAsUtf8BeforeParsing() {
        when(session.isDeprecateEOF()).thenReturn(true);

        Future<Void> result = prepare("select 1 as `中文`");

        assertTrue(result.succeeded());
        SQLStatement statement = dataContext.getPrepareInfo().get(7L).getStatement();
        assertTrue(statement.toString().contains("中文"), statement.toString());
        assertCounts(1, 0);
    }

    @Test
    void illegalUtf8ByteUsesReversibleLatinCarrier() {
        when(session.isDeprecateEOF()).thenReturn(true);
        byte[] sql = concatenate(
                "select 1 as `".getBytes(StandardCharsets.US_ASCII),
                new byte[]{(byte) 0xAA},
                "`".getBytes(StandardCharsets.US_ASCII));

        Future<Void> result = new DefaultCommandHandler().handlePrepareStatement(sql, session);

        assertTrue(result.succeeded());
        SQLStatement statement = dataContext.getPrepareInfo().get(7L).getStatement();
        assertTrue(statement.toString().contains("\u00AA"), statement.toString());
        assertCounts(1, 0);
    }

    @Test
    void metadataFailureIsReportedAsUnsupportedPrepare() {
        when(compiler.getRelRoot(any(DrdsSqlWithParams.class)))
                .thenThrow(new IllegalStateException("metadata unavailable"));

        Future<Void> result = prepare("select 1");

        assertTrue(result.failed());
        SQLException failure = assertInstanceOf(SQLException.class, result.cause());
        assertEquals(1295, failure.getErrorCode());
        assertEquals("HY000", failure.getSQLState());
        assertTrue(payloads.isEmpty());
    }

    @Test
    void metadataResolutionDoesNotBindSavedAstToPrepareSchema() {
        when(compiler.getRelRoot(any(DrdsSqlWithParams.class))).thenAnswer(invocation -> {
            DrdsSqlWithParams parsed = invocation.getArgument(0);
            SQLExprTableSource table = (SQLExprTableSource) ((SQLSelectStatement) parsed.getParameterizedStatement())
                    .getSelect().getQueryBlock().getFrom();
            assertEquals("db1", SQLUtils.normalize(table.getSchema()));
            return relContext(rowType);
        });
        when(dataContext.getDefaultSchema()).thenReturn("db1");

        Future<Void> result = prepare("select * from t");
        assertTrue(result.succeeded(), () -> String.valueOf(result.cause()));
        SQLSelectStatement saved = (SQLSelectStatement) dataContext.getPrepareInfo().get(7L).getStatement();
        SQLExprTableSource savedTable = (SQLExprTableSource) saved.getSelect().getQueryBlock().getFrom();
        assertNull(savedTable.getSchema());

        when(dataContext.getDefaultSchema()).thenReturn("db2");
        PreparedStatement prepared = dataContext.getPrepareInfo().get(7L);
        prepared.setBindValues(new BindValue[0]);
        SQLStatement executable = prepared.getSQLStatementByBindValue(prepared.getBindValues());
        SQLExprTableSource executableTable = (SQLExprTableSource) ((SQLSelectStatement) executable)
                .getSelect().getQueryBlock().getFrom();
        assertNull(executableTable.getSchema());
        DrdsSqlWithParams reparsed = DrdsRunnerHelper.preParse(executable, "db2");
        SQLExprTableSource resolvedTable = (SQLExprTableSource) ((SQLSelectStatement) reparsed.getParameterizedStatement())
                .getSelect().getQueryBlock().getFrom();
        assertEquals("db2", SQLUtils.normalize(resolvedTable.getSchema()));
    }

    @Test
    void intermediateWriteFailureStopsPrepareAndReturnsOriginalCause() {
        when(session.isDeprecateEOF()).thenReturn(true);
        RuntimeException failure = new RuntimeException("socket write failed");
        when(session.writeBytes(any(byte[].class), anyBoolean())).thenAnswer(invocation -> {
            payloads.add(invocation.getArgument(0));
            endFlags.add(invocation.getArgument(1));
            return payloads.size() == 2 ? Future.failedFuture(failure) : Future.succeededFuture();
        });

        Future<Void> result = prepare("select ?");

        assertTrue(result.failed());
        assertSame(failure, result.cause());
        assertEquals(2, payloads.size());
    }

    private Future<Void> prepare(String sql) {
        return new DefaultCommandHandler().handlePrepareStatement(
                MySQLSqlTextCodec.encode(sql), session);
    }

    private void assertCounts(int columns, int parameters) {
        assertEquals(0, payloads.get(0)[0]);
        assertEquals(7L, readFixedInt(payloads.get(0), 1, 4));
        assertEquals(columns, readFixedInt(payloads.get(0), 5, 2));
        assertEquals(parameters, readFixedInt(payloads.get(0), 7, 2));
    }

    private static long readFixedInt(byte[] bytes, int offset, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value |= (bytes[offset + i] & 0xFFL) << (8 * i);
        }
        return value;
    }

    private static byte[] parameterPayload(int index) {
        io.mycat.beans.mycat.ResultSetBuilder builder = io.mycat.beans.mycat.ResultSetBuilder.create();
        for (int i = 0; i <= index; i++) {
            builder.addColumnInfo("", java.sql.JDBCType.VARCHAR);
        }
        return MySQLPacketUtil.generateColumnDefPayload(builder.build().getMetaData(), index);
    }

    private static byte[] fieldPayload(RelDataType rowType, int index) {
        return MySQLPacketUtil.generateColumnDefPayload(
                new CalciteRowMetaData(rowType.getFieldList()), index);
    }

    private static RelNodeContext relContext(RelDataType rowType) {
        MycatRel rel = mock(MycatRel.class);
        when(rel.getRowType()).thenReturn(rowType);
        RelRoot root = RelRoot.of(rel, SqlKind.SELECT);
        RelNodeContext context = mock(RelNodeContext.class);
        when(context.getRoot()).thenReturn(root);
        return context;
    }

    private static RelDataType rowType(String name, SqlTypeName type) {
        RelDataTypeFactory.Builder builder = MycatCalciteSupport.TypeFactory.builder();
        builder.add(name, MycatCalciteSupport.TypeFactory.createSqlType(type));
        return builder.build();
    }

    private static byte[] concatenate(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
