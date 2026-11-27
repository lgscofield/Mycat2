package io.mycat.commands;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLCommentHint;
import com.alibaba.druid.sql.ast.SQLStatement;
import io.mycat.calcite.MycatHint;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MycatdbCommandHintRouteTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> hintRoute(String sql) throws Exception {
        Method method = MycatdbCommand.class.getDeclaredMethod("getHintRoute", SQLStatement.class);
        method.setAccessible(true);
        MySqlStatementParser parser = (MySqlStatementParser) SQLParserUtils.createSQLStatementParser(sql, DbType.mysql, true);
        SQLStatement statement = parser.parseStatementList().get(0);
        assertNotNull(statement.getHeadHintsDirect());
        assertFalse(statement.getHeadHintsDirect().isEmpty());
        SQLCommentHint hint = (SQLCommentHint) statement.getHeadHintsDirect().get(0);
        MycatHint mycatHint = new MycatHint(hint.getText());
        assertFalse(mycatHint.getFunctions().isEmpty(), hint.getText());
        return (Map<String, Object>) method.invoke(null, statement);
    }

    @Test
    void mergeUnionSizeDoesNotFallThroughToExecuteTimeout() throws Exception {
        Map<String, Object> route = hintRoute("/*+MYCAT:MERGE_UNION_SIZE(7)*/ SELECT 1");

        assertEquals(7, route.get("MERGE_UNION_SIZE"));
        assertFalse(route.containsKey("EXECUTE_TIMEOUT"));
    }

    @Test
    void explicitExecuteTimeoutStillWorks() throws Exception {
        Map<String, Object> route = hintRoute("/*+MYCAT:EXECUTE_TIMEOUT(123)*/ SELECT 1");

        assertEquals(123L, route.get("EXECUTE_TIMEOUT"));
        assertFalse(route.containsKey("MERGE_UNION_SIZE"));
    }
}
