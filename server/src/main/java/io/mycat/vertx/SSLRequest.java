package io.mycat.vertx;

import io.mycat.config.MySQLServerCapabilityFlags;
import io.mycat.protocol.mysql.packet.MySQLPayloadReadView;

/**
 * R4 前端 TLS：Protocol::SSLRequest（客户端在明文握手后请求升级到 TLS 的截断 HandshakeResponse）。
 * 见 design §2 / MySQL 官方 Connection Phase。
 */
public class SSLRequest {
    private int capabilities;
    private int maxPacketSize;
    private int characterSet;

    public void readPayload(MySQLPayloadReadView buffer) {
        // Protocol::SSLRequest = HandshakeResponse41 截断到 username 之前：
        // int<4> client_flag · int<4> max_packet_size · int<1> character_set · string[23] filler。
        capabilities = (int) buffer.readFixInt(4);
        maxPacketSize = (int) buffer.readFixInt(4);
        characterSet = buffer.readByte() & 0xFF;
        buffer.readBytes(23); // filler，全 0，丢弃
    }

    public boolean isClientSsl() {
        return (capabilities & MySQLServerCapabilityFlags.CLIENT_SSL) != 0;
    }

    public int getCapabilities() {
        return capabilities;
    }

    public int getMaxPacketSize() {
        return maxPacketSize;
    }

    public int getCharacterSet() {
        return characterSet;
    }
}
