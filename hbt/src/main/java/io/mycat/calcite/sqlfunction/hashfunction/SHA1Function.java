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
 * MySQL {@code SHA1(str)}（同义词 {@code SHA(str)}）：SHA-1 摘要的小写十六进制串（40 字符）。
 *
 * <p>MYCAT ADDITION（I-DEBT-32-FAMILY 加密/哈希族本地落码）：与 {@link MD5Function} 同因——
 * Mycat 算子表对 "SHA1"/"SHA" 均解析出 0 个算子，出码期 {@code cannot translate call SHA1(...)}。
 */
public class SHA1Function extends MycatSqlDefinedFunction {
    public static final SHA1Function INSTANCE = new SHA1Function();

    public SHA1Function() {
        super("SHA1", ReturnTypes.VARCHAR_2000,
                InferTypes.FIRST_KNOWN, OperandTypes.STRING,
                MycatScalarFunction.create(SHA1Function.class,
                        "sha1", 1),
                SqlFunctionCategory.STRING);
    }

    public static String sha1(String arg0) {
        // JCA 算法名是 "SHA-1"; 同义词 SHA(...) 复用本实现(名字在 MycatCalciteSupport 里夺占)。
        return arg0 == null ? null : DigestHex.digestHex("SHA-1", arg0);
    }
}
