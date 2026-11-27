package io.mycat.commands;

import com.alibaba.druid.sql.ast.statement.SQLCallStatement;
import com.alibaba.druid.sql.ast.statement.SQLCreateFunctionStatement;
import com.alibaba.druid.sql.ast.statement.SQLCreateProcedureStatement;
import io.mycat.sqlhandler.SQLHandler;
import io.mycat.sqlhandler.ddl.CreateTableSQLHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MycatdbCommandInitializationTest {

    @Test
    void registrationIncludesHandlersAfterCreateTable() throws Exception {
        Field field = MycatdbCommand.class.getDeclaredField("sqlHandlerMap");
        field.setAccessible(true);
        com.google.common.collect.ImmutableClassToInstanceMap<?> handlers =
                (com.google.common.collect.ImmutableClassToInstanceMap<?>) field.get(null);

        assertNotNull(handlers.get(SQLCreateFunctionStatement.class));
        assertNotNull(handlers.get(SQLCreateProcedureStatement.class));
        assertNotNull(handlers.get(SQLCallStatement.class));
    }

    @Test
    void duplicateStatementClassFailsInitialization() {
        SQLHandler first = new CreateTableSQLHandler();
        SQLHandler second = new CreateTableSQLHandler();

        assertThrows(IllegalArgumentException.class,
                () -> MycatdbCommand.buildSqlHandlerMap(Arrays.asList(first, second)));
    }

    @Test
    void missingStatementClassFailsInitialization() {
        SQLHandler handler = new SQLHandler() {
            @Override
            public Class getStatementClass() {
                return null;
            }
        };

        assertThrows(NullPointerException.class,
                () -> MycatdbCommand.buildSqlHandlerMap(Arrays.asList(handler)));
    }
}
