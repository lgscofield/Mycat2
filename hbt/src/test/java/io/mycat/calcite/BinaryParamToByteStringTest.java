package io.mycat.calcite;

import io.mycat.DrdsSqlWithParams;
import io.mycat.MycatDataContext;
import io.mycat.NewMycatDataContextImpl;
import org.apache.calcite.avatica.util.ByteString;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import io.mycat.newquery.NewMycatConnection;
import io.reactivex.rxjava3.core.Observable;
import io.vertx.core.Future;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * I-DEBT-8 回归锁：钉死“BINARY 内部规范表示 = ByteString”在<b>动态参数读边界</b>的收口。
 *
 * <p>根因链：参数化阶段 druid 把 {@code X'4D7953514C'} 这类十六进制字面量抽为裸 {@code byte[]}；
 * {@link DrdsRunnerHelper#getSqlTypeNames} 依 {@code byte[]} 归为 {@link SqlTypeName#BINARY}；
 * Calcite codegen 按 BINARY 的 Java 规范类型生成 {@code (ByteString) root.get("?N")} 强转，
 * 于是裸 {@code byte[]} 触发 {@code [B cannot be cast to ByteString}（e2e 靶：SqlFunctionTest:73）。
 *
 * <p>收口点 {@link NewMycatDataContextImpl#get(String)}：对声明为 BINARY/VARBINARY 的 {@code byte[]}
 * 参数补 wrap 为 {@link ByteString}；非二进制类型（如 VARCHAR 的 {@code String}）不得被误伤。
 */
public class BinaryParamToByteStringTest {

    /** 只驱动 get("?N") 路径：context/codeExecuterContext 均不参与，其余抽象方法打桩。 */
    private static NewMycatDataContextImpl dataContext(List<Object> params, List<SqlTypeName> typeNames) {
        DrdsSqlWithParams drdsSql = new DrdsSqlWithParams("SELECT ?", params, false, typeNames,
                Collections.emptyList(), Collections.emptyList());
        return new NewMycatDataContextImpl((MycatDataContext) null, null, drdsSql) {
            @Override
            public Observable<Object[]> getTableObservable(String schema, String table) {
                return null;
            }

            @Override
            public Future<NewMycatConnection> getConnection(String key) {
                return null;
            }

            @Override
            public void recycleConnection(String key, Future<NewMycatConnection> connectionFuture) {
            }

            @Override
            public List<Observable<Object[]>> getObservableList(String node) {
                return null;
            }

            @Override
            public Observable<Object[]> getObservable(String node,
                    org.apache.calcite.linq4j.function.Function1 function1, java.util.Comparator comparator,
                    int offset, int fetch) {
                return null;
            }

            @Override
            public Observable<Object[]> getObservable(String node) {
                return null;
            }
        };
    }

    @Test
    public void byteArraysAreTypedAsBinary() {
        // 前置不变式：byte[] 参数在类型推断里落 BINARY——这正是 codegen 生成 (ByteString) 强转的由来。
        List<SqlTypeName> types = DrdsRunnerHelper.getSqlTypeNames(Collections.singletonList((Object) "MySQL".getBytes()));
        Assertions.assertEquals(SqlTypeName.BINARY, types.get(0));
    }

    @Test
    public void binaryDynamicParamIsNormalizedToByteString() {
        byte[] raw = "MySQL".getBytes();
        NewMycatDataContextImpl ctx = dataContext(
                Collections.singletonList((Object) raw),
                Collections.singletonList(SqlTypeName.BINARY));

        Object value = ctx.get("?0");
        Assertions.assertInstanceOf(ByteString.class, value,
                "BINARY 动态参数必须归一为 ByteString，否则 codegen (ByteString) 强转即 [B CCE");
        Assertions.assertArrayEquals(raw, ((ByteString) value).getBytes());
    }

    @Test
    public void varBinaryDynamicParamIsNormalizedToByteString() {
        byte[] raw = new byte[]{1, 2, 3};
        NewMycatDataContextImpl ctx = dataContext(
                Collections.singletonList((Object) raw),
                Collections.singletonList(SqlTypeName.VARBINARY));

        Assertions.assertInstanceOf(ByteString.class, ctx.get("?0"));
    }

    @Test
    public void varcharStringParamIsNotTouched() {
        // 非二进制：String/VARCHAR 必须原样返回，不被误 wrap（守住不误伤边界）。
        NewMycatDataContextImpl ctx = dataContext(
                Collections.singletonList((Object) "abc"),
                Collections.singletonList(SqlTypeName.VARCHAR));

        Object value = ctx.get("?0");
        Assertions.assertEquals("abc", value);
        Assertions.assertFalse(value instanceof ByteString);
    }

    @Test
    public void mixedParamsNormalizedIndependently() {
        NewMycatDataContextImpl ctx = dataContext(
                Arrays.asList((Object) "MySQL".getBytes(), (Object) 42L),
                Arrays.asList(SqlTypeName.BINARY, SqlTypeName.BIGINT));

        Assertions.assertInstanceOf(ByteString.class, ctx.get("?0"));
        Assertions.assertEquals(42L, ctx.get("?1"));
    }
}
