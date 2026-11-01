package io.mycat.proxy.session;

import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.mycat.MycatDataContext;
import io.mycat.beans.mycat.TransactionType;
import io.mycat.beans.mycat.WarningMessage;
import io.mycat.buffer.BufferPool;
import io.mycat.protocol.mysql.type.MySQLErrorCode;
import io.mycat.proxy.reactor.MycatReactorThread;
import io.mycat.runtime.MycatDataContextImpl;

/**
 * I-DEBT-69 Blade F：NIO 活路径语句错误出口单点收敛锁。
 *
 * <p>{@link MySQLProxyServerSession#writeErrorEndPacketBySyncInProcessError(int)} 是 NIO 链
 * 全部 ERR_Packet 的唯一生成点（无参 default 委托它），必须与会话诊断区同源写入 Error 级条件
 * （活体真值 C1：失败语句 wc=1/ec=1，SHOW WARNINGS/ERRORS 可见）。诊断写入还必须在
 * {@code isConnected} 投递守卫<b>之外</b>——真库语义里诊断与投递成败无关。
 */
class ProxyServerSessionErrorDiagnosticTest {

    /** 覆写 switchTransaction 为 no-op：绕构造期运行时单例（proxy 测试既定姿势）。 */
    static class Ctx extends MycatDataContextImpl {
        @Override
        public void switchTransaction(TransactionType transactionSessionType) {
            // no-op
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private MySQLProxyServerSession<?> newDisconnectedSession(MycatDataContext ctx, String lastMessage) throws Exception {
        MySQLProxyServerSession session = mock(MySQLProxyServerSession.class);
        java.nio.channels.SocketChannel channel = mock(java.nio.channels.SocketChannel.class);
        when(channel.isConnected()).thenReturn(false);
        when(session.channel()).thenReturn(channel);
        when(session.getDataContext()).thenReturn(ctx);
        when(session.getLastMessage()).thenReturn(lastMessage);
        doCallRealMethod().when(session).writeErrorEndPacketBySyncInProcessError(org.mockito.ArgumentMatchers.anyInt());
        return session;
    }

    @Test
    void disconnectedChannelStillRecordsErrorIntoSessionDiagnostics() throws Exception {
        Ctx ctx = new Ctx();
        MySQLProxyServerSession<?> session = newDisconnectedSession(ctx, "Duplicate entry '1'");

        session.writeErrorEndPacketBySyncInProcessError(1062);

        List<WarningMessage> warnings = ctx.getWarnings();
        Assertions.assertEquals(1, warnings.size(),
                "ERR exit must feed session diagnostics even when delivery is guarded off (real-server semantics)");
        Assertions.assertEquals("Error", warnings.get(0).getLevel());
        Assertions.assertEquals(1062, warnings.get(0).getCode(), "diagnostic code shares source with ERR packet");
        Assertions.assertEquals("Duplicate entry '1'", warnings.get(0).getMessage());
    }

    @Test
    void connectedChannelRecordsErrorAndStillWritesPacket() throws Exception {
        Ctx ctx = new Ctx();
        MySQLProxyServerSession session = newDisconnectedSession(ctx, "No database selected");
        // 翻成存活通道：投递正控制也必须成立
        java.nio.channels.SocketChannel live = mock(java.nio.channels.SocketChannel.class);
        when(live.isConnected()).thenReturn(true);
        when(session.channel()).thenReturn(live);
        ConcurrentLinkedQueue<ByteBuffer> queue = new ConcurrentLinkedQueue<>();
        when(session.writeQueue()).thenReturn(queue);
        BufferPool pool = mock(BufferPool.class);
        when(pool.allocate(org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenAnswer(inv -> ByteBuffer.wrap(inv.getArgument(0)));
        when(session.writeBufferPool()).thenReturn(pool);
        when(session.getNextPacketId()).thenReturn((byte) 1);
        when(session.getCapabilities()).thenReturn(0);
        when(session.getIOThread()).thenReturn(mock(MycatReactorThread.class));
        // 接口内部 this.writeBytes(...) 同样被 mock 拦截；放行真实 default 才能断言投递正控制。
        doCallRealMethod().when(session).writeBytes(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.anyBoolean());

        session.writeErrorEndPacketBySyncInProcessError(1046);

        Assertions.assertEquals(1, ctx.getWarnings().size(), "live path must record Error condition");
        Assertions.assertEquals("Error", ctx.getWarnings().get(0).getLevel());
        Assertions.assertEquals(1046, ctx.getWarnings().get(0).getCode());
        Assertions.assertEquals(1, queue.size(), "live path must still deliver the ERR packet");
    }

    @Test
    void noArgDefaultDelegatesWithUnknownErrorCodeAndRecords() throws Exception {
        Ctx ctx = new Ctx();
        MySQLProxyServerSession session = newDisconnectedSession(ctx, "boom");
        doCallRealMethod().when(session).writeErrorEndPacketBySyncInProcessError();

        session.writeErrorEndPacketBySyncInProcessError();

        List<WarningMessage> warnings = ctx.getWarnings();
        Assertions.assertEquals(1, warnings.size());
        Assertions.assertEquals(MySQLErrorCode.ER_UNKNOWN_ERROR, warnings.get(0).getCode(),
                "no-arg default must delegate with ER_UNKNOWN_ERROR and land in diagnostics identically");
    }
}
