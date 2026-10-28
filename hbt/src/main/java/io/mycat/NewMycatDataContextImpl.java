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
package io.mycat;

import io.mycat.calcite.CodeExecuterContext;
import io.mycat.config.MycatServerConfig;
import lombok.Getter;
import lombok.SneakyThrows;
import okhttp3.Response;
import okhttp3.*;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.util.ByteString;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.runtime.NewMycatDataContext;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.type.SqlTypeName;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Getter
public abstract class NewMycatDataContextImpl implements NewMycatDataContext {
    private static final Logger LOGGER = LoggerFactory.getLogger(NewMycatDataContextImpl.class);
    protected final MycatDataContext context;
    protected final CodeExecuterContext codeExecuterContext;
    protected final DrdsSqlWithParams drdsSqlWithParams;

    public NewMycatDataContextImpl(MycatDataContext dataContext,
                                   CodeExecuterContext context,
                                   DrdsSqlWithParams drdsSqlWithParams) {
        this.context = dataContext;
        this.codeExecuterContext = context;
        this.drdsSqlWithParams = drdsSqlWithParams;
    }

    @Override
    public SchemaPlus getRootSchema() {
        return null;
    }

    @Override
    public JavaTypeFactory getTypeFactory() {
        return null;
    }

    @Override
    public QueryProvider getQueryProvider() {
        return null;
    }

    @Override
    public Object get(String name) {
        if (name.startsWith("?")) {
            int index = Integer.parseInt(name.substring(1));
            Object value = drdsSqlWithParams.getParams().get(index);
            // MYCAT CHANGE (I-DEBT-8): 参数化阶段 druid 把 X'..' 十六进制字面量抽为裸 byte[]，
            // 而 DrdsRunnerHelper.getSqlTypeNames 将 byte[] 归为 SqlTypeName.BINARY；Calcite codegen
            // 按 BINARY 的 Java 规范类型生成 (ByteString) root.get("?N") 强转，裸 byte[] 遂触发
            // "[B cannot be cast to ByteString"（如 SELECT X'4D7953514C'）。在参数读边界按声明
            // 类型把 BINARY/VARBINARY 家族的 byte[] 归一为 ByteString，与 BINARY
            // 内部规范表示一致（同 I-DEBT-17 结果侧对称、与 BaseRowObservable 的 byte[] 补 wrap 同族）。
            if (value instanceof byte[]) {
                List<SqlTypeName> typeNames = drdsSqlWithParams.getTypeNames();
                SqlTypeName typeName = (typeNames != null && index < typeNames.size())
                        ? typeNames.get(index) : null;
                if (typeName == null || typeName == SqlTypeName.BINARY
                        || typeName == SqlTypeName.VARBINARY) {
                    value = new ByteString((byte[]) value);
                }
            }
            return value;
        }
        return codeExecuterContext.getVarContext().get(name);
    }

    public Object getSessionVariable(String name) {
        return context.getVariable(false, name);
    }

    public Object getGlobalVariable(String name) {
        return context.getVariable(true, name);
    }

    public String getDatabase() {
        return context.getDefaultSchema();
    }

    public Long getLastInsertId() {
        return context.getLastInsertId();
    }

    public Long getConnectionId() {
        return context.getSessionId();
    }

    public Object getUserVariable(String name) {
        return null;
    }

    public String getCurrentUser() {
        MycatUser user = context.getUser();
        Authenticator authenticator = MetaClusterCurrent.wrapper(Authenticator.class);
        return user.getUserName() + "@" + authenticator.getUserInfo(user.getUserName()).getIp();
    }

    public String getUser() {
        MycatUser user = context.getUser();
        return user.getUserName() + "@" + user.getHost();
    }

    @Override
    public DrdsSqlWithParams getDrdsSql() {
        return drdsSqlWithParams;
    }

    @Override
    public Long getRowCount() {
        return context.getAffectedRows();
    }

    public MycatDataContext getContext() {
        return context;
    }


    public Integer getLock(String name, long timeout) {
        MycatDataContext context = getContext();
        return context.getLock(name, timeout);
    }

    public Integer releaseLock(String name) {
        MycatDataContext context = getContext();
        return context.releaseLock(name);
    }

    public Integer isFreeLock(String name) {
        MycatDataContext context = getContext();
        return context.isFreeLock(name);
    }
}
