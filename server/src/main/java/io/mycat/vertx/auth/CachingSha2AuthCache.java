package io.mycat.vertx.auth;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R3-T6：caching_sha2_password 服务端 "warmed" 标记缓存（进程级单例）。
 *
 * <p>MySQL caching_sha2 语义：用户首次经 full-auth 成功认证后，其口令哈希被 server 侧缓存，
 * 后续连接可走 fast-auth。Mycat 前端持明文口令、每连接以当连 seed 实算 scramble 比对，
 * 无需缓存口令/scramble 本身——此处只记录"该用户已完成过一次 full-auth、可走 fast-auth"的事实标记。
 *
 * <p>有界 LRU（容量上限 {@value #CAPACITY}，超限驱逐最久未用），防无界增长。
 * {@link #invalidateAll()} 对应 FLUSH PRIVILEGES 语义：清空全部 warmed 标记，令所有用户回到冷态。
 * 线程安全：全部方法同步。
 */
public final class CachingSha2AuthCache {

    private static final int CAPACITY = 1024;

    private static final CachingSha2AuthCache INSTANCE = new CachingSha2AuthCache();

    private final LinkedHashMap<String, Boolean> warmed =
            new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > CAPACITY;
                }
            };

    private CachingSha2AuthCache() {
    }

    public static CachingSha2AuthCache getInstance() {
        return INSTANCE;
    }

    /** 该用户是否已 warmed（可对其走 fast-auth）。 */
    public synchronized boolean isWarmed(String user) {
        return user != null && warmed.containsKey(user);
    }

    /** 标记该用户已 warmed（full-auth 成功后调用）。 */
    public synchronized void markWarmed(String user) {
        if (user != null) {
            warmed.put(user, Boolean.TRUE);
        }
    }

    /** 清空全部标记（FLUSH PRIVILEGES 语义）。 */
    public synchronized void invalidateAll() {
        warmed.clear();
    }

    /** 当前标记数（内省/测试用）。 */
    public synchronized int size() {
        return warmed.size();
    }

    /** 容量上限（内省/测试用）。 */
    public int capacity() {
        return CAPACITY;
    }
}
