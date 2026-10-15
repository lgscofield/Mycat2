/**
 * Copyright (C) <2021>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.vertx;

import io.mycat.*;
import io.mycat.config.MySQLServerCapabilityFlags;
import io.mycat.config.VersionProfile;
import io.mycat.protocol.mysql.type.MySQLIsolation;
import io.mycat.beans.mysql.packet.AuthPacket;
import io.mycat.config.UserConfig;
import io.mycat.mycatmysql.MycatVertxMySQLHandler;
import io.mycat.mycatmysql.MycatVertxMysqlSession;
import io.mycat.proxy.handler.front.MySQLClientAuthHandler;
import io.mycat.proxy.handler.front.SocketAddressUtil;
import io.mycat.protocol.mysql.type.ServerVersion;
import io.mycat.runtime.MycatDataContextImpl;
import io.mycat.protocol.mysql.auth.MysqlNativePasswordPluginUtil;
import io.mycat.vertx.auth.AuthMoreData;
import io.mycat.vertx.auth.CachingSha2AuthCache;
import io.mycat.vertx.auth.CachingSha2ServerAuth;
import io.mycat.vertx.auth.RsaKeyPairProvider;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static io.mycat.protocol.mysql.type.MySQLErrorCode.ER_ACCESS_DENIED_ERROR;
import static io.mycat.vertx.VertxMySQLPacketResolver.readInt;

/**
 * 前端握手认证处理器。R3 起支持两套原生认证插件，按握手宣称的 {@link VersionProfile#authPlugin()}
 * 由客户端选择回送：
 * <ul>
 *   <li>{@code mysql_native_password}（含插件名缺省）：{@code scramble411} 直校（既有行为）；</li>
 *   <li>{@code caching_sha2_password}：warmed 命中走 fast-auth，未命中走 RSA full-auth 状态机。</li>
 * </ul>
 * 出站包序号严格按"收到的入站 packetId + 1"链式递增，交给会话后由 {@code getNextPacketId()} 续接。
 */
public class VertxMySQLAuthHandler implements Handler<Buffer> {
    private static final Logger LOGGER = LoggerFactory.getLogger(VertxMySQLAuthHandler.class);

    private static final String PLUGIN_NATIVE = "mysql_native_password";
    private static final String PLUGIN_CACHING_SHA2 = "caching_sha2_password";
    private static final ServerVersion OAEP_MIN_VERSION = new ServerVersion(8, 0, 5);

    /** caching_sha2 full-auth 子状态。 */
    private enum AuthStage {
        /** 等待首个 AuthResponse（native 或 caching_sha2 的初始 scramble）。 */
        AUTH_RESPONSE,
        /** 已下发 full-auth 请求，等客户端裸 {@code 0x02}（求公钥）。 */
        SHA_FULL_ASK_PUBKEY,
        /** 已下发公钥，等客户端 RSA 密文口令。 */
        SHA_FULL_RECV_PW
    }

    final NetSocket socket;
    final VertxMycatServer.MycatSessionManager mycatSessionManager;
    private final MycatDataContext mycatDataContext;
    private byte[][] seedParts;
    Buffer buffer = Buffer.buffer();
    private AuthPacket authPacket;

    private AuthStage stage = AuthStage.AUTH_RESPONSE;
    private Authenticator authenticator;
    private String username;
    private String host;
    private String rightPassword;
    private byte[] seed;
    /** 生效版本 ≥8.0.5 时 full-auth 用 OAEP 解密，否则 PKCS1。 */
    private boolean oaep;
    /** R4 前端 TLS：服务端是否在握手广告 CLIENT_SSL（由 defaultServerCapabilities 派生）。 */
    private final boolean sslAdvertised;
    /** 本连接是否已完成 STARTTLS 升级：升级后首包不再按 SSLRequest 解释，避免二次升级。 */
    private boolean tlsUpgraded = false;

    public VertxMySQLAuthHandler(NetSocket socket, int defaultServerCapabilities, VertxMycatServer.MycatSessionManager mycatSessionManager) {
        this.socket = socket;
        this.mycatSessionManager = mycatSessionManager;
        this.sslAdvertised = (defaultServerCapabilities & MySQLServerCapabilityFlags.CLIENT_SSL) != 0;
        this.mycatDataContext = new MycatDataContextImpl();
        this.seedParts = MysqlNativePasswordPluginUtil.nextSeedBuild();
        VersionProfile versionProfile = MetaClusterCurrent.wrapper(VersionProfile.class);
        if (versionProfile != null) {
            this.oaep = ServerVersion.parseVersion(versionProfile.serverVersionString())
                    .compareTo(OAEP_MIN_VERSION) >= 0;
        }
        byte[] handshakePacket = MySQLClientAuthHandler.createHandshakePayload(mycatDataContext.getSessionId(), defaultServerCapabilities, seedParts, versionProfile);
        try {
            socket.write(Buffer.buffer(MySQLPacketUtil.generateMySQLPacket(0, handshakePacket)));
            socket.handler(this);
        } catch (Exception exception) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("{} is closed", socket.remoteAddress(), exception);
            }
            socket.close();
        }
    }

    @Override
    public void handle(Buffer event) {
        buffer.appendBuffer(event);
        if (buffer.length() <= 3) {
            return;
        }
        int length = readInt(buffer, 0, 3);
        if (length != buffer.length() - 4) {
            return;
        }
        int packetId = buffer.getUnsignedByte(3);
        Buffer payload = buffer.slice(4, buffer.length());
        ReadView readView = new ReadView(payload);
        switch (stage) {
            case AUTH_RESPONSE: {
                // R4 前端 TLS：升级前收到的首个置 CLIENT_SSL 的包 = Protocol::SSLRequest（截断 HandshakeResponse）。
                // 上面的分帧已保证 buffer 恰为一个完整包（length==buffer.length()-4，无残留），
                // 故此处按 MySQL 包边界吃满整个 SSLRequest 后再就地 socket.upgradeToSsl 升级，
                // 避免同读合并吞掉尾部 ClientHello（真 Connector/J 实测首读 tail=0，不合并）。
                // 升级成功后续 HandshakeResponse 走 Vert.x 解密流再入本 handler（tlsUpgraded=true 则不再判 SSLRequest）。
                if (sslAdvertised && !tlsUpgraded
                        && (readInt(payload, 0, 4) & MySQLServerCapabilityFlags.CLIENT_SSL) != 0) {
                    SSLRequest sslRequest = new SSLRequest();
                    sslRequest.readPayload(readView);
                    buffer = Buffer.buffer();
                    socket.upgradeToSsl(ar -> {
                        if (ar.failed()) {
                            LOGGER.error("frontend STARTTLS upgrade failed for " + socket.remoteAddress(),
                                    ar.cause());
                            socket.close();
                            return;
                        }
                        this.tlsUpgraded = true;
                        socket.handler(this);
                        socket.resume();
                    });
                    return;
                }
                this.authPacket = new AuthPacket();
                authPacket.readPayload(readView);
                this.username = authPacket.getUsername();
                this.host = SocketAddressUtil.simplySocketAddress(socket.remoteAddress().toString());
                this.seed = seedParts[2];
                if (MetaClusterCurrent.exist(Authenticator.class)) {
                    this.authenticator = MetaClusterCurrent.wrapper(Authenticator.class);
                    Authenticator.AuthInfo info = authenticator.getPassword(username, host);
                    if (!info.isOk()) {
                        // 用户不存在 / IP 被禁：在任何插件校验前干净拒绝，避免 null 口令走 native 空口令放行
                        // 或 caching_sha2 full-auth 旁路导致握手挂起（getUserInfo 为 null 的 NPE）与污染 warmed 缓存。
                        writeAccessDenied(packetId,
                                "Access denied for user '" + username + "'@'" + host + "'");
                        return;
                    }
                    this.rightPassword = info.getRightPassword();
                }
                String plugin = authPacket.getAuthPluginName();
                if (plugin == null || PLUGIN_NATIVE.equalsIgnoreCase(plugin)) {
                    authenticateNative(packetId);
                } else if (PLUGIN_CACHING_SHA2.equalsIgnoreCase(plugin)) {
                    onCachingSha2AuthResponse(packetId);
                } else {
                    writeAccessDenied(packetId, "Authentication plugin '" + plugin + "' is not supported");
                }
                buffer = Buffer.buffer();
                break;
            }
            case SHA_FULL_ASK_PUBKEY: {
                // 非加密连接下客户端回裸 0x02 求公钥；下发 AuthMoreData(0x01 + PEM)。
                byte[] pk = AuthMoreData.publicKey(RsaKeyPairProvider.getInstance().publicKeyPem());
                socket.write(Buffer.buffer(MySQLPacketUtil.generateMySQLPacket(packetId + 1, pk)));
                stage = AuthStage.SHA_FULL_RECV_PW;
                buffer = Buffer.buffer();
                break;
            }
            case SHA_FULL_RECV_PW: {
                byte[] cipher = readView.readEOFStringBytes();
                if (fullAuthVerify(cipher)) {
                    CachingSha2AuthCache.getInstance().markWarmed(username);
                    completeAuthentication(packetId); // OK 在 packetId+1
                } else {
                    writeAccessDenied(packetId,
                            "Access denied for user '" + username + "'@'" + host + "' (using password: YES)");
                }
                buffer = Buffer.buffer();
                break;
            }
        }
    }

    /** caching_sha2 收到初始 scramble：warmed 且 fast-auth 通过→AuthMoreData(fast-ok)+OK；否则→AuthMoreData(full-auth) 进 RSA 流程。 */
    private void onCachingSha2AuthResponse(int packetId) {
        byte[] scramble = authPacket.getPassword();
        if (authenticator != null
                && CachingSha2AuthCache.getInstance().isWarmed(username)
                && CachingSha2ServerAuth.fastAuthOk(rightPassword, seed, scramble)) {
            socket.write(Buffer.buffer(MySQLPacketUtil.generateMySQLPacket(packetId + 1, AuthMoreData.fastOk())));
            completeAuthentication(packetId + 1); // AuthMoreData 占 packetId+1，OK 顺延 +2
        } else if (authenticator == null) {
            // 未配置用户存储（示例/嵌入式场景）：沿用 native 的放行语义，直接进入会话。
            completeAuthentication(packetId);
        } else {
            socket.write(Buffer.buffer(MySQLPacketUtil.generateMySQLPacket(packetId + 1, AuthMoreData.fullAuth())));
            stage = AuthStage.SHA_FULL_ASK_PUBKEY;
        }
    }

    /** native 直校：scramble411（既有行为），成功进会话，失败 ERR。 */
    private void authenticateNative(int packetId) {
        if (authenticator != null && !checkPassword(rightPassword, authPacket.getPassword())) {
            writeAccessDenied(packetId,
                    "Access denied for user '" + username + "'@'" + host + "' (using password: YES)");
            return;
        }
        completeAuthentication(packetId);
    }

    /** full-auth 口令校验；authenticator 缺省时按放行语义返回 true。 */
    private boolean fullAuthVerify(byte[] cipher) {
        if (authenticator == null) {
            return true;
        }
        return CachingSha2ServerAuth.fullAuthOk(rightPassword, seed, cipher,
                RsaKeyPairProvider.getInstance().privateKey(), oaep);
    }

    /**
     * 认证成功：装配上下文、切换到查询处理器并写 OK。
     *
     * @param lastWrittenSeq 上一枚已出站包的序号（native/直接放行时为入站 packetId；
     *                       fast-ok 时为 AuthMoreData 序号），OK 包取 {@code lastWrittenSeq + 1}。
     */
    private void completeAuthentication(int lastWrittenSeq) {
        buffer = null;
        UserConfig userInfo;
        if (authenticator != null) {
            userInfo = authenticator.getUserInfo(username);
        } else {
            userInfo = new UserConfig();
        }
        if (userInfo == null) {
            // 防御：即便上游校验放行，用户配置缺失也不让 orElse 实参急切求值触发 NPE。
            userInfo = new UserConfig();
        }
        InetSocketAddress remoteAddress = new InetSocketAddress(socket.remoteAddress().host(), socket.remoteAddress().port());
        // I-DEBT-47：前端已完成 STARTTLS 升级时，把本会话协商的 cipher/protocol 回填到会话态，
        // 供 ShowStatusSQLHandler 回报 Ssl_cipher/Ssl_version（此刻 TLS 握手已随加密 HandshakeResponse 完成，sslSession 可用）。
        if (tlsUpgraded) {
            recordSslSessionState(socket, mycatDataContext);
        }
        mycatDataContext.setUser(new MycatUser(username, null, null, host, remoteAddress, userInfo));
        mycatDataContext.useShcema(Optional.ofNullable(authPacket.getDatabase()).orElse(userInfo.getSchema()));
        mycatDataContext.setServerCapabilities(authPacket.getCapabilities());
        mycatDataContext.setAutoCommit(true);
        mycatDataContext.setIsolation(MySQLIsolation.REPEATED_READ);
        mycatDataContext.setCharsetIndex(authPacket.getCharacterSet());
        MycatVertxMysqlSession vertxSession = new MycatVertxMysqlSession(mycatDataContext, socket, this.mycatSessionManager);
        socket.handler(new VertxMySQLPacketResolver(socket, new MycatVertxMySQLHandler(vertxSession)));
        vertxSession.setPacketId(lastWrittenSeq);

        mycatSessionManager.addSession(vertxSession);

        vertxSession.writeOkEndPacket();
    }

    /**
     * I-DEBT-47：STARTTLS 升级成功后，把前端 TLS 会话协商到的 cipher/protocol 回填进会话 processStateMap，
     * 供 {@code ShowStatusSQLHandler} 回报 Ssl_cipher/Ssl_version。sslSession 不可用（异常/空）时静默降级，
     * 绝不拖垮认证主流程。
     */
    static void recordSslSessionState(NetSocket socket, MycatDataContext dataContext) {
        try {
            SSLSession ssl = socket.sslSession();
            if (ssl != null) {
                Map<String, Object> state = dataContext.getProcessStateMap();
                if (state != null) {
                    state.put("SSL_CIPHER", ssl.getCipherSuite());
                    state.put("SSL_VERSION", ssl.getProtocol());
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("record frontend ssl session state failed", e);
        }
    }

    private void writeAccessDenied(int inboundPacketId, String message) {
        LOGGER.error(message);
        socket.write(Buffer.buffer(MySQLPacketUtil.generateMySQLPacket(inboundPacketId + 1,
                MySQLPacketUtil.generateError(ER_ACCESS_DENIED_ERROR, message, 0))));
        socket.end();
    }

    private boolean checkPassword(String rightPassword, byte[] password) {
        if (rightPassword == null || rightPassword.length() == 0) {
            return (password == null || password.length == 0);
        }
        if (password == null || password.length == 0) {
            return false;
        }
        byte[] encryptPass = MysqlNativePasswordPluginUtil.scramble411(rightPassword, seedParts[2]);
        return Arrays.equals(password, encryptPass);
    }


}
