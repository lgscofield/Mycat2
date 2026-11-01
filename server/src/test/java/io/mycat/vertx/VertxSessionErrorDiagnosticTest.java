/**
 * Copyright (C) <2021>  <chen junwen>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package io.mycat.vertx;

import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.mycat.beans.mycat.TransactionType;
import io.mycat.beans.mycat.WarningMessage;
import io.mycat.runtime.MycatDataContextImpl;
import io.vertx.core.net.NetSocket;

/**
 * I-DEBT-69 Blade F：VERTX 活路径语句错误出口（{@link VertxSessionImpl} 两个
 * {@code writeErrorEndPacketBySyncInProcessError} 重载）必须把失败写成会话诊断区的
 * <b>Error 级</b>条件——真库语义（2026-11-01 双档探针 C1/C2/D0）：凡回客户端 ERR_Packet 的
 * 语句级错误必进诊断区，Code/Message 与 ERR 包同源，{@code SHOW WARNINGS/ERRORS} 与
 * {@code @@error_count} 据此可见。修复前该出口只 setLastMessage + 写包，从不喂诊断缓冲。
 *
 * <p>诊断写入必须独立于 socket 写守卫（close 后 ERR 包 no-op，但真库诊断与投递成败无关）。
 */
class VertxSessionErrorDiagnosticTest {

    /** 生产同型会话域（proxy 的 MycatDataContextImpl）；覆写 switchTransaction 绕构造期单例。 */
    static class Ctx extends MycatDataContextImpl {
        Ctx() {
            super();
        }

        @Override
        public void switchTransaction(TransactionType transactionSessionType) {
            // no-op
        }
    }

    private VertxSessionImpl newSession(Ctx ctx) {
        NetSocket socket = mock(NetSocket.class);
        return new VertxSessionImpl(ctx, socket, null);
    }

    @Test
    void codedOverloadRecordsErrorLevelConditionIntoSessionDiagnostics() {
        Ctx ctx = new Ctx();
        ctx.setLastMessage("Duplicate entry '1' for key 't.id'");
        VertxSessionImpl session = newSession(ctx);

        session.writeErrorEndPacketBySyncInProcessError(1062);

        List<WarningMessage> warnings = ctx.getWarnings();
        Assertions.assertEquals(1, warnings.size(), "ERR packet exit must feed session diagnostics (live C1)");
        Assertions.assertEquals("Error", warnings.get(0).getLevel());
        Assertions.assertEquals(1062, warnings.get(0).getCode(), "diagnostic code shares source with ERR packet");
        Assertions.assertEquals("Duplicate entry '1' for key 't.id'", warnings.get(0).getMessage());
        Assertions.assertEquals(1, ctx.getWarningCount(), "warning_count counts Error conditions too (C1: wc=1)");
    }

    @Test
    void noArgOverloadRecordsLastErrorCodeFromDataContext() {
        Ctx ctx = new Ctx();
        ctx.setLastErrorCode(1046);
        ctx.setLastMessage("No database selected");
        VertxSessionImpl session = newSession(ctx);

        session.writeErrorEndPacketBySyncInProcessError();

        List<WarningMessage> warnings = ctx.getWarnings();
        Assertions.assertEquals(1, warnings.size());
        Assertions.assertEquals("Error", warnings.get(0).getLevel());
        Assertions.assertEquals(1046, warnings.get(0).getCode(),
                "no-arg overload sources the code from lastErrorCode, same as the packet");
        Assertions.assertEquals("No database selected", warnings.get(0).getMessage());
    }

    @Test
    void diagnosticsWrittenEvenAfterCloseGuardSuppressesSocketWrite() {
        Ctx ctx = new Ctx();
        ctx.setLastMessage("boom");
        NetSocket socket = mock(NetSocket.class);
        VertxSessionImpl session = new VertxSessionImpl(ctx, socket, null);

        session.close(false, "boom");
        session.writeErrorEndPacketBySyncInProcessError(1062);

        Assertions.assertEquals(1, ctx.getWarnings().size(),
                "diagnostic record is independent of the delivery guard (real-server semantics)");
        Assertions.assertEquals("Error", ctx.getWarnings().get(0).getLevel());
    }
}
