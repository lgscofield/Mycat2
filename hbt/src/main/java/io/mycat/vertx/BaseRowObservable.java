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
package io.mycat.vertx;

import io.mycat.MycatTimeUtil;
import io.mycat.api.collector.RowBaseIterator;
import io.mycat.api.collector.RowObservable;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.calcite.resultset.MycatValueFactory;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.disposables.Disposable;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.mysqlclient.impl.MySQLRowDesc;
import io.vertx.mysqlclient.impl.codec.StreamMysqlCollector;
import io.vertx.mysqlclient.impl.protocol.ColumnDefinition;
import io.vertx.sqlclient.*;
import io.vertx.sqlclient.data.Numeric;
import io.vertx.sqlclient.desc.ColumnDescriptor;
import lombok.SneakyThrows;
import org.apache.calcite.avatica.util.ByteString;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static java.sql.Types.*;

public class BaseRowObservable extends RowObservable implements StreamMysqlCollector {
    private static final Logger LOGGER = LoggerFactory.getLogger(BaseRowObservable.class);
    protected MycatRowMetaData metaData;
    protected Observer<? super Object[]> observer;
    protected final Future<SqlConnection> sqlConnectionFuture;
    protected final String sql;
    protected final List<Object> values;
    protected ColumnDefinition[] columnDefinitions;

    public BaseRowObservable(Future<SqlConnection> sqlConnectionFuture,
                             String sql,
                             List<Object> values,
                             MycatRowMetaData rowMetaData) {
        this.sqlConnectionFuture = sqlConnectionFuture;
        this.sql = sql;
        this.values = values;
        this.metaData = rowMetaData;
    }

    @Override
    protected void subscribeActual(@NonNull Observer<? super Object[]> observer) {
        this.observer = observer;
        sqlConnectionFuture
                .flatMap(connection -> connection.prepare(sql)).compose(preparedStatement -> {
                    PreparedQuery<RowSet<Row>> query = preparedStatement.query();
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("RowObservableImpl sql:{} connection:{}", sql, sqlConnectionFuture.result());
                    }
                    PreparedQuery<SqlResult<Void>> collecting = query.collecting(this);
                    return collecting.execute(Tuple.tuple(values));
                }).onSuccess(new Handler<SqlResult<Void>>() {
                    @Override
                    public void handle(SqlResult<Void> event) {

                        LOGGER.debug("subscribeActual successful sql:{} connection:{}", sql, sqlConnectionFuture.result());

                        observer.onComplete();
                    }
                }).onFailure(event -> {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.error("subscribeActual error sql:{}", sql);
                    }
                    this.observer.onError(event);
                });
    }

    @Override
    public void onColumnDefinitions(MySQLRowDesc columnDefinitions) {
        this.columnDefinitions = columnDefinitions.columnDefinitions();
        if (this.metaData == null) {
            this.metaData = toColumnMetaData(columnDefinitions.columnDescriptor());
        }
        this.observer.onSubscribe(Disposable.disposed());
    }

    @Override
    public void onRow(Row row) {
        MycatRowMetaData metaData = this.metaData;
        int columnCount = this.metaData.getColumnCount();
        Object[] objects = getObjects(row, metaData);
        observer.onNext(objects);
    }

    @NotNull
    @SneakyThrows
    public static Object[] getObjects(Row row, MycatRowMetaData metaData) {
        Object[] objects = new Object[metaData.getColumnCount()];
        for (int columnIndex = 0; columnIndex < objects.length; columnIndex++) {
            int columnType = metaData.getColumnType(columnIndex);
            Object value = null;
            switch (columnType) {
                case BIT:
                case BOOLEAN: {
                    value = row.getValue(columnIndex);
                    if (value == null) {
                        break;
                    }
                    if (value instanceof Boolean) {
                        break;
                    }
                    // I-DEBT-34：BIT(n>1) 后端 JDBC 原生返回 byte[]（官方口径），原样透传，
                    // 绝不折成 Boolean/数值；前端以 MYSQL_TYPE_BIT+原始字节回写。
                    if (value instanceof byte[]) {
                        break;
                    }
                    if (value instanceof Number) {
                        value = MycatValueFactory.BOOLEAN_VALUE_FACTORY.createFromLong(((Number) value).longValue());
                        break;
                    }
                    throw new UnsupportedOperationException("unsupport type:" + value);
                }
                case TINYINT:
                case SMALLINT:
                case INTEGER:
                case BIGINT: {
                    Numeric numeric = row.getNumeric(columnIndex);
                    if (numeric == null) {
                        value = null;
                    } else {
                        value = MycatValueFactory.LONG_VALUE_FACTORY.createFromLong(numeric.longValue());
                    }
                    break;
                }

                case FLOAT:
                case REAL:
                case DOUBLE: {
                    Numeric numeric = row.getNumeric(columnIndex);
                    if (numeric == null) {
                        value = null;
                    } else {
                        value = numeric.doubleValue();
                    }
                    break;
                }

                case DECIMAL:
                case NUMERIC: {
                    value = row.getBigDecimal(columnIndex);
                    break;
                }
                case NCHAR:
                case NVARCHAR:
                case LONGNVARCHAR:
                case LONGVARCHAR:
                case VARCHAR:
                case CHAR: {
                    value = row.getValue(columnIndex);
                    if (value instanceof String) {

                    } else if (value instanceof byte[]) {
                        value = new String((byte[]) value);
                    }
                    break;
                }
                case DATE: {
                    value = row.getValue(columnIndex);
                    if (value == null) {

                    } else if (value instanceof LocalDate) {

                    } else if (value instanceof java.sql.Date) {
                        value = ((Date) value).toLocalDate();
                    } else if (value instanceof java.util.Date) {
                        java.util.Date value1 = (java.util.Date) value;
                        value = LocalDate.of(value1.getYear() + 1900, value1.getMonth() + 1, value1.getDate());
                    } else if (value instanceof String) {
                        value = LocalDate.parse((String) value);
                    } else {
                        throw new UnsupportedOperationException("unsupport type:" + value);
                    }
                    break;
                }
                case TIME_WITH_TIMEZONE:
                case TIME: {
                    value = row.getValue(columnIndex);
                    if (value == null) {

                    } else if (value instanceof Duration) {

                    } else {
                        String s = value.toString();
                        value = MycatTimeUtil.timeStringToTimeDuration(s);
                    }
                    break;
                }
                case TIMESTAMP_WITH_TIMEZONE:
                case TIMESTAMP: {
                    value = row.getValue(columnIndex);
                    if (value == null) {
                        value = null;
                    } else if (value instanceof LocalDateTime) {

                    } else if (value instanceof Timestamp) {
                        value = ((Timestamp) value).toLocalDateTime();
                    } else if (value instanceof String) {
                        value = MycatTimeUtil.timestampStringToTimestamp((String) value);
                    } else {
                        throw new UnsupportedOperationException("unsupport type:" + value);
                    }
                    break;
                }
                case NCLOB:
                case CLOB: {
                    value = row.getValue(columnIndex);
                    // MYCAT CHANGE (I-DEBT-17): 原分支仅接受 Clob 实例，其它一律抛
                    // "unsupport type:..."。但字符大对象列（如 information_schema 的长文本列）
                    // 经 MySQL JDBC 常直接以 String 返回、元数据却标 CLOB/NCLOB，导致误抛；
                    // NULL 也会落进 else 抛（与同文件 BLOB 兄弟分支已守 null/String/byte[] 不一致）。
                    // 与 BLOB 分支对称：null 归一、String 原样、byte[] 解码、Clob 取子串。
                    if (value == null) {
                        value = null;
                    } else if (value instanceof Clob) {
                        Clob value1 = (Clob) value;
                        try {
                            value = (value1.getSubString(1, (int) (value1.length())));
                        } finally {
                            value1.free();
                        }
                    } else if (value instanceof String) {

                    } else if (value instanceof byte[]) {
                        value = new String((byte[]) value);
                    } else {
                        throw new UnsupportedOperationException("unsupport type:" + value);
                    }
                    break;
                }
                case BLOB:
                case LONGVARBINARY:
                case VARBINARY:
                case BINARY: {
                    value = row.getValue(columnIndex);
                    if (value == null) {
                        value = null;
                    } else if (value instanceof String) {
                        value = new ByteString(((String) value).getBytes());
                    } else if (value instanceof Buffer) {
                        value = new ByteString(((Buffer) value).getBytes());
                    } else if (value instanceof Blob) {
                        Blob value1 = (Blob) value;
                        try {
                            value = new ByteString(value1.getBytes(1, (int) (value1.length())));
                        } finally {
                            value1.free();
                        }
                    } else if (value instanceof byte[]) {
                        // MYCAT CHANGE (I-DEBT-8): 原空体→裸 byte[] 未归一，破坏“BINARY 内部规范表示=ByteString”
                        // 不变式（同分支 String/Buffer/Blob 皆转 ByteString），下游 (ByteString) 强转即抛
                        // [B cannot be cast to ByteString。与 I-DEBT-17 CLOB 补 byte[] 对称，补 wrap。
                        value = new ByteString((byte[]) value);
                    } else {
                        throw new UnsupportedOperationException("unsupport type:" + value);
                    }
                    break;
                }
                case NULL: {
                    value = null;
                    break;
                }
                case ROWID:
                case REF_CURSOR:
                case OTHER:
                case JAVA_OBJECT:
                case DISTINCT:
                case STRUCT:
                case ARRAY:
                case REF:
                case DATALINK:
                default:
                    value = row.getValue(columnIndex);
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("may be unsupported type :{}", JDBCType.valueOf(columnType));
                    }
            }
            objects[columnIndex] = value;
        }
        return objects;
    }


    @NotNull
    @SneakyThrows
    public static Object[] getObjects(Object[] row, MycatRowMetaData metaData) {
        Object[] objects = new Object[metaData.getColumnCount()];
        for (int columnIndex = 0; columnIndex < objects.length; columnIndex++) {
            int columnType = metaData.getColumnType(columnIndex);
            Object value = null;
            switch (columnType) {
                case BIT:
                case BOOLEAN: {
                    value = row[columnIndex];
                    if (value == null) {
                        break;
                    }
                    if (value instanceof Boolean) {
                        break;
                    }
                    // I-DEBT-34：BIT(n>1) 后端 JDBC 原生返回 byte[]（官方口径），原样透传，
                    // 绝不折成 Boolean/数值；前端以 MYSQL_TYPE_BIT+原始字节回写。
                    if (value instanceof byte[]) {
                        break;
                    }
                    if (value instanceof Number) {
                        value = MycatValueFactory.BOOLEAN_VALUE_FACTORY.createFromLong(((Number) value).longValue());
                        break;
                    }
                    throw new UnsupportedOperationException("unsupport type:" + value);
                }
                case TINYINT:
                case SMALLINT:
                case INTEGER:
                case BIGINT: {
                    Object o = row[columnIndex];
                    if (o == null) {
                        value = null;
                    } else if (o instanceof Number) {
                        Number numeric = (Number) o;
                        value = MycatValueFactory.LONG_VALUE_FACTORY.createFromLong(numeric.longValue());
                    } else if (o instanceof Boolean) {
                        value = (Boolean) o ? 1 : 0;
                    } else {
                        throw new UnsupportedOperationException(o.toString());
                    }
                    break;
                }

                case FLOAT:
                case REAL: {
                    Number numeric = (Number) row[columnIndex];
                    if (numeric == null) {
                        value = null;
                    } else {
                        value = numeric.floatValue();
                    }
                    break;
                }
                case DOUBLE: {
                    Number numeric = (Number) row[columnIndex];
                    if (numeric == null) {
                        value = null;
                    } else {
                        value = numeric.doubleValue();
                    }
                    break;
                }

                case DECIMAL:
                case NUMERIC: {
                    value = row[columnIndex];
                    break;
                }
                case NCHAR:
                case NVARCHAR:
                case LONGNVARCHAR:
                case LONGVARCHAR:
                case VARCHAR:
                case CHAR: {
                    value = row[columnIndex];
                    if (value instanceof String) {

                    } else if (value instanceof byte[]) {
                        value = new String((byte[]) value);
                    }
                    break;
                }
                case DATE: {
                    value = row[columnIndex];
                    if (value == null) {

                    } else if (value instanceof LocalDate) {

                    } else if (value instanceof java.sql.Date) {
                        value = ((Date) value).toLocalDate();
                    } else if (value instanceof java.util.Date) {
                        java.util.Date value1 = (java.util.Date) value;
                        value = LocalDate.of(value1.getYear() + 1900, value1.getMonth() + 1, value1.getDate());
                    } else if (value instanceof String) {
                        value = LocalDate.parse((String) value);
                    } else {
                        throw new UnsupportedOperationException("unsupport type:" + value);
                    }
                    break;
                }
                case TIME_WITH_TIMEZONE:
                case TIME: {
                    value = row[columnIndex];
                    if (value == null) {

                    } else if (value instanceof Duration) {

                    } else {
                        String s = value.toString();
                        value = MycatTimeUtil.timeStringToTimeDuration(s);
                    }
                    break;
                }
                case TIMESTAMP_WITH_TIMEZONE:
                case TIMESTAMP: {
                    value = row[columnIndex];
                    if (value == null) {
                        value = null;
                    } else if (value instanceof LocalDateTime) {

                    } else if (value instanceof Timestamp) {
                        value = ((Timestamp) value).toLocalDateTime();
                    } else if (value instanceof String) {
                        value = MycatTimeUtil.timestampStringToTimestamp((String) value);
                    } else {
                        throw new UnsupportedOperationException("unsupport type:" + value);
                    }
                    break;
                }
                case NCLOB:
                case CLOB: {
                    value = row[columnIndex];
                    // MYCAT CHANGE (I-DEBT-12 附带修正): 原分支未守 null，NULL 的 CLOB/NCLOB 列会落进 else 抛
                    // "unsupport type:null"（与同文件 DATE/TIMESTAMP/BLOB 兄弟分支均有 value==null 守卫不一致）。
                    // 补齐 null 归一，NULL 列返回 null。
                    // MYCAT CHANGE (I-DEBT-17): 同理补 String/byte[] 分支——字符大对象列（如
                    // information_schema 长文本列）经 JDBC 常直接以 String 返回、元数据却标 CLOB/NCLOB，
                    // 原仅认 Clob 会误抛（如值 "bigint"）；与 BLOB 兄弟分支对称处理。
                    if (value == null) {
                        value = null;
                    } else if (value instanceof Clob) {
                        Clob value1 = (Clob) value;
                        try {
                            value = (value1.getSubString(1, (int) (value1.length())));
                        } finally {
                            value1.free();
                        }
                    } else if (value instanceof String) {

                    } else if (value instanceof byte[]) {
                        value = new String((byte[]) value);
                    } else {
                        throw new UnsupportedOperationException("unsupport type:" + value);
                    }
                    break;
                }
                case BLOB:
                case LONGVARBINARY:
                case VARBINARY:
                case BINARY: {
                    value = row[columnIndex];
                    if (value == null) {
                        value = null;
                    } else if (value instanceof String) {
                        value = new ByteString(((String) value).getBytes());
                    } else if (value instanceof Buffer) {
                        value = new ByteString(((Buffer) value).getBytes());
                    } else if (value instanceof Blob) {
                        Blob value1 = (Blob) value;
                        try {
                            value = new ByteString(value1.getBytes(1, (int) (value1.length())));
                        } finally {
                            value1.free();
                        }
                    } else if (value instanceof byte[]) {
                        // MYCAT CHANGE (I-DEBT-8): 同上，与兄弟分支对称补 wrap（裸 byte[]→ByteString），
                        // 否则下游 (ByteString) 强转即 [B cannot be cast to ByteString。
                        value = new ByteString((byte[]) value);
                    } else {
                        throw new UnsupportedOperationException("unsupport type:" + value);
                    }
                    break;
                }
                case NULL: {
                    value = null;
                    break;
                }
                case ROWID:
                case REF_CURSOR:
                case OTHER:
                case JAVA_OBJECT:
                case DISTINCT:
                case STRUCT:
                case ARRAY:
                case REF:
                case DATALINK:
                default:
                    value = row[columnIndex];
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("may be unsupported type :{}", JDBCType.valueOf(columnType));
                    }
            }
            objects[columnIndex] = value;
        }
        return objects;
    }

    public static Object convertSqlValue(Object value) throws SQLException {
        if (value == null) {
            return null;
        }

        // valid json types are just returned as is
        if (value instanceof Boolean || value instanceof String || value instanceof byte[]) {
            return value;
        }

        // numeric values
        if (value instanceof Number) {
            if (value instanceof BigDecimal) {
                BigDecimal d = (BigDecimal) value;
                if (d.scale() == 0) {
                    return ((BigDecimal) value).toBigInteger();
                } else {
                    // we might loose precision here
                    return ((BigDecimal) value).doubleValue();
                }
            }

            return value;
        }

        // JDBC temporal values

        if (value instanceof Time) {
            return ((Time) value).toLocalTime();
        }

        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate();
        }

        if (value instanceof Timestamp) {
            return ((Timestamp) value).toInstant().atOffset(ZoneOffset.UTC);
        }

        // large objects
        if (value instanceof Clob) {
            Clob c = (Clob) value;
            try {
                // result might be truncated due to downcasting to int
                return c.getSubString(1, (int) c.length());
            } finally {
                try {
                    c.free();
                } catch (AbstractMethodError | SQLFeatureNotSupportedException e) {
                    // ignore since it is an optional feature since 1.6 and non existing before 1.6
                }
            }
        }

        if (value instanceof Blob) {
            Blob b = (Blob) value;
            try {
                // result might be truncated due to downcasting to int
                return b.getBytes(1, (int) b.length());
            } finally {
                try {
                    b.free();
                } catch (AbstractMethodError | SQLFeatureNotSupportedException e) {
                    // ignore since it is an optional feature since 1.6 and non existing before 1.6
                }
            }
        }

        // arrays
        if (value instanceof Array) {
            Array a = (Array) value;
            try {
                Object[] arr = (Object[]) a.getArray();
                if (arr != null) {
                    Object[] castedArray = new Object[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        castedArray[i] = convertSqlValue(arr[i]);
                    }
                    return castedArray;
                }
            } finally {
                a.free();
            }
        }

        // RowId
        if (value instanceof RowId) {
            return ((RowId) value).getBytes();
        }

        // Struct
        if (value instanceof Struct) {
            return Tuple.of(((Struct) value).getAttributes());
        }

        // fallback to String
        return value.toString();
    }

    public static MycatRowMetaData toColumnMetaData(List<ColumnDescriptor> event) {
        ResultSetBuilder resultSetBuilder = ResultSetBuilder.create();
        for (ColumnDescriptor columnDescriptor : event) {
            if (columnDescriptor instanceof ColumnDefinition) {
                ColumnDefinition columnDefinition = (ColumnDefinition) columnDescriptor;
                // MYCAT CHANGE: Vert.x 4.5.x ColumnDefinition does not expose schema/table/length
                String schemaName = "";
                String tableName = "";
                String columnName = columnDefinition.name();
                // Vert.x 4.x: DataType.jdbcType is now private; use ColumnDescriptor.jdbcType() if available
                int columnType;
                try {
                    columnType = columnDescriptor.jdbcType().getVendorTypeNumber();
                } catch (Exception e) {
                    columnType = Types.VARCHAR; // fallback
                }
                int precision = 0;
                int scale = 0;
                String columnLabel = columnDefinition.name();
                boolean isAutoIncrement = false;
                boolean isCaseSensitive = false;
                boolean isNullable = (columnDefinition.flags() & ColumnDefinition.ColumnDefinitionFlags.NOT_NULL_FLAG) == 0;
                boolean isSigned = true;
                int displaySize = 0; // MYCAT CHANGE: Vert.x 4.5.x ColumnDefinition does not expose length
                resultSetBuilder.addColumnInfo(schemaName, tableName, columnName, columnType, precision, scale, columnLabel, isAutoIncrement, isCaseSensitive, isNullable,
                        isSigned, displaySize);
            } else {
                resultSetBuilder.addColumnInfo(columnDescriptor.name(),
                        columnDescriptor.jdbcType());
            }
        }
        RowBaseIterator build = resultSetBuilder.build();
        return build.getMetaData();
    }
}
