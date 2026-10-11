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
package io.mycat.calcite.sqlfunction.infofunction;

import io.mycat.MetaClusterCurrent;
import io.mycat.config.VersionProfile;
import io.mycat.config.VersionProfileFactory;
import org.apache.calcite.mycat.MycatSqlDefinedFunction;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeName;

public class MycatVersionFunction extends MycatSqlDefinedFunction {
    public final static MycatVersionFunction INSTANCE = new MycatVersionFunction();

    public MycatVersionFunction() {
        super("VERSION",
                ReturnTypes.explicit(SqlTypeName.VARCHAR),
                InferTypes.RETURN_TYPE, OperandTypes.NILADIC,
                ScalarFunctionImpl.create(MycatVersionFunction.class,"version"),
                SqlFunctionCategory.SYSTEM);
    }

    public static String version(){
        // I-VE-1: SELECT VERSION() 从 VersionProfile 画像取宣称串；DI 未注册（引导早期/单测）回落 MYSQL_5_7 默认，不 NPE
        return MetaClusterCurrent.exist(VersionProfile.class)
                ? MetaClusterCurrent.wrapper(VersionProfile.class).serverVersionString()
                : VersionProfileFactory.defaultProfile().serverVersionString();
    }
}
