package io.mycat.vertx;

import cn.mycat.vertx.xa.XaSqlConnection;
import io.mycat.MycatDataContext;
import io.mycat.TransactionSession;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * I-PR-1 Task 6 Step 3 活体升级：第三轮 136 帧钉住里 **105 帧**出自
 * {@code VertxExecuter:137 map.computeIfAbsent(target, s -> transactionSession.getConnection(target))}。
 * CHM 在 mapping function 执行期间持 bin 监视器，而该 function 里做阻塞 JDBC
 * （活体 park 在 logback appender 的 ReentrantLock）⇒ VT 钉住载体线程。
 *
 * <p>探针原理：取连接的 stub 里读当前栈，若栈上出现 {@code ConcurrentHashMap.computeIfAbsent}，
 * 就说明阻塞取连接正发生在 mapping function 内。这是钉住条件的直接可测代理，不依赖反射猜字段。
 *
 * <p>map 是 apply() 内的局部 CHM、整个 for 循环单线程，故 get-then-put 与 computeIfAbsent 语义等价。
 */
public class VertxExecuterComputeIfAbsentPinTest {

    /** 行为探针：分片取连接不得发生在 CHM 的 mapping function 内。 */
    @Test
    public void shardConnectionIsNotFetchedInsideComputeIfAbsent() {
        AtomicBoolean insideComputeIfAbsent = new AtomicBoolean(false);
        AtomicInteger calls = new AtomicInteger();

        // XaSqlConnection 与 TransactionSession 无继承关系，生产码是跨接口强转，故 mock 须同时具备两者。
        TransactionSession session = Mockito.mock(TransactionSession.class,
                Mockito.withSettings().extraInterfaces(XaSqlConnection.class));
        XaSqlConnection xa = (XaSqlConnection) session;
        Mockito.when(xa.getConnection(Mockito.anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                if ("java.util.concurrent.ConcurrentHashMap".equals(e.getClassName())
                        && "computeIfAbsent".equals(e.getMethodName())) {
                    insideComputeIfAbsent.set(true);
                }
            }
            // 失败 future 让后续 flatMap 短路：探针只关心取连接发生在哪，不关心结果。
            return Future.failedFuture(new RuntimeException("probe-stop"));
        });

        MycatDataContext context = Mockito.mock(MycatDataContext.class);
        Mockito.when(context.getTransactionSession()).thenReturn(session);
        Mockito.when(context.resolveDatasourceTargetName(Mockito.anyString(), Mockito.anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));

        List<VertxExecuter.EachSQL> sqls = Arrays.asList(
                new VertxExecuter.EachSQL("c0", "update t set a=1", Collections.emptyList()),
                new VertxExecuter.EachSQL("c1", "update t set a=1", Collections.emptyList()));

        // xa=false ⇒ 走 Future.succeededFuture().flatMap(...)，在当前线程内联执行，无需 Vert.x 上下文。
        VertxExecuter.simpleUpdate(context, false, false, true, sqls);

        assertEquals(2, calls.get(), "两个不同 target 应各取一次连接，否则探针前提不成立");
        assertFalse(insideComputeIfAbsent.get(),
                "阻塞取连接发生在 ConcurrentHashMap.computeIfAbsent 的 mapping function 内 ⇒ "
                        + "CHM 持 bin 监视器跨阻塞 IO ⇒ VT 钉住载体（活体实测 105 帧）");
    }

    /**
     * 结构锁：覆盖离线不可达的事务路径孪生缺陷 {@code AsyncMycatDataContextImpl:212}
     * （本轮未命中只因负载没开事务，机理与 :137 相同）。
     * 按括号配平取出每个 computeIfAbsent 调用整体，其中不得出现 getConnection(。
     */
    @Test
    public void noBlockingGetConnectionInsideAnyComputeIfAbsent() throws Exception {
        assertNoBlockingComputeIfAbsent(Paths.get("src/main/java/io/mycat/vertx/VertxExecuter.java"));
        assertNoBlockingComputeIfAbsent(Paths.get("src/main/java/io/mycat/AsyncMycatDataContextImpl.java"));
    }

    private static void assertNoBlockingComputeIfAbsent(Path file) throws Exception {
        assertTrue(Files.exists(file), "找不到生产源文件，结构锁失效: " + file.toAbsolutePath());
        String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String needle = "computeIfAbsent(";
        int i = src.indexOf(needle);
        int checked = 0;
        while (i >= 0) {
            int open = i + needle.length() - 1;
            int depth = 0;
            int j = open;
            for (; j < src.length(); j++) {
                char c = src.charAt(j);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            if (depth != 0) {
                fail("computeIfAbsent 括号未配平，结构锁无法判定: " + file + " @offset " + i);
            }
            String call = src.substring(open, j + 1);
            assertFalse(call.contains("getConnection("),
                    "computeIfAbsent 的 mapping function 内做阻塞取连接 ⇒ CHM 持 bin 监视器跨阻塞 IO ⇒ VT 钉住载体: "
                            + file + " @offset " + i + " -> " + call);
            checked++;
            i = src.indexOf(needle, j);
        }
        assertTrue(checked > 0, "一个 computeIfAbsent 都没扫到，结构锁是空断言: " + file);
    }
}