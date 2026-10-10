package io.mycat.calcite;

import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * I-DEBT-33 Bug D 回归锁：{@link DrdsRunnerHelper#getSqlTypeNames} 不得把单个 {@link Byte}
 * 参数误判成 {@link SqlTypeName#BINARY}。
 *
 * <p>根因链：{@code select ?} 传 {@code (byte)1}（{@code java.lang.Byte}）时，getSqlTypeNames
 * 硬编码 {@code Byte.class -> BINARY}；Calcite codegen 据此对投影列生成 {@code (ByteString)} 强转，
 * 而 {@link NewMycatDataContextImpl#get(String)} 只把 {@code byte[]} 归一为 ByteString、不含 {@code Byte}，
 * 于是裸 {@code java.lang.Byte} 撞 {@code (ByteString)} 强转抛
 * {@code ClassCastException: java.lang.Byte cannot be cast to ByteString}→服务端杀连接
 * （e2e 靶：UserCaseTest case21）。
 *
 * <p>正解：{@code Byte} 是 1 字节有符号整数（MySQL TINYINT 域），应解析为 {@link SqlTypeName#TINYINT}；
 * 真·二进制 {@code byte[]} 仍须保持 {@link SqlTypeName#BINARY}（守 I-DEBT-8 不过正）。
 */
public class TinyIntParamTypeTest {

    @Test
    public void byteWrapperParamTypesAsTinyintNotBinary() {
        List<SqlTypeName> types = DrdsRunnerHelper.getSqlTypeNames(
                Collections.singletonList((Object) Byte.valueOf((byte) 1)));
        Assertions.assertEquals(SqlTypeName.TINYINT, types.get(0),
                "java.lang.Byte 应解析为 TINYINT，误判 BINARY 会触发 codegen (ByteString) CCE");
    }

    @Test
    public void shortAndIntAndLongKeepNumericTypes() {
        // 守住相邻整型档位不被牵连
        List<SqlTypeName> types = DrdsRunnerHelper.getSqlTypeNames(
                Arrays.asList((Object) Short.valueOf((short) 1), Integer.valueOf(2), Long.valueOf(3L)));
        Assertions.assertEquals(SqlTypeName.INTEGER, types.get(0));
        Assertions.assertEquals(SqlTypeName.INTEGER, types.get(1));
        Assertions.assertEquals(SqlTypeName.BIGINT, types.get(2));
    }

    @Test
    public void byteArrayStillTypesAsBinary() {
        // 反向守卫：byte[] 不能被一起改成 TINYINT（那是 I-DEBT-8 的正确归一路径）
        List<SqlTypeName> types = DrdsRunnerHelper.getSqlTypeNames(
                Collections.singletonList((Object) new byte[]{1, 2, 3}));
        Assertions.assertEquals(SqlTypeName.BINARY, types.get(0));
    }
}
