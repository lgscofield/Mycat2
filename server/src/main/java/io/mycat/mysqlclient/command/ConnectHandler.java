/*
 * Copyright (c) 2011-2020 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
/**
 * Copyright (C) <2022>  <chen junwen>
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

package io.mycat.mysqlclient.command;

import io.mycat.MySQLPacketUtil;
import io.mycat.protocol.mysql.type.MySQLPayloadWriter;
import io.mycat.beans.mysql.packet.AuthPacket;
import io.mycat.mysqlclient.VertxConnection;
import io.mycat.mysqlclient.VertxConnectionPool;
import io.mycat.mysqlclient.VertxPoolConnectionImpl;
import io.mycat.vertx.VertxMySQLPacketClientResolver;
import io.netty.buffer.ByteBuf;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import io.vertx.mysqlclient.impl.MySQLDatabaseMetadata;
import io.vertx.mysqlclient.impl.protocol.CapabilitiesFlag;
import io.vertx.mysqlclient.impl.util.BufferUtils;
import io.vertx.mysqlclient.impl.util.CachingSha2Authenticator;
import io.vertx.mysqlclient.impl.util.Native41Authenticator;
import io.vertx.mysqlclient.impl.util.RsaPublicKeyEncryptor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static io.vertx.mysqlclient.impl.protocol.CapabilitiesFlag.*;
import static io.vertx.mysqlclient.impl.protocol.Packets.*;

/**
 * reference vertx mysql
 * io.vertx.mysqlclient.impl.MySQLConnectionFactory
 */
public class ConnectHandler extends VertxMySQLPacketClientResolver {
    static final int NONCE_LENGTH = 20;
    static final int AUTH_PLUGIN_DATA_PART1_LENGTH = 8;
    static final int AUTH_SWITCH_REQUEST_STATUS_FLAG = 0xFE;
    static final int AUTH_MORE_DATA_STATUS_FLAG = 0x01;
    static final int AUTH_PUBLIC_KEY_REQUEST_FLAG = 0x02;
    static final int FAST_AUTH_STATUS_FLAG = 0x03;
    static final int FULL_AUTHENTICATION_STATUS_FLAG = 0x04;

    State state = State.INIT;
    Promise<VertxConnection> promise;
    VertxPoolConnectionImpl.Config config;
    private final VertxConnectionPool pool;
    int serverCapabilitiesFlags;
    long connectionId;
    // Scramble used for the most recent auth response; needed to encrypt the password for caching_sha2 full-auth.
    private byte[] authPluginData;
    // Set while waiting for the server RSA public key during caching_sha2 full-auth over a non-secure connection.
    private boolean isWaitingForRsaPublicKey = false;

    public ConnectHandler(NetSocket socket, VertxPoolConnectionImpl.Config config, VertxConnectionPool pool) {
        super(socket, 0);
        this.config = config;
        this.pool = pool;
    }

    public Future<VertxConnection> handleInitialHandshake() {
        return Future.future(promise -> {
            ConnectHandler.this.promise = promise;
            socket.exceptionHandler(event -> promise.tryFail(event));
            socket.handler(ConnectHandler.this);
        });
    }

    static enum State {
        INIT,
        AUTH
    }

    private int initCapabilitiesFlags(String database, boolean needAttrs) {
        int capabilitiesFlags = CLIENT_SUPPORTED_CAPABILITIES_FLAGS;
        if (database != null && !database.isEmpty()) {
            capabilitiesFlags |= CLIENT_CONNECT_WITH_DB;
        }
        if (needAttrs) {
            capabilitiesFlags |= CLIENT_CONNECT_ATTRS;
        }
        if (config.isClientDeprecateEof()){
            capabilitiesFlags |= CLIENT_DEPRECATE_EOF;
        }
        return capabilitiesFlags & serverCapabilitiesFlags;
    }

    @Override
    public void handle0(int packetId, Buffer buffer, NetSocket socket) {
        ByteBuf payload = buffer.getByteBuf();
        switch (state) {
            case INIT: {
                short protocolVersion = payload.readUnsignedByte();
                String serverVersion = BufferUtils.readNullTerminatedString(payload, StandardCharsets.US_ASCII);
                MySQLDatabaseMetadata md = MySQLDatabaseMetadata.parse(serverVersion);
                boolean serverClientDeprecateEof = false;
                boolean finalClientDeprecateEof;
                if (md.majorVersion() == 5 &&
                        (md.minorVersion() < 7 || (md.minorVersion() == 7 && md.microVersion() < 5))) {
                    // EOF_HEADER has to be enabled for older MySQL version which does not support the CLIENT_DEPRECATE_EOF flag
                } else {
                    serverClientDeprecateEof = true;
                }
                if (config.isClientDeprecateEof() && !serverClientDeprecateEof) {
                    socket.close();
                    promise.tryFail("serverClientDeprecateEof not support!");
                    return;
                }

                this.connectionId = payload.readUnsignedIntLE();

                // read first part of scramble
                byte[] authPluginData = new byte[NONCE_LENGTH];
                payload.readBytes(authPluginData, 0, AUTH_PLUGIN_DATA_PART1_LENGTH);

                //filler
                payload.readByte();

                // read lower 2 bytes of Capabilities flags
                int lowerServerCapabilitiesFlags = payload.readUnsignedShortLE();

                short characterSet = payload.readUnsignedByte();

                int statusFlags = payload.readUnsignedShortLE();

                // read upper 2 bytes of Capabilities flags
                int capabilityFlagsUpper = payload.readUnsignedShortLE();
                this.serverCapabilitiesFlags = (lowerServerCapabilitiesFlags | (capabilityFlagsUpper << 16));

                // length of the combined auth_plugin_data (scramble)
                short lenOfAuthPluginData;
                boolean isClientPluginAuthSupported = (serverCapabilitiesFlags & CapabilitiesFlag.CLIENT_PLUGIN_AUTH) != 0;
                if (isClientPluginAuthSupported) {
                    lenOfAuthPluginData = payload.readUnsignedByte();
                } else {
                    payload.readerIndex(payload.readerIndex() + 1);
                    lenOfAuthPluginData = 0;
                }

                // 10 bytes reserved
                payload.readerIndex(payload.readerIndex() + 10);

                // Rest of the plugin provided data
                payload.readBytes(authPluginData, AUTH_PLUGIN_DATA_PART1_LENGTH, Math.max(NONCE_LENGTH - AUTH_PLUGIN_DATA_PART1_LENGTH, lenOfAuthPluginData - 9));
                payload.readByte(); // reserved byte

                // we assume the server supports auth plugin
                final String serverAuthPluginName = BufferUtils.readNullTerminatedString(payload, StandardCharsets.UTF_8);

                boolean upgradeToSsl;

                this.authPluginData = authPluginData;
                sendHandshakeResponseMessage(config.getUsername(), config.getPassword(), config.getDatabase(), authPluginData, serverAuthPluginName,
                        (initCapabilitiesFlags(config.getDatabase(), false)),
                        33, Collections.emptyMap());
                state = State.AUTH;
                System.out.println();
            }
            break;
            case AUTH: {
                int header = payload.getUnsignedByte(payload.readerIndex());
                switch (header) {
                    case OK_PACKET_HEADER:
                        promise.tryComplete(new VertxConnection(this.socket, this.connectionId, config,pool));
                        break;
                    case ERROR_PACKET_HEADER:
                        promise.tryFail(buffer.toString());
                        break;
                    case AUTH_SWITCH_REQUEST_STATUS_FLAG:
                        handleAuthSwitchRequest(payload);
                        break;
                    case AUTH_MORE_DATA_STATUS_FLAG:
                        handleAuthMoreData(payload);
                        break;
                    default:
                        promise.tryFail(new IllegalStateException("Unhandled auth packet with header: " + header));
                        break;
                }
            }
            break;
        }

    }

    @Override
    public void onException(Throwable throwable) {
        promise.tryFail(throwable);
    }

    /**
     * Server asked the client to switch authentication plugin (Protocol::AuthSwitchRequest, header 0xFE). Recompute
     * the auth response with the requested plugin and the fresh scramble the server supplied, then send it back.
     */
    private void handleAuthSwitchRequest(ByteBuf payload) {
        payload.skipBytes(1); // status flag, always 0xFE
        String pluginName = BufferUtils.readNullTerminatedString(payload, StandardCharsets.UTF_8);
        byte[] nonce = new byte[NONCE_LENGTH];
        int readable = payload.readableBytes();
        payload.readBytes(nonce, 0, Math.min(NONCE_LENGTH, readable));
        this.authPluginData = nonce;
        byte[] password = config.getPassword().getBytes(StandardCharsets.UTF_8);
        byte[] authResponse;
        switch (pluginName) {
            case "mysql_native_password":
                authResponse = Native41Authenticator.encode(password, nonce);
                break;
            case "caching_sha2_password":
                authResponse = CachingSha2Authenticator.encode(password, nonce);
                break;
            case "mysql_clear_password":
                authResponse = password;
                break;
            default:
                promise.tryFail(new UnsupportedOperationException("Unsupported authentication method: " + pluginName));
                return;
        }
        sendBytesAsPacket(authResponse);
    }

    /**
     * Server sent extra authentication data (Protocol::AuthMoreData, header 0x01). For caching_sha2_password this is
     * either a fast-auth result (0x03, wait for the following OK/ERR) or a full-auth request (0x04). Since this
     * client never upgrades to TLS, full-auth requires fetching the server RSA public key (0x02) and sending the
     * password encrypted with that key over the scramble.
     */
    private void handleAuthMoreData(ByteBuf payload) {
        payload.skipBytes(1); // status flag, always 0x01
        if (isWaitingForRsaPublicKey) {
            byte[] rest = new byte[payload.readableBytes()];
            payload.readBytes(rest);
            String serverRsaPublicKey = new String(rest, StandardCharsets.UTF_8);
            byte[] password = config.getPassword().getBytes(StandardCharsets.UTF_8);
            byte[] encrypted;
            try {
                byte[] passwordInput = Arrays.copyOf(password, password.length + 1); // append 0x00 (NULL) terminator
                encrypted = RsaPublicKeyEncryptor.encrypt(passwordInput, authPluginData, serverRsaPublicKey);
            } catch (Exception e) {
                promise.tryFail(e);
                return;
            }
            sendBytesAsPacket(encrypted);
        } else {
            byte flag = payload.readByte();
            if (flag == FULL_AUTHENTICATION_STATUS_FLAG) {
                // Non-secure connection: request the server RSA public key to encrypt the password.
                isWaitingForRsaPublicKey = true;
                sendBytesAsPacket(new byte[]{(byte) AUTH_PUBLIC_KEY_REQUEST_FLAG});
            } else if (flag == FAST_AUTH_STATUS_FLAG) {
                // Fast-auth succeeded, the server will follow with an OK packet.
            } else {
                promise.tryFail(new UnsupportedOperationException("Unsupported flag for AuthMoreData : " + flag));
            }
        }
    }

    private void sendBytesAsPacket(byte[] payload) {
        socket.write(Buffer.buffer(MySQLPacketUtil.generateMySQLPacket(packetId + 1, payload)));
    }

    private void sendHandshakeResponseMessage(String username,
                                              String password,
                                              String database,
                                              byte[] nonce,
                                              String clientPluginName,
                                              int clientCapabilitiesFlags,
                                              int collationId,
                                              Map<String, String> clientConnectionAttributes) {
        AuthPacket authPacket = new AuthPacket();
        authPacket.setCapabilities(clientCapabilitiesFlags);
        authPacket.setMaxPacketSize(PACKET_PAYLOAD_LENGTH_LIMIT);
        authPacket.setCharacterSet((byte) collationId);
        authPacket.setUsername(username);
        authPacket.setDatabase(database);
        authPacket.setClientConnectAttrs(clientConnectionAttributes);
        String authMethod = clientPluginName;
        if (password.isEmpty()) {
            authPacket.setPassword(new byte[]{});
        } else {
            byte[] authResponse;
            switch (authMethod) {
                case "mysql_native_password":
                    authResponse = Native41Authenticator.encode(password.getBytes(StandardCharsets.UTF_8), nonce);
                    break;
                case "caching_sha2_password":
                    authResponse = CachingSha2Authenticator.encode(password.getBytes(StandardCharsets.UTF_8), nonce);
                    break;
                case "mysql_clear_password":
                    authResponse = password.getBytes(StandardCharsets.UTF_8);
                    break;
                default:
                    authMethod = "mysql_native_password";
                    authResponse = Native41Authenticator.encode(password.getBytes(StandardCharsets.UTF_8), nonce);
                    break;
            }
            authPacket.setPassword(authResponse);
        }
        authPacket.setAuthPluginName(authMethod);
        MySQLPayloadWriter mySQLPayloadWriter = new MySQLPayloadWriter();
        authPacket.writePayload(mySQLPayloadWriter);
        super.socket.write(Buffer.buffer(MySQLPacketUtil.generateMySQLPacket(++packetId, mySQLPayloadWriter.toByteArray())));
    }
}
