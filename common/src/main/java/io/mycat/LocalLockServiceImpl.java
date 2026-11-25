package io.mycat;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.shareddata.Lock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

public class LocalLockServiceImpl implements LockService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalLockServiceImpl.class);


    // I-PR-1：原 synchronized 单例方法持实例监视器跨整个 supplier，supplier 内阻塞 JDBC
    // （活体实测 park 在 NativeProtocol.readMessage）会钉住 VT 载体线程。换每实例单把 ReentrantLock。
    // 保持**全局串行**语义：不改成 per-name 锁，那会放大 DDL 并发，属行为变更、超出本票。
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public Future<Void> lock(String name, Supplier<Future<Void>> runnable) {
        lock.lock();
        try {
            LOGGER.info("lock_service_worker is running with '" + name + "'");
            try {
                return runnable.get();
            } catch (Throwable throwable) {
                return Future.failedFuture(throwable);
            }
        } finally {
            lock.unlock();
        }
    }

}
