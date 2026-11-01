package io.mycat.mysqlclient;

import io.mycat.MySQLPacketUtil;
import io.mycat.newquery.SqlResult;
import io.mycat.protocol.mysql.type.MySQLPayloadWriter;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * I-DEBT-69 Blade G（刀2）：NATIVE 池 OK 包告警明细往返集成锁（vertx NetServer 假后端）。
 *
 * <p>假后端按协议脚本应答：握手 → OK → 对 COM_QUERY 按 SQL 文本回 OK（warnings 计数可配）
 * 或 SHOW WARNINGS 三列结果集。锁定两条契约：
 * ① OK 包 warnings&gt;0 时，在连接归还前的同一串行队列上补一次 SHOW WARNINGS 往返，
 *    明细进 SqlResult.warnings（计数由列表派生，与 JDBC 池 Blade D 腿对齐）；
 * ② warnings=0 的普通 DML 不得多发任何往返（主路径零扰动）。
 */
public class VertxConnectionWarningDetailTest {

    private static final int FIELD_TYPE_VARCHAR = 15;
    private static final int CHARSET_UTF8 = 33;

    private Vertx vertx;
    private NetClient client;
    private NetServer server;
    private int port;

    @BeforeEach
    void startClient() {
        vertx = Vertx.vertx();
        client = vertx.createNetClient();
    }

    @AfterEach
    void stopFakeBackend() throws Exception {
        if (server != null) {
            CountDownLatch closeLatch = new CountDownLatch(1);
            server.close(ar -> closeLatch.countDown());
            closeLatch.await(5, TimeUnit.SECONDS);
        }
        vertx.close();
    }

    // ------------------------------------------------------------------ tests

    /** G1：DML OK 携 warnings=2 → 同连接补 SHOW WARNINGS 往返，两条真明细进 SqlResult。 */
    @Test
    void dmlOkWithWarningsFetchesDetailsOnSameConnection() throws Exception {
        ScriptedBackend backend = new ScriptedBackend(2);
        listenWith(backend);

        SqlResult result = runUpdate("INSERT IGNORE INTO t VALUES(1)");

        Assertions.assertEquals(2, result.getWarningCount(), "明细到手后计数由列表派生");
        Assertions.assertEquals("Warning", result.getWarnings().get(0).getLevel());
        Assertions.assertEquals(1265, result.getWarnings().get(0).getCode());
        Assertions.assertEquals("Data truncated for column 'a' at row 1", result.getWarnings().get(0).getMessage());
        Assertions.assertEquals("Note", result.getWarnings().get(1).getLevel());
        Assertions.assertEquals(1003, result.getWarnings().get(1).getCode());
        Assertions.assertEquals(List.of("INSERT IGNORE INTO t VALUES(1)", "SHOW WARNINGS"), backend.sqls,
                "往返必须恰好补一条 SHOW WARNINGS");
    }

    /** G2：OK warnings=0 → 不得发起任何 SHOW WARNINGS 往返（主路径零扰动）。 */
    @Test
    void dmlWithoutWarningsSkipsRoundtrip() throws Exception {
        ScriptedBackend backend = new ScriptedBackend(0);
        listenWith(backend);

        SqlResult result = runUpdate("INSERT INTO t VALUES(1)");

        Assertions.assertEquals(0, result.getWarningCount());
        Assertions.assertTrue(result.getWarnings().isEmpty());
        Assertions.assertEquals(List.of("INSERT INTO t VALUES(1)"), backend.sqls, "零告警不得有多余往返");
    }

    /** G4：往返失败降级——SHOW WARNINGS 回 ERR 包时 update 仍成功完成，明细如实为空。 */
    @Test
    void failedRoundtripDegradesWithoutBlockingMainPath() throws Exception {
        ScriptedBackend backend = new ScriptedBackend(2);
        backend.failShowWarnings = true;
        listenWith(backend);

        SqlResult result = runUpdate("INSERT IGNORE INTO t VALUES(1)");

        Assertions.assertNotNull(result, "往返失败不得拖垮主路径");
        Assertions.assertTrue(result.getWarnings().isEmpty(), "取不到明细必须如实为空，不得合成");
        Assertions.assertEquals(1, result.getAffectRows());
    }

    // ------------------------------------------------------------------ helpers

    /** Vert.x 4.5.14：NetServer 的接入回调是 connectHandler（非 connectionHandler）。 */
    private void listenWith(ScriptedBackend backend) throws Exception {
        server = vertx.createNetServer(new NetServerOptions().setPort(0).setHost("127.0.0.1"));
        server.connectHandler(backend::handle);
        CountDownLatch listenLatch = new CountDownLatch(1);
        server.listen(ar -> listenLatch.countDown());
        Assertions.assertTrue(listenLatch.await(5, TimeUnit.SECONDS));
        port = server.actualPort();
    }

    /** 走被测链：VertxConnection.update(sql) 完成并返回 SqlResult。 */
    private SqlResult runUpdate(String sql) throws Exception {
        VertxConnection connection = connect();
        CountDownLatch latch = new CountDownLatch(1);
        SqlResult[] holder = new SqlResult[1];
        Throwable[] error = new Throwable[1];
        connection.update(sql).onComplete(ar -> {
            if (ar.succeeded()) {
                holder[0] = ar.result();
            } else {
                error[0] = ar.cause();
            }
            latch.countDown();
        });
        Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS), "update 未在超时内完成");
        Assertions.assertNull(error[0], "update 不得失败：" + error[0]);
        return holder[0];
    }

    private VertxConnection connect() throws Exception {
        NetSocket socket = await(client.connect(port, "127.0.0.1"), 5);
        VertxPoolConnectionImpl.Config config = new VertxPoolConnectionImpl.Config();
        // pool=null：异常回收回调不在本测试路径触发；命令腿行为与池无关。
        return new VertxConnection(socket, 1, config, null);
    }

    private static <T> T await(io.vertx.core.Future<T> future, int seconds) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Object[] box = new Object[2];
        future.onComplete(ar -> {
            if (ar.succeeded()) {
                box[0] = ar.result();
            } else {
                box[1] = ar.cause();
            }
            latch.countDown();
        });
        Assertions.assertTrue(latch.await(seconds, TimeUnit.SECONDS));
        Assertions.assertNull(box[1], "future 不得失败：" + box[1]);
        @SuppressWarnings("unchecked")
        T value = (T) box[0];
        return value;
    }

    // ------------------------------------------------------------------ fake backend

    /** 协议脚本假后端：握手→OK；DML→OK(warnings 可配)；SHOW WARNINGS→三列两行或 ERR。 */
    private static final class ScriptedBackend {
        final List<String> sqls = new CopyOnWriteArrayList<>();
        final AtomicInteger okWarnings;
        boolean failShowWarnings = false;

        ScriptedBackend(int okWarnings) {
            this.okWarnings = new AtomicInteger(okWarnings);
        }

        void handle(NetSocket socket) {
            // 本测试只验命令腿（update → OkCommand → 可选 SHOW WARNINGS），绕过 ConnectHandler 握手/认证：
            // 不写握手包，否则已缓冲的握手字节会被首个 OkCommand 当作 OK 响应误解析。
            final Buffer[] accumulator = {Buffer.buffer()};
            socket.handler(data -> {
                accumulator[0] = Buffer.buffer().appendBuffer(accumulator[0]).appendBuffer(data);
                while (accumulator[0].length() >= 4) {
                    int len = (accumulator[0].getUnsignedByte(0))
                            | (accumulator[0].getUnsignedByte(1) << 8)
                            | (accumulator[0].getUnsignedByte(2) << 16);
                    if (accumulator[0].length() < 4 + len) {
                        return;
                    }
                    byte[] payload = accumulator[0].getBytes(4, 4 + len);
                    // Buffer.slice 是不可写零容量视图，不得直接 appendBuffer——用拷贝重建可写累加器
                    accumulator[0] = Buffer.buffer(accumulator[0].getBytes(4 + len, accumulator[0].length()));
                    respond(payload, socket);
                }
            });
        }

        /**
         * 握手包字段序按 mysql 协议 Initial Handshake 4.1 形态；caps 只给
         * PROTOCOL_41|SECURE_CONNECTION|PLUGIN_AUTH（不给 DEPRECATE_EOF/SSL/COMPRESS，
         * 保证命令腿走经典 EOF 终结形态）。
         */
        private Buffer handshakePacket() {
            int caps = 0x00000200 | 0x00008000 | 0x00080000;
            try (MySQLPayloadWriter writer = new MySQLPayloadWriter(128)) {
                writer.write(10); // protocol version
                writer.writeNULString("8.0.46");
                writer.writeFixInt(4, 1); // connection id
                writer.writeBytes("12345678".getBytes()); // salt part 1
                writer.write(0); // filler
                writer.writeFixInt(2, caps & 0xFFFF);
                writer.write(33); // charset utf8
                writer.writeFixInt(2, 2); // status: autocommit
                writer.writeFixInt(2, (caps >>> 16) & 0xFFFF);
                writer.write(21); // len of combined auth-plugin data
                writer.writeBytes(new byte[10]); // reserved
                writer.writeBytes("123456789012".getBytes()); // salt part 2
                writer.write(0);
                writer.writeNULString("mysql_native_password");
                return bufferWithHeader(writer.toByteArray(), 0);
            }
        }

        private void respond(byte[] payload, NetSocket socket) {
            if (payload.length == 0) {
                return;
            }
            if (payload[0] == 0x03) {
                String sql = new String(payload, 1, payload.length - 1);
                sqls.add(sql);
                if (sql.equalsIgnoreCase("SHOW WARNINGS")) {
                    if (failShowWarnings) {
                        byte[] err = MySQLPacketUtil.generateError(1064, "boom", 0x00000200);
                        socket.write(bufferWithHeader(err, 1));
                    } else {
                        writeShowWarningsRows(socket);
                    }
                } else {
                    writeOk(socket, okWarnings.get());
                }
            } else {
                // auth packet → OK
                writeOk(socket, 0);
            }
        }

        private void writeOk(NetSocket socket, int warnings) {
            byte[] ok = MySQLPacketUtil.generateOk(0, warnings, 2, 1, 0, true, true, false, "");
            socket.write(bufferWithHeader(ok, 1));
        }

        private void writeShowWarningsRows(NetSocket socket) {
            writePayload(socket, lenencInt(3), 1);
            writePayload(socket, MySQLPacketUtil.generateColumnDefPayload("Level", FIELD_TYPE_VARCHAR, CHARSET_UTF8,
                    java.nio.charset.StandardCharsets.UTF_8), 2);
            writePayload(socket, MySQLPacketUtil.generateColumnDefPayload("Code", FIELD_TYPE_VARCHAR, CHARSET_UTF8,
                    java.nio.charset.StandardCharsets.UTF_8), 3);
            writePayload(socket, MySQLPacketUtil.generateColumnDefPayload("Message", FIELD_TYPE_VARCHAR, CHARSET_UTF8,
                    java.nio.charset.StandardCharsets.UTF_8), 4);
            writePayload(socket, row("Warning", "1265", "Data truncated for column 'a' at row 1"), 5);
            writePayload(socket, row("Note", "1003", "Query rewritten"), 6);
            writePayload(socket, eof(0, 2), 7);
        }

        private void writePayload(NetSocket socket, byte[] payload, int seq) {
            socket.write(bufferWithHeader(payload, seq));
        }
    }

    // ------------------------------------------------------------------ payload builders

    private static Buffer bufferWithHeader(byte[] payload, int seq) {
        Buffer head = Buffer.buffer();
        head.setByte(0, (byte) (payload.length & 0xFF));
        head.setByte(1, (byte) ((payload.length >>> 8) & 0xFF));
        head.setByte(2, (byte) ((payload.length >>> 16) & 0xFF));
        head.setByte(3, (byte) seq);
        return head.appendBytes(payload);
    }

    private static byte[] lenencInt(int value) {
        try (MySQLPayloadWriter writer = new MySQLPayloadWriter(9)) {
            writer.writeLenencInt(value);
            return writer.toByteArray();
        }
    }

    private static byte[] row(String... cells) {
        try (MySQLPayloadWriter writer = new MySQLPayloadWriter(64)) {
            for (String cell : cells) {
                writer.writeLenencString(cell);
            }
            return writer.toByteArray();
        }
    }

    private static byte[] eof(int warnings, int status) {
        try (MySQLPayloadWriter writer = new MySQLPayloadWriter(6)) {
            writer.write(0xFE);
            writer.writeFixInt(2, warnings);
            writer.writeFixInt(2, status);
            return writer.toByteArray();
        }
    }
}
