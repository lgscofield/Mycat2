package cn.mycat.vertx.xa.impl;

import cn.mycat.vertx.xa.MySQLManager;
import cn.mycat.vertx.xa.XaLog;
import io.mycat.newquery.NewMycatConnection;
import io.mycat.protocol.mysql.type.MySQLIsolation;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MYCAT CHANGE (I-DEBT-113): getConnection 事务首借 check-then-act 锁——
 * 修复前：`if (localSqlConnection == null)` 判空后跨异步借用再赋值。真异步池（借出 Future 未 resolve）
 * 期间第二条 getConnection 进入，两并发首借各走借出分支，后完成者覆写 localSqlConnection，
 * 先借出的连接成孤儿（带着已 open 的 begin; 静默丢失）。
 * 修复后：首借登记先行（synchronized 首借块 + 占位 Future），后到者等首借落位后走 map/local 路径，
 * 两连接全部登记（localSqlConnection / map），无一孤儿。
 */
public class LocalXaConcurrentFirstBorrowTest {

    /** 真异步池替身：borrow Future 由测试手工 resolve（未 resolve 即"池还没回来"）。 */
    private static NewMycatConnection fakeConnection(String targetName) {
        NewMycatConnection connection = mock(NewMycatConnection.class);
        when(connection.getTargetName()).thenReturn(targetName);
        when(connection.isClosed()).thenReturn(false);
        when(connection.update(anyString())).thenAnswer(invocation -> Future.succeededFuture());
        when(connection.close()).thenAnswer(invocation -> Future.succeededFuture());
        when(connection.abandonQuery()).thenAnswer(invocation -> Future.succeededFuture());
        return connection;
    }

    private static void await(Future<?> future, String what) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        future.onComplete(ar -> {
            if (ar.failed()) {
                failure.set(ar.cause());
            }
            latch.countDown();
        });
        assertTrue(what + " 未在上限内完成", latch.await(20, TimeUnit.SECONDS));
        assertFalse(what + " 意外失败: " + failure.get(), failure.get() != null);
    }

    @Test
    public void concurrentFirstBorrowMustRegisterBothConnections() throws Exception {
        MySQLManager manager = mock(MySQLManager.class);
        XaLog xaLog = mock(XaLog.class);
        when(xaLog.nextXid()).thenReturn("xid-1");

        // 两条借出都是未 resolve 的 Future（NATIVE 真异步池形态）
        Promise<NewMycatConnection> poolPromise1 = Promise.promise();
        Promise<NewMycatConnection> poolPromise2 = Promise.promise();
        when(manager.getConnection("ds1")).thenReturn(poolPromise1.future());
        when(manager.getConnection("ds2")).thenReturn(poolPromise2.future());
        NewMycatConnection conn1 = fakeConnection("ds1");
        NewMycatConnection conn2 = fakeConnection("ds2");

        LocalXaSqlConnection session = new LocalXaSqlConnection(
                MySQLIsolation.REPEATED_READ, () -> manager, xaLog);
        await(session.begin(), "begin");

        // 一条语句 ≥2 目标：第二借发生在第一借 Future resolve 之前
        Future<NewMycatConnection> f1 = session.getConnection("ds1");
        Future<NewMycatConnection> f2 = session.getConnection("ds2");
        assertFalse("修复前此断言即 RED：第二借未等首借落位就并发借出", f2.isComplete() && f1.isComplete());

        // 池随后回包
        poolPromise1.complete(conn1);
        poolPromise2.complete(conn2);
        await(f1, "首借");
        await(f2, "次借");

        // 两连接都被登记：首借为 localSqlConnection，次借走 map 路径
        assertSame("首借必须登记为 localSqlConnection（不得被后到者覆写成孤儿）",
                conn1, session.localSqlConnection);
        assertSame("次借必须登记进参与者 map", conn2, session.map.get("ds2"));
        assertEquals("次借 Future 必须如实返回次借连接", conn2, f2.result());

        // 无孤儿：每条借出的连接要么是 localSqlConnection 要么在 map
        List<NewMycatConnection> registered = new ArrayList<>(session.map.values());
        registered.add(session.localSqlConnection);
        assertTrue("conn1 不得成为孤儿", registered.contains(conn1));
        assertTrue("conn2 不得成为孤儿", registered.contains(conn2));
    }

    @Test
    public void sequentialBorrowAfterFirstResolvedStillWorks() throws Exception {
        MySQLManager manager = mock(MySQLManager.class);
        XaLog xaLog = mock(XaLog.class);
        when(xaLog.nextXid()).thenReturn("xid-1");

        Promise<NewMycatConnection> poolPromise1 = Promise.promise();
        Promise<NewMycatConnection> poolPromise2 = Promise.promise();
        when(manager.getConnection("ds1")).thenReturn(poolPromise1.future());
        when(manager.getConnection("ds2")).thenReturn(poolPromise2.future());
        NewMycatConnection conn1 = fakeConnection("ds1");
        NewMycatConnection conn2 = fakeConnection("ds2");

        LocalXaSqlConnection session = new LocalXaSqlConnection(
                MySQLIsolation.REPEATED_READ, () -> manager, xaLog);
        await(session.begin(), "begin");

        // 第一借 resolve 后再借第二目标（常规路径回归）
        Future<NewMycatConnection> f1 = session.getConnection("ds1");
        poolPromise1.complete(conn1);
        await(f1, "首借");
        assertSame(conn1, session.localSqlConnection);

        Future<NewMycatConnection> f2 = session.getConnection("ds2");
        poolPromise2.complete(conn2);
        await(f2, "次借");
        assertSame("首借不得被次借覆写", conn1, session.localSqlConnection);
        assertSame(conn2, session.map.get("ds2"));

        // 同目标重复借出直接复用本地连接
        Future<NewMycatConnection> f3 = session.getConnection("ds1");
        assertTrue(f3.isComplete());
        assertSame(conn1, f3.result());
    }
}
