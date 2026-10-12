package io.mycat.vertx;

import io.mycat.config.MySQLServerCapabilityFlags;
import org.junit.Assert;
import org.junit.Test;

/**
 * R4/Task2 锁：Protocol::SSLRequest 包解析（MySQL 官方 Connection Phase 文档）。
 * 32 字节 = int<4> client_flag（须含 CLIENT_SSL）· int<4> max_packet_size · int<1> character_set · string[23] filler(全 0)。
 */
public class SSLRequestTest {

    private static byte[] le(int v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF), (byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF)};
    }

    /** 拼一个合规 SSLRequest 的 32 字节 payload。 */
    private static io.vertx.core.buffer.Buffer sslRequestPayload(int clientFlag) {
        io.vertx.core.buffer.Buffer b = io.vertx.core.buffer.Buffer.buffer();
        b.appendBytes(le(clientFlag));       // client_flag int<4>
        b.appendBytes(le(16777216));          // max_packet_size int<4>
        b.appendByte((byte) 45);              // character_set int<1> (45=utf8mb4)
        b.appendBytes(new byte[23]);          // filler 23×0
        return b;
    }

    @Test
    public void parsesSslRequestFields() {
        int clientFlag = MySQLServerCapabilityFlags.CLIENT_SSL
                | MySQLServerCapabilityFlags.CLIENT_PROTOCOL_41
                | MySQLServerCapabilityFlags.CLIENT_SECURE_CONNECTION; // 2048|512|32768 = 35328
        SSLRequest req = new SSLRequest();
        req.readPayload(new ReadView(sslRequestPayload(clientFlag)));
        Assert.assertEquals(clientFlag, req.getCapabilities());
        Assert.assertEquals(16777216, req.getMaxPacketSize());
        Assert.assertEquals(45, req.getCharacterSet());
        Assert.assertTrue("CLIENT_SSL 位必须识别为 TLS 升级请求", req.isClientSsl());
    }

    @Test
    public void notSslRequestWhenClientSslAbsent() {
        int clientFlag = MySQLServerCapabilityFlags.CLIENT_PROTOCOL_41; // 无 CLIENT_SSL
        SSLRequest req = new SSLRequest();
        req.readPayload(new ReadView(sslRequestPayload(clientFlag)));
        Assert.assertFalse("未置 CLIENT_SSL 不应当作 SSLRequest", req.isClientSsl());
    }
}
