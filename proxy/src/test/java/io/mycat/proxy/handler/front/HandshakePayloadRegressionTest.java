package io.mycat.proxy.handler.front;

import io.mycat.beans.mysql.packet.HandshakePacket;
import io.mycat.config.MySQLServerCapabilityFlags;
import io.mycat.config.VersionProfile;
import io.mycat.config.VersionProfileFactory;
import io.mycat.protocol.mysql.type.MySQLPayloadWriter;
import io.mycat.protocol.mysql.type.MySQLVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * I-VE-1 握手字节零回归锁：默认 MYSQL_5_7 画像下 {@code createHandshakePayload} 产出的字节，
 * 必须与改动前用硬编码常量（version=5.7.33-mycat-2.0/charset=8/statusFlags=2/plugin=native）
 * 手工序列化的参照字节逐字节相同，证明抽象化改造未引入握手行为漂移。
 */
public class HandshakePayloadRegressionTest {

    @Test
    public void defaultProfileProducesIdenticalHandshakeBytes() {
        long sid = 42L;
        int caps = MySQLServerCapabilityFlags.getDefaultServerCapabilities();
        byte[][] seed = {"12345678".getBytes(), "abcdefghijklmnopqrst".getBytes(), "u".getBytes()};
        VersionProfile p = VersionProfileFactory.defaultProfile(); // MYSQL_5_7

        // 参照：按改动前的硬编码字段直接拼装握手包
        HandshakePacket ref = new HandshakePacket();
        ref.setProtocolVersion(MySQLVersion.PROTOCOL_VERSION);
        ref.setServerVersion("5.7.33-mycat-2.0");
        ref.setConnectionId(sid);
        ref.setAuthPluginDataPartOne(new String(seed[0]));
        ref.setCapabilities(new MySQLServerCapabilityFlags(caps));
        ref.setHasPartTwo(true);
        ref.setCharacterSet(8);
        ref.setStatusFlags(2);
        ref.setAuthPluginDataLen(21);
        ref.setAuthPluginDataPartTwo(new String(seed[1]));
        ref.setAuthPluginName("mysql_native_password");
        MySQLPayloadWriter refWriter = new MySQLPayloadWriter();
        ref.writePayload(refWriter);

        byte[] actual = MySQLClientAuthHandler.createHandshakePayload(sid, caps, seed, p);
        assertArrayEquals(refWriter.toByteArray(), actual);
    }
}
