package io.mycat.calcite.sqlfunction.infofunction;

import io.mycat.MetaClusterCurrent;
import io.mycat.config.ServerPersona;
import io.mycat.config.VersionProfile;
import io.mycat.config.VersionProfileFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * I-VE-1 SELECT VERSION() 改读 VersionProfile：DI 命中取画像串，未注册回落 MYSQL_5_7 默认（不 NPE）。
 */
public class MycatVersionFunctionTest {

    private Map<Class, Object> previousContext;

    @BeforeEach
    public void snapshot() {
        previousContext = MetaClusterCurrent.copyContext();
    }

    @AfterEach
    public void restore() {
        MetaClusterCurrent.register(previousContext);
    }

    @Test
    public void versionReturnsProfileStringWhenRegistered() {
        IdentityHashMap<Class, Object> ctx = new IdentityHashMap<>(previousContext);
        ctx.put(VersionProfile.class, VersionProfileFactory.of(ServerPersona.MYSQL_5_7, "8.8.8-probe"));
        MetaClusterCurrent.register(ctx);
        assertEquals("8.8.8-probe", MycatVersionFunction.version());
    }

    @Test
    public void versionFallsBackToDefaultWhenDiEmpty() {
        // 用不含画像的空上下文模拟引导早期/单测环境，须回落默认而非抛 NPE
        MetaClusterCurrent.register(new IdentityHashMap<>());
        assertEquals("5.7.33-mycat-2.0", MycatVersionFunction.version());
    }
}
