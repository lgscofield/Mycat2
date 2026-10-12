package io.mycat.vertx.auth;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * R3-T5：caching_sha2_password 握手期 AuthMoreData 包的裸载荷编码。
 *
 * <p>MySQL AuthMoreData 帧首字节恒为 {@code 0x01}（MoreData 标记），其后语义字节区分分支：
 * {@code 0x03}=认证快速成功（fast-auth ok，随后接 OK）、{@code 0x04}=需完整认证（full-auth，
 * 随后接公钥请求/下发流程）。下发公钥时为 {@code 0x01} + PEM 的 UTF-8 字节。
 *
 * <p>本类只产出裸载荷，不含 MySQL 4 字节包头；调用方经
 * {@code MySQLPacketUtil.generateMySQLPacket(packetId, payload)} 封装后写出。
 */
public final class AuthMoreData {

    private static final byte MORE_DATA_HEADER = 0x01;
    private static final byte FAST_AUTH_SUCCESS = 0x03;
    private static final byte PERFORM_FULL_AUTHENTICATION = 0x04;

    private AuthMoreData() {
    }

    /** fast-auth 成功：{@code {0x01,0x03}}，随后由调用方写 OK。 */
    static byte[] fastOk() {
        return new byte[]{MORE_DATA_HEADER, FAST_AUTH_SUCCESS};
    }

    /** 需 full-auth：{@code {0x01,0x04}}，随后等待客户端求公钥（裸 {@code 0x02}）。 */
    static byte[] fullAuth() {
        return new byte[]{MORE_DATA_HEADER, PERFORM_FULL_AUTHENTICATION};
    }

    /** 下发 RSA 公钥：{@code 0x01} + PEM 的 UTF-8 字节。 */
    static byte[] publicKey(String pem) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MORE_DATA_HEADER);
        byte[] pemBytes = pem.getBytes(StandardCharsets.UTF_8);
        out.write(pemBytes, 0, pemBytes.length);
        return out.toByteArray();
    }
}
