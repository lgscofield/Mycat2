package io.mycat.mysqlclient;

import io.mycat.MySQLPacketUtil;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-69 Blade G（刀2）：NATIVE 池 OK 包 warnings 字段解析锁。
 *
 * <p>真值来源：MySQL 协议 OK packet 第 4 段 warnings（2 bytes，PROTOCOL_41 恒有）。
 * 既有 {@code decodeOkPacketPayload} 注释自证「not used for now」直接丢弃该字段，
 * 导致 NATIVE 型 datasource 的 DML 告警计数在代理侧整体失踪。本锁钉新解析方法。
 */
public class PacketUtilOkWarningsTest {

    @Test
    public void readsWarningsFieldFromOkPayload() {
        // generateOk 出包顺序即协议顺序：header/affected/lastInsertId/status(2)/warnings(2)
        byte[] payload = MySQLPacketUtil.generateOk(0, 2, 0x0002, 1, 7, true, true, false, "");

        int warnings = PacketUtil.decodeOkPacketWarnings(Buffer.buffer(payload));

        Assertions.assertEquals(2, warnings, "OK 包 warnings 字段必须可解析（协议第 4 段）");
    }

    @Test
    public void truncatedPayloadWithoutWarningsFieldDegradesToZero() {
        // 截断形态：status 后即 EOF，无 warnings 2 字节——不得越界抛错，退化为 0
        byte[] payload = new byte[]{0x00, 0x01, 0x07, 0x02, 0x00};

        int warnings = PacketUtil.decodeOkPacketWarnings(Buffer.buffer(payload));

        Assertions.assertEquals(0, warnings, "残长不足 2 字节必须退化 0，不得反抛");
    }

    @Test
    public void zeroWarningsParsesCleanly() {
        byte[] payload = MySQLPacketUtil.generateOk(0, 0, 0x0002, 3, 0, true, true, false, "");

        Assertions.assertEquals(0, PacketUtil.decodeOkPacketWarnings(Buffer.buffer(payload)));
    }
}
