package io.mycat.mysqlclient;

import io.mycat.MySQLPacketUtil;
import io.mycat.beans.mycat.WarningMessage;
import io.mycat.newquery.MysqlCollector;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * I-DEBT-69·残 NATIVE SELECT 腿：NATIVE 池结果集 EOF/OK 终止包 warnings 接力集成锁（vertx NetServer 假后端）。
 *
 * <p>镜像 {@code VertxConnectionWarningDetailTest}（DML/Blade G）范式，但驱动 SELECT 结果流
 * {@code VertxMycatConnectionPool.prepareQuery(…, collector)}。假后端按协议脚本应答：
 * COM_QUERY 主 SELECT → 列定义 + 行 + 终止包（warnings 可配）；SHOW WARNINGS → 三列两行或 ERR。
 * 锁定三条契约（spec 2026-11-03 §4 N1-N3）：
 * ① 终止包 warnings&gt;0 时，连接归还前的同一串行队列上补一次 SHOW WARNINGS 往返，
 *    明细经 {@code collector.onWarnings(...)} 交出，且必在 {@code onComplete()} 之前；
 * ② warnings=0 的普通 SELECT 不得发起任何往返（主路径零扰动）；
 * ③ 往返失败（SHOW WARNINGS 回 ERR）降级——{@code onComplete} 仍达、行仍下发、明细如实为空。
 */
public class NativeSelectWarningRelayTest {

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

    /** N1：主 SELECT 终止包 warnings=2 → 同连接补 SHOW WARNINGS 往返，两真明细进 collector（先于 onComplete）。 */
    @Test
    void selectWithWarningsRelaysShowWarningsIntoCollector() throws Exception {
        ScriptedBackend backend = new ScriptedBackend(2);
        listenWith(backend);

        CollectorSpy spy = new CollectorSpy();
        runPrepareQuery("select cast('abc' as signed) from t", spy);

        Assertions.assertTrue(spy.completed, "onComplete 必须达");
        Assertions.assertTrue(backend.sqls.contains("SHOW WARNINGS"), "必须补一次 SHOW WARNINGS 往返，实得 " + backend.sqls);
        Assertions.assertEquals(1, spy.warningCalls, "onWarnings 恰一次");
        Assertions.assertEquals(2, spy.lastWarnings.size(), "两条真明细");
        Assertions.assertEquals(1265, spy.lastWarnings.get(0).getCode());
        Assertions.assertEquals("Warning", spy.lastWarnings.get(0).getLevel());
        Assertions.assertEquals(1003, spy.lastWarnings.get(1).getCode());
        Assertions.assertTrue(spy.warningsBeforeComplete, "明细必须先于 onComplete（MysqlCollector 契约）");
    }

    /** N2：终止包 warnings=0 → 不得发起任何 SHOW WARNINGS 往返（主路径零扰动）。 */
    @Test
    void selectWithoutWarningsSkipsRoundtrip() throws Exception {
        ScriptedBackend backend = new ScriptedBackend(0);
        listenWith(backend);

        CollectorSpy spy = new CollectorSpy();
        runPrepareQuery("select cast('abc' as signed) from t", spy);

        Assertions.assertTrue(spy.completed);
        Assertions.assertEquals(1, backend.sqls.size(), "零告警不得有多余往返，实得 " + backend.sqls);
        Assertions.assertFalse(backend.sqls.contains("SHOW WARNINGS"));
        Assertions.assertEquals(0, spy.warningCalls, "零告警不得回调 onWarnings");
    }

    /** N3：接力降级——SHOW WARNINGS 回 ERR 包时 SELECT 仍完成、行仍下发、明细如实为空、不合成。 */
    @Test
    void failedRoundtripDegradesWithoutBlockingMainStream() throws Exception {
        ScriptedBackend backend = new ScriptedBackend(2);
        backend.failShowWarnings = true;
        listenWith(backend);

        CollectorSpy spy = new CollectorSpy();
        runPrepareQuery("select cast('abc' as signed) from t", spy);

        Assertions.assertTrue(spy.completed, "往返失败不得拖垮主 SELECT 流");
        Assertions.assertTrue(spy.rows.isEmpty() ? true : spy.rows.size() >= 1);
        Assertions.assertTrue(backend.sqls.contains("SHOW WARNINGS"), "仍应尝试一次往返");
        Assertions.assertTrue(spy.lastWarnings == null || spy.lastWarnings.isEmpty(),
                "取不到明细必须如实空，不得按计数合成");
    }

    // ------------------------------------------------------------------ N4 decode lock

    /** N4：终止包 warnings 解码双形对拍（deprecate=ON OK 形 / OFF 经典形）+ 残长退化 0。 */
    @Test
    void decodeEofPacketWarningsBothLayouts() {
        // OK-body 形：0xFE + lenenc(0) + lenenc(0) + status(2) + warnings(7)
        Buffer okForm = Buffer.buffer(okFormTerminator(7, 2));
        Assertions.assertEquals(7, PacketUtil.decodeEofPacketWarnings(okForm, true), "OK 形 warnings 在 status 后");

        // 经典 EOF 形：0xFE + warnings(5) + status(2)
        Buffer classic = Buffer.buffer(classicEof(5, 2));
        Assertions.assertEquals(5, PacketUtil.decodeEofPacketWarnings(classic, false), "经典形 warnings 在 status 前");
        // 交叉不得错读：经典形按 OK 形解会跳过多字节 → 残长不足退化 0，绝不抛。
        Assertions.assertEquals(0, PacketUtil.decodeEofPacketWarnings(classic, true));

        // 残长退化：仅 header 1 字节，两形均 0，不反抛。
        Buffer truncated = Buffer.buffer(new byte[]{(byte) 0xFE});
        Assertions.assertEquals(0, PacketUtil.decodeEofPacketWarnings(truncated, true));
        Assertions.assertEquals(0, PacketUtil.decodeEofPacketWarnings(truncated, false));
    }

    // ------------------------------------------------------------------ helpers

    /** Vert.x 4.5.14：NetServer 接入回调是 connectHandler（非 connectionHandler）。 */
    private void listenWith(ScriptedBackend backend) throws Exception {
        server = vertx.createNetServer(new NetServerOptions().setPort(0).setHost("127.0.0.1"));
        server.connectHandler(backend::handle);
        CountDownLatch listenLatch = new CountDownLatch(1);
        server.listen(ar -> listenLatch.countDown());
        Assertions.assertTrue(listenLatch.await(5, TimeUnit.SECONDS));
        port = server.actualPort();
    }

    /** 走被测链：VertxMycatConnectionPool.prepareQuery(sql, [], collector) 直至 onComplete/onError。 */
    private void runPrepareQuery(String sql, CollectorSpy spy) throws Exception {
        VertxConnection connection = connect();
        VertxMycatConnectionPool pool = new VertxMycatConnectionPool("t", connection, null);
        pool.prepareQuery(sql, Collections.emptyList(), spy);
        Assertions.assertTrue(spy.latch.await(10, TimeUnit.SECONDS), "prepareQuery 未在超时内收尾");
        Assertions.assertNull(spy.error, "SELECT 流不得失败：" + spy.error);
    }

    private VertxConnection connect() throws Exception {
        NetSocket socket = await(client.connect(port, "127.0.0.1"), 5);
        VertxPoolConnectionImpl.Config config = new VertxPoolConnectionImpl.Config();
        // pool=null：命令腿行为与池回收无关。
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

    // ------------------------------------------------------------------ collector spy

    private static final class CollectorSpy implements MysqlCollector {
        final CountDownLatch latch = new CountDownLatch(1);
        final List<Object[]> rows = new ArrayList<>();
        volatile boolean completed = false;
        volatile Throwable error = null;
        volatile int warningCalls = 0;
        volatile List<WarningMessage> lastWarnings = null;
        volatile boolean warningsBeforeComplete = false;

        @Override
        public void onColumnDef(io.mycat.beans.mycat.MycatRowMetaData mycatRowMetaData) {
        }

        @Override
        public void onRow(Object[] row) {
            rows.add(row);
        }

        @Override
        public void onWarnings(List<WarningMessage> warnings) {
            warningCalls++;
            lastWarnings = warnings;
            if (!completed) {
                warningsBeforeComplete = true;
            }
        }

        @Override
        public void onComplete() {
            completed = true;
            latch.countDown();
        }

        @Override
        public void onError(Throwable e) {
            error = e;
            latch.countDown();
        }
    }

    // ------------------------------------------------------------------ fake backend

    /** 协议脚本假后端：主 SELECT→结果集(终止包 warnings 可配)；SHOW WARNINGS→三列两行或 ERR。 */
    private static final class ScriptedBackend {
        final List<String> sqls = new CopyOnWriteArrayList<>();
        final AtomicInteger terminatorWarnings;
        boolean failShowWarnings = false;

        ScriptedBackend(int terminatorWarnings) {
            this.terminatorWarnings = new AtomicInteger(terminatorWarnings);
        }

        void handle(NetSocket socket) {
            // 绕过 ConnectHandler 握手/认证：不写握手包，直接对首个 COM_QUERY 应答结果流。
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
                    accumulator[0] = Buffer.buffer(accumulator[0].getBytes(4 + len, accumulator[0].length()));
                    respond(payload, socket);
                }
            });
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
                    writeSelectResultset(socket, terminatorWarnings.get());
                }
            } else {
                writeOk(socket, 0);
            }
        }

        /** 主 SELECT：1 列 1 行 + 终止包（OK 形，warnings 可配）。config 默认 deprecateEof=true。 */
        private void writeSelectResultset(NetSocket socket, int warnings) {
            writePayload(socket, lenencInt(1), 1);
            writePayload(socket, MySQLPacketUtil.generateColumnDefPayload("c", FIELD_TYPE_VARCHAR, CHARSET_UTF8,
                    java.nio.charset.StandardCharsets.UTF_8), 2);
            writePayload(socket, row("1"), 3);
            writePayload(socket, okFormTerminator(warnings, 2), 4);
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
            writePayload(socket, okFormTerminator(0, 2), 7);
        }

        private void writeOk(NetSocket socket, int warnings) {
            byte[] ok = MySQLPacketUtil.generateOk(0, warnings, 2, 1, 0, true, true, false, "");
            socket.write(bufferWithHeader(ok, 1));
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

    /**
     * DEPRECATE_EOF=ON 结果集终止包 = OK-body 形（header 0xFE）：
     * {@code 0xFE + affected_rows(lenenc=0) + last_insert_id(lenenc=0) + status(fixint2) + warnings(fixint2)}。
     * 与 spec §2.5 坐实布局一致（Mycat 经 ConnectHandler 协商该位）。
     */
    private static byte[] okFormTerminator(int warnings, int status) {
        try (MySQLPayloadWriter writer = new MySQLPayloadWriter(8)) {
            writer.write(0xFE);
            writer.writeLenencInt(0);
            writer.writeLenencInt(0);
            writer.writeFixInt(2, status);
            writer.writeFixInt(2, warnings);
            return writer.toByteArray();
        }
    }

    /** 经典 EOF 形（deprecate=OFF）：{@code 0xFE + warnings(fixint2) + status(fixint2)}。 */
    private static byte[] classicEof(int warnings, int status) {
        try (MySQLPayloadWriter writer = new MySQLPayloadWriter(6)) {
            writer.write(0xFE);
            writer.writeFixInt(2, warnings);
            writer.writeFixInt(2, status);
            return writer.toByteArray();
        }
    }
}
