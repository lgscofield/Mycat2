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
package io.mycat.calcite.sqlfunction.hashfunction;

import io.mycat.calcite.MycatScalarFunction;
import org.apache.calcite.mycat.MycatSqlDefinedFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;

/**
 * MySQL {@code MD5(str)}：MD5 摘要的小写十六进制串（32 字符）。
 *
 * <p>MYCAT ADDITION（I-DEBT-32-FAMILY 加密/哈希族本地落码）：此前 Mycat 既无自有算子、
 * 也不在 {@code SqlStdOperatorTable} 里，算子表 {@code lookupOperatorOverloads("MD5")} 返回 0 个算子，
 * 折算期残留 {@code SqlUnresolvedFunction}，出码期 {@code RexToLixTranslator.visitCall} 抛
 * {@code cannot translate call MD5(...)}（客户端表现为 {@code Unable to implement MycatProject}）。
 * 形参取 {@code String}（family=CHARACTER），与 I-DEBT-32 定案的方案 E 同源，直配
 * information_schema / 本地表的 VARCHAR 列，无需 coerce。
 */
public class MD5Function extends MycatSqlDefinedFunction {
    public static final MD5Function INSTANCE = new MD5Function();

    public MD5Function() {
        super("MD5", ReturnTypes.VARCHAR_2000,
                InferTypes.FIRST_KNOWN, OperandTypes.STRING,
                MycatScalarFunction.create(MD5Function.class,
                        "md5", 1),
                SqlFunctionCategory.STRING);
    }

    public static String md5(String arg0) {
        // 真库对 utf8mb4 连接按 UTF-8 字节取摘要, 故显式指定字符集(不能用 String.getBytes() 的平台默认值,
        // Windows JVM 默认 GBK 会得到不同摘要); NULL 入参 MySQL 返回 NULL 而非报错。
        return arg0 == null ? null : DigestHex.digestHex("MD5", arg0);
    }
}
