package io.mycat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * I-PR-1 D3/D4：workerPool 旋钮必须名实相符。
 * Lombok @Builder 不带 @Builder.Default 时会忽略字段初始化器 ⇒ builder().build() 产出 0/null，
 * 与 new ThreadPoolExecutorConfig() 是两套互不一致的默认值。本类把「两套统一」钉成断言。
 */
public class ThreadPoolExecutorConfigDefaultsTest {

    @Test
    public void builderDefaultsMatchFieldInitializers() {
        ThreadPoolExecutorConfig c = ThreadPoolExecutorConfig.builder().build();
        assertEquals(0, c.getCorePoolSize());
        assertEquals(1024, c.getMaxPoolSize());
        assertEquals(60L, c.getKeepAliveTime());
        assertEquals(600L, c.getTaskTimeout());
        assertEquals("SECONDS", c.getTimeUnit());
        assertEquals(65535, c.getMaxPendingLimit());
    }

    @Test
    public void noArgsConstructorStillMatchesBuilder() {
        ThreadPoolExecutorConfig viaCtor = new ThreadPoolExecutorConfig();
        ThreadPoolExecutorConfig viaBuilder = ThreadPoolExecutorConfig.builder().build();
        assertEquals(viaCtor.getCorePoolSize(), viaBuilder.getCorePoolSize());
        assertEquals(viaCtor.getMaxPoolSize(), viaBuilder.getMaxPoolSize());
        assertEquals(viaCtor.getKeepAliveTime(), viaBuilder.getKeepAliveTime());
        assertEquals(viaCtor.getTaskTimeout(), viaBuilder.getTaskTimeout());
        assertEquals(viaCtor.getTimeUnit(), viaBuilder.getTimeUnit());
        assertEquals(viaCtor.getMaxPendingLimit(), viaBuilder.getMaxPendingLimit());
    }

    @Test
    public void admitTimeoutDefaultIs200() {
        assertEquals(200L, new ThreadPoolExecutorConfig().getAdmitTimeoutMillis());
        assertEquals(200L, ThreadPoolExecutorConfig.builder().build().getAdmitTimeoutMillis());
    }
}