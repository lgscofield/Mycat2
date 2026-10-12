package io.mycat.vertx.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3-T6：caching_sha2 warmed 标记缓存。锁冷→热→FLUSH 生命周期，并验证有界不无界增长
 * （满容量后驱逐最旧，Review Focus：防内存泄漏）。不存口令/scramble，只存"该用户已 warmed"事实。
 */
public class CachingSha2AuthCacheTest {

    @Test
    public void coldThenWarmThenFlush() {
        CachingSha2AuthCache c = CachingSha2AuthCache.getInstance();
        c.invalidateAll();
        assertFalse(c.isWarmed("u_fe"));
        c.markWarmed("u_fe");
        assertTrue(c.isWarmed("u_fe"));
        c.invalidateAll();
        assertFalse(c.isWarmed("u_fe"));
    }

    @Test
    public void boundedEvictsOldest() {
        CachingSha2AuthCache c = CachingSha2AuthCache.getInstance();
        c.invalidateAll();
        int cap = c.capacity();
        for (int i = 0; i < cap + 1; i++) {
            c.markWarmed("user_" + i);
        }
        // 超容量后最旧被驱逐，最新的仍在，尺寸不超过容量
        assertFalse(c.isWarmed("user_0"));
        assertTrue(c.isWarmed("user_" + cap));
        assertTrue(c.size() <= cap);
    }
}
