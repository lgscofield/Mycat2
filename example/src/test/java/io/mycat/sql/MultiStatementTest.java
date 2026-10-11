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

            statement.execute("select 1;select 1/0");
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
            // 与同类已绿的 testSelectError("select 1;select 1/0") 保持同构：首句 set(OK) + 次句 1/0(错误)，
            // 才真正测到本用例意图的 "OK → ERROR" 多语句错误传播边界。
            // 注：Mycat 对多语句运行期错误是以断连(2013) 而非干净 ERR 包终止会话，属更深的流式/错误终结问题，
            // 已另挂账，不在本测吸收。
            statement.execute("set autocommit = 1;select 1/0");
        }
    }
}
