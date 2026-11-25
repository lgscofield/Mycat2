package io.mycat.router.custom;

import io.mycat.Partition;
import io.mycat.RangeVariable;
import io.mycat.router.ShardingTableHandler;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpCustomRuleFunctionLockTest {

    @Test
    public void blockingFetchDoesNotHoldInstanceMonitor() throws Exception {
        HttpCustomRuleFunction function = new HttpCustomRuleFunction();
        AtomicBoolean monitorHeld = new AtomicBoolean(true);
        AtomicBoolean onVirtualThread = new AtomicBoolean();
        AtomicBoolean parked = new AtomicBoolean();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Map<String, Object> properties = new HashMap<String, Object>() {
            @Override
            public Object getOrDefault(Object key, Object defaultValue) {
                monitorHeld.set(Thread.holdsLock(function));
                onVirtualThread.set(Thread.currentThread().isVirtual());
                inside.countDown();
                try {
                    parked.set(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new ProbeStop();
            }
        };
        function.properties = properties;
        Map<String, RangeVariable> values = new HashMap<>();
        values.put("id", null);
        ExecutorService vt = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        Future<?> task = vt.submit(() -> {
            try {
                function.calculate(values);
            } catch (ProbeStop expected) {
                return;
            }
            throw new AssertionError("探针未在 properties 读取处停下");
        });
        try {
            assertTrue(inside.await(5, TimeUnit.SECONDS), "fetchPartitions 未进入探针");
            assertTrue(onVirtualThread.get(), "探针必须运行在虚拟线程上");
            assertFalse(monitorHeld.get(), "阻塞 HTTP 取分区时仍持有实例监视器 ⇒ VT 钉住载体线程");
        } finally {
            release.countDown();
            vt.shutdown();
        }
        task.get(5, TimeUnit.SECONDS);
        assertTrue(parked.get(), "探针未真的 park 到放闸");
    }

    @Test
    public void blockingHttpDoesNotRunRealCallExecuteOnVirtualThread() throws Exception {
        ShardingTableHandler tableHandlerStub = (ShardingTableHandler) Proxy.newProxyInstance(
                ShardingTableHandler.class.getClassLoader(), new Class<?>[]{ShardingTableHandler.class},
                (proxy, method, args) -> {
                    if ("getSchemaName".equals(method.getName())) return "db";
                    if ("getTableName".equals(method.getName())) return "t";
                    throw new UnsupportedOperationException(method.getName());
                });
        HttpCustomRuleFunction function = new HttpCustomRuleFunction() {
            @Override
            public ShardingTableHandler getTable() {
                return tableHandlerStub;
            }
        };

        CountDownLatch requestAccepted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        AtomicReference<String> stubError = new AtomicReference<>("");
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread stub = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                         OutputStream out = socket.getOutputStream()) {
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            // 只消费请求头；响应即可放行，探针只验证路由腿确实到达 HTTP。
                        }
                        requestAccepted.countDown();
                        assertTrue(releaseResponse.await(5, TimeUnit.SECONDS), "HTTP 探针未真的等待放闸");
                        String json = "[{\"targetName\":\"c0\",\"schema\":\"db\",\"table\":\"t\","
                                + "\"dbIndex\":0,\"tableIndex\":0,\"index\":0}]";
                        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
                        String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                + payload.length + "\r\nConnection: close\r\n\r\n";
                        out.write(headers.getBytes(StandardCharsets.US_ASCII));
                        out.write(payload);
                        out.flush();
                    } catch (Exception e) {
                        if (!server.isClosed()) stubError.compareAndSet("", e.getClass().getSimpleName() + ":" + e.getMessage());
                    }
                }
            }, "http-rule-pin-stub");
            stub.setDaemon(true);
            stub.start();

            Map<String, Object> properties = new HashMap<>();
            properties.put("routerServiceAddress", "http://127.0.0.1:" + server.getLocalPort() + "/routerserivce");
            properties.put("fetchTimeout", 5000L);
            function.properties = properties;
            Map<String, RangeVariable> values = new HashMap<>();
            values.put("id", null);
            AtomicReference<Thread> virtualThread = new AtomicReference<>();
            ExecutorService vt = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
            try {
                Future<List<Partition>> result = vt.submit(() -> {
                    virtualThread.set(Thread.currentThread());
                    return function.calculate(values);
                });
                if (!requestAccepted.await(5, TimeUnit.SECONDS)) {
                    try {
                        result.get(1, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.ExecutionException e) {
                        throw new AssertionError("HTTP 路由在请求到达前失败", e);
                    }
                    throw new AssertionError("HTTP 路由请求未到达探针");
                }
                assertTrue(virtualThread.get().isVirtual(), "HTTP 探针必须运行在虚拟线程上");
                assertFalse(hasFrame(virtualThread.get(), "okhttp3.internal.connection.RealCall", "execute"),
                        "虚拟线程仍停在 OkHttp 同步 execute ⇒ RealCall 内部 monitor 跨 socket read ⇒ VT 钉住载体");
                releaseResponse.countDown();
                List<Partition> partitions = result.get(5, TimeUnit.SECONDS);
                assertEquals(1, partitions.size());
                assertEquals("c0", partitions.get(0).getTargetName());
                assertEquals("", stubError.get());
            } finally {
                releaseResponse.countDown();
                vt.shutdownNow();
            }
        }
    }

    private static boolean hasFrame(Thread thread, String className, String methodName) {
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (className.equals(frame.getClassName()) && methodName.equals(frame.getMethodName())) return true;
        }
        return false;
    }

    @Test
    public void noSynchronizedMethodRemains() {
        for (Method method : HttpCustomRuleFunction.class.getDeclaredMethods()) {
            assertFalse(Modifier.isSynchronized(method.getModifiers()), "方法级 synchronized 未换锁: " + method);
        }
    }

    @Test
    public void lockIsSinglePerInstance() {
        assertEquals(1, reentrantLockFields(HttpCustomRuleFunction.class).size(),
                "必须恰好一个 ReentrantLock 实例字段，保持 fetchPartitions 的互斥语义");
    }

    private static List<Field> reentrantLockFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && ReentrantLock.class.isAssignableFrom(field.getType())) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static final class ProbeStop extends RuntimeException {
    }
}
