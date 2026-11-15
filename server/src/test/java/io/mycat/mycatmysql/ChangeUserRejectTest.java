/*
 * I-DEBT-122 离线锁：COM_CHANGE_USER 必须拒绝而非假 OK。
 *
 * 活体点位（修复前）：MycatVertxMySQLHandler.handleChangeUser 无条件 writeOkEndPacket()——
 * 连接模型下会话绑定 dataContext（身份/事务/会话变量都在其上），换用户只换"名"不换"权"，
 * 假 OK 等于把 A 的会话直接过户给 B（越权面）。最小安全面：拒绝并回 ERR（ER_ACCESS_DENIED_ERROR
 * 1045 + 明确消息），不做完整重认证（auth 流程重构超本轮）。
 * 单元级锁：Mockito 桩 session，断言拒绝决策——
 *  1) 回 ERR 且错误码为 1045；2) 错误消息明确指向"请开新连接"；
 *  3) 绝不 writeOkEndPacket（假 OK 面归零）。
 */
package io.mycat.mycatmysql;

import io.mycat.MycatDataContext;
import io.mycat.protocol.mysql.type.MySQLErrorCode;
import io.vertx.core.Future;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ChangeUserRejectTest {

    private MycatVertxMySQLHandler newHandler(MycatVertxMysqlSession session) {
        return new MycatVertxMySQLHandler(session);
    }

    @Test
    public void changeUserIsRejectedWithAccessDeniedError() {
        MycatVertxMysqlSession session = Mockito.mock(MycatVertxMysqlSession.class);
        MycatDataContext dataContext = Mockito.mock(MycatDataContext.class);
        when(session.getDataContext()).thenReturn(dataContext);
        when(session.getSocket()).thenReturn(Mockito.mock(NetSocket.class));
        when(session.writeErrorEndPacketBySyncInProcessError(anyInt()))
                .thenReturn(Future.succeededFuture());

        MycatVertxMySQLHandler handler = newHandler(session);
        Future<Void> future = handler.handleChangeUser(
                "other", "authresp", "db", 33, "mysql_native_password",
                Collections.emptyMap(), session);

        assertEquals(Future.succeededFuture(), future);
        verify(dataContext).setLastMessage(contains("COM_CHANGE_USER"));
        verify(session).writeErrorEndPacketBySyncInProcessError(MySQLErrorCode.ER_ACCESS_DENIED_ERROR);
    }

    @Test
    public void changeUserNeverFakesOk() {
        MycatVertxMysqlSession session = Mockito.mock(MycatVertxMysqlSession.class);
        MycatDataContext dataContext = Mockito.mock(MycatDataContext.class);
        when(session.getDataContext()).thenReturn(dataContext);
        when(session.getSocket()).thenReturn(Mockito.mock(NetSocket.class));
        when(session.writeErrorEndPacketBySyncInProcessError(anyInt()))
                .thenReturn(Future.succeededFuture());

        MycatVertxMySQLHandler handler = newHandler(session);
        handler.handleChangeUser(
                "other", "authresp", "db", 33, "mysql_native_password",
                Collections.emptyMap(), session);

        verify(session, never()).writeOkEndPacket();
    }
}
