package io.mycat;

import io.mycat.config.ThreadPoolExecutorConfig;

/**
 * I-PR-1：IO 阻塞车道的准入参数，不可变。
 * VT 无池无队列，core/keepAlive/maxPending 已无物理意义；真正界住并发的只有许可数与准入等待上限。
 */
public final class IOExecutorConfig {

    private final long permits;
    private final long admitTimeoutMillis;

    public IOExecutorConfig(long permits, long admitTimeoutMillis) {
        if (permits < 1 || permits > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("IO 许可数（workerPool.maxPoolSize）必须在 1.." + Integer.MAX_VALUE
                    + " 之间，实得 " + permits);
        }
        if (admitTimeoutMillis < 0) {
            throw new IllegalArgumentException("workerPool.admitTimeoutMillis 必须 >= 0，实得 " + admitTimeoutMillis);
        }
        this.permits = permits;
        this.admitTimeoutMillis = admitTimeoutMillis;
    }

    public static IOExecutorConfig from(ThreadPoolExecutorConfig config) {
        return new IOExecutorConfig(config.getMaxPoolSize(), config.getAdmitTimeoutMillis());
    }

    public static IOExecutorConfig defaults() {
        return from(new ThreadPoolExecutorConfig());
    }

    public long getPermits() {
        return permits;
    }

    public long getAdmitTimeoutMillis() {
        return admitTimeoutMillis;
    }
}