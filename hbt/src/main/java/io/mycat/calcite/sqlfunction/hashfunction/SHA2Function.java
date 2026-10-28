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

/**
 * MySQL {@code SHA2(str, hashLength)}：按指定摘要长度计算，返回小写十六进制串。
 *
 * <p>MYCAT ADDITION（I-DEBT-32-FAMILY 加密/哈希族本地落码·续 MD5/SHA1 之后的窄切片）：
 * Calcite 只有单参 {@code SHA256}（{@code SqlLibraryOperators}），且 Mycat 算子表不引 library，
 * 双参 {@code SHA2} 在 Mycat 侧完全无实现——{@code lookupOperatorOverloads("SHA2")} 返 0 个算子，
 * 折算期残留 {@code SqlUnresolvedFunction}，出码期 {@code RexToLixTranslator.visitCall} 抛
 * {@code cannot translate call SHA2($t7, $t21)}（客户端表现为 {@code Unable to implement MycatProject}）。
 *
 * <p>语义基准取自真库对拍（MySQL 8.0.46 / MariaDB 10.11），见 {@link #sha2}：形参按
 * {@code GetOperandTypes} 从 Java 方法签名 {@code (String, Integer)} 自动派生（与方案 E 同源，
 * 直配 information_schema / 本地表的 VARCHAR 列）。
 */
public class SHA2Function extends MycatSqlDefinedFunction {
    public static final SHA2Function INSTANCE = new SHA2Function();

    public SHA2Function() {
        super("SHA2",
                MycatScalarFunction.create(SHA2Function.class, "sha2", 2),
                SqlFunctionCategory.STRING);
    }

    public static String sha2(String arg0, Integer hashLength) {
        // 真库语义(MySQL 8.0.46 实测, 与 MariaDB 10.11 逐值一致):
        //   任一入参 NULL → NULL (故用 Integer 而非 int 以区分 NULL 长度;
        //   注: MariaDB 对 SHA2(str,NULL) 会回退为 256, 本实现按北极星主口径对齐 MySQL 而返 NULL)。
        //   len=0 视同 256; 仅 224/256/384/512 为合法长度, 其余(1/160/999/负) → NULL。
        // 摘要按 UTF-8 字节取、小写十六进制(复用 DigestHex, 与 MD5/SHA1 同源)。
        if (arg0 == null || hashLength == null) {
            return null;
        }
        final String algorithm;
        switch (hashLength) {
            case 0:
            case 256:
                algorithm = "SHA-256";
                break;
            case 224:
                algorithm = "SHA-224";
                break;
            case 384:
                algorithm = "SHA-384";
                break;
            case 512:
                algorithm = "SHA-512";
                break;
            default:
                // 非法摘要长度：真库返回 NULL（非报错）。
                return null;
        }
        return DigestHex.digestHex(algorithm, arg0);
    }
}
