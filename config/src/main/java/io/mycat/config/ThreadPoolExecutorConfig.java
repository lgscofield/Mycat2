package io.mycat.config;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.util.concurrent.TimeUnit;

/**
 * int corePoolSize,
 * int maximumPoolSize,
 * long keepAliveTime,
 * TimeUnit unit
 */
@Data
@ToString
@Builder
public class ThreadPoolExecutorConfig {
    @Builder.Default
    private int corePoolSize = 0;
    @Builder.Default
    private int maxPoolSize = 1024;
    @Builder.Default
    private long keepAliveTime = 60;
    @Builder.Default
    private long taskTimeout = 600;
    @Builder.Default
    private String timeUnit = TimeUnit.SECONDS.toString();
    @Builder.Default
    private int maxPendingLimit = 65535;
    // I-PR-1 D3：VT 准入等待上限。VT 无池无队列，故 core/keepAlive/maxPending 已无物理意义，
    // 真正界住并发的是 maxPoolSize（许可数）与本字段（等待多久后拒绝）。
    @Builder.Default
    private long admitTimeoutMillis = 200;

    public ThreadPoolExecutorConfig() {
        // @Builder.Default 会把字段初始化器搬进 builder 的 $default$ 方法并从字段上剥离，
        // 手写无参构造器因此拿不到默认值（Jackson 反序列化 server.json 走的正是这条路径）。
        // 从 builder 取默认值，避免默认值字面量出现第二份副本而漂移。
        ThreadPoolExecutorConfig d = ThreadPoolExecutorConfig.builder().build();
        this.corePoolSize = d.corePoolSize;
        this.maxPoolSize = d.maxPoolSize;
        this.keepAliveTime = d.keepAliveTime;
        this.taskTimeout = d.taskTimeout;
        this.timeUnit = d.timeUnit;
        this.maxPendingLimit = d.maxPendingLimit;
        this.admitTimeoutMillis = d.admitTimeoutMillis;
    }

    public ThreadPoolExecutorConfig(int corePoolSize, int maxPoolSize, long keepAliveTime, long taskTimeout, String timeUnit, int maxPendingLimit, long admitTimeoutMillis) {
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.taskTimeout = taskTimeout;
        this.timeUnit = timeUnit;
        this.maxPendingLimit = maxPendingLimit;
        this.admitTimeoutMillis = admitTimeoutMillis;
    }
}