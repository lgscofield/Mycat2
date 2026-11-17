package io.mycat.beans.mycat;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-PR-1：准入拒绝码 MYCAT-3007。
 * 不复用 3006（ERR_GET_CONNECTION_POOL_FULL 语义是后端连接池满，且零消费者），
 * 不用 MySQL 原生 1040/1041/1203（会误导客户端驱动的重试/重连策略）。
 */
public class MycatErrorCodeIoLimitTest {

    private static final String PREFIX = "ERROR-CODE: [MYCAT-3007][ERR_IO_CONCURRENCY_LIMIT] ";

    @Test
    public void ioLimitCodeIs3007() {
        assertEquals(3007, MycatErrorCode.ERR_IO_CONCURRENCY_LIMIT);
    }

    @Test
    public void ioLimitCodeIsRegisteredInMap() {
        assertTrue(MycatErrorCode.errorCode(3007).contains("[MYCAT-3007][ERR_IO_CONCURRENCY_LIMIT]"),
                "反射静态块必须收录该常量，否则 createMycatException 会走无前缀分支");
    }

    @Test
    public void ioLimitExceptionCarriesPrefix() {
        String msg = MycatErrorCode.createMycatException(3007, "busy").getMessage();
        assertTrue(msg.startsWith(PREFIX), "实际=" + msg);
        assertTrue(msg.contains("busy"));
    }

    @Test
    public void code3007DoesNotCollide() throws IllegalAccessException {
        for (Field f : MycatErrorCode.class.getFields()) {
            if (f.getName().equals("ERR_IO_CONCURRENCY_LIMIT")) {
                continue;
            }
            int other = f.getInt(null);
            assertTrue(other != 3007,
                    "3007 与 " + f.getName() + " 重码（该类已有 3014/3026/3030 三处重码，不得再加）");
        }
    }
}