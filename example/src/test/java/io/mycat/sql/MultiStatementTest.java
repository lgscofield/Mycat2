package io.mycat.sql;

import io.mycat.assemble.MycatTest;
import org.junit.Test;

import javax.annotation.concurrent.NotThreadSafe;
import java.sql.Connection;
import java.sql.Statement;

@NotThreadSafe
@net.jcip.annotations.NotThreadSafe
public class MultiStatementTest implements MycatTest {
    @Test
    public void testSelect() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(
                "jdbc:mysql://localhost:8066/mysql?username=root&password=123456&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                +"&allowMultiQueries=true"
        )) {
            Statement statement = mycatConnection.createStatement();
            statement.execute("select 1;select 1;");
        }
    }
    @Test
    public void testSelectOk() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(
                "jdbc:mysql://localhost:8066/mysql?username=root&password=123456&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                        +"&allowMultiQueries=true"
        )) {
            Statement statement = mycatConnection.createStatement();
            statement.execute("select 1;set autocommit = 1;");
        }
    }
    @Test
    public void testOkSelect() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(
                "jdbc:mysql://localhost:8066/mysql?username=root&password=123456&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                        +"&allowMultiQueries=true"
        )) {
            Statement statement = mycatConnection.createStatement();
            statement.execute("set autocommit = 1;select 1");
        }
    }
    @Test(expected= Exception.class)
    public void testSelectError() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(
                "jdbc:mysql://localhost:8066/mysql?username=root&password=123456&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                        +"&allowMultiQueries=true"
        )) {
            Statement statement = mycatConnection.createStatement();

            // I-DEBT-39 tranche①-宽订正: 原以 "select 1;select 1/0" 的 1/0 作错误触发句，但 tranche① 已让
            // 本地 SELECT 期 1/0 对齐 MySQL = 返回 NULL(不再报错)，该前提破。改用校验期错误(未知列)保留
            // 本用例意图的 "OK → ERROR" 多语句错误传播边界：首句 select 1(OK) + 次句未知列(ERROR)。
            // 多语句批处理下后续语句的错误可能延迟到遍历结果时才浮出，故 execute() 后主动 drain 以确保 ERROR 被 surface。
            statement.execute("select 1;select `no_such_col_zzz` from (select 1) t");
            while (statement.getMoreResults() || statement.getUpdateCount() != -1) {
                // 消耗剩余结果，逼出次句的 ERR。
            }
        }
    }
    @Test(expected= Exception.class)
    public void testOkError() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(
                "jdbc:mysql://localhost:8066/mysql?username=root&password=123456&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                        +"&allowMultiQueries=true"
        )) {
            Statement statement = mycatConnection.createStatement();
            // I-DEBT(7C 残面·测试订正): 原写 "select 1;begin;begin;" 期望抛异常，但 begin(=START TRANSACTION)
            // 连发在真 MySQL/Mycat 均合法、不报错（探针实测 exit 0），系用例误选了非错误的触发语句。
            // I-DEBT-39 tranche①-宽再订正: 上一版改用 "set autocommit = 1;select 1/0"，但 tranche① 后
            // 1/0 已对齐 MySQL SELECT 期返回 NULL、不再报错，该错误触发句失效。改用校验期错误(未知列)，
            // 与同类 testSelectError 保持同构：首句 set(OK) + 次句未知列(ERROR)，才真正测到 "OK → ERROR"
            // 多语句错误传播边界。同 testSelectError，execute() 后主动 drain 以 surface 次句 ERR。
            statement.execute("set autocommit = 1;select `no_such_col_zzz` from (select 1) t");
            while (statement.getMoreResults() || statement.getUpdateCount() != -1) {
                // 消耗剩余结果，逼出次句的 ERR。
            }
        }
    }
}
