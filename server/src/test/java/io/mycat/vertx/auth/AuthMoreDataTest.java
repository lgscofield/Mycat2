package io.mycat.vertx.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R3-T5：caching_sha2 AuthMoreData 裸载荷编码（不含 MySQL 4 字节包头）。
 * 锁 fast-ok(0x01,0x03)/full-auth(0x01,0x04)/公钥下发(0x01+PEM) 三种帧形（Review Focus #1）。
 */
public class AuthMoreDataTest {

    @Test
    public void shape() {
        assertArrayEquals(new byte[]{0x01, 0x03}, AuthMoreData.fastOk());
        assertArrayEquals(new byte[]{0x01, 0x04}, AuthMoreData.fullAuth());
        byte[] pk = AuthMoreData.publicKey("PEM");
        assertEquals(0x01, pk[0] & 0xff);
        assertArrayEquals("PEM".getBytes(StandardCharsets.UTF_8),
                Arrays.copyOfRange(pk, 1, pk.length));
    }
}
