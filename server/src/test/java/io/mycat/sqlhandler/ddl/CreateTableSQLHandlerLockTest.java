package io.mycat.sqlhandler.ddl;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * I-PR-1 Task 6 Step 3 活体升级：CreateTableSQLHandler.createTable0 原为 synchronized，
 * 且 INSTANCE 是静态单例 ⇒ 全局监视器跨整个建表（含 ops.commit() 的物理建表阻塞 JDBC，
 * 活体实测 park 在 MySQL socket read）⇒ 第三轮 136 帧里占 4 帧。
 *
 * <p>覆盖口径如实说明：本类是**结构锁**。createTable0 的 body 会走 ops.commit() 写配置/建物理表，
 * 用 null 参数做「持锁必阻塞」行为探针会在 RED 阶段真跑 body、有污染配置的风险，故不采用。
 * 该站点钉住是否真消除，由活体 L1（-Djdk.tracePinnedThreads=full）决定性证明。
 */
public class CreateTableSQLHandlerLockTest {

    @Test
    public void noSynchronizedMethodRemains() {
        for (Method m : CreateTableSQLHandler.class.getDeclaredMethods()) {
            assertFalse(Modifier.isSynchronized(m.getModifiers()), "方法级 synchronized 未换锁: " + m);
        }
    }

    @Test
    public void lockIsSinglePerInstance() {
        assertEquals(1, reentrantLockFields(CreateTableSQLHandler.class).size(),
                "必须恰好一个 ReentrantLock 实例字段（单例下即全局串行，语义与原监视器一致）");
    }

    private static List<Field> reentrantLockFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && ReentrantLock.class.isAssignableFrom(f.getType())) {
                out.add(f);
            }
        }
        return out;
    }
}