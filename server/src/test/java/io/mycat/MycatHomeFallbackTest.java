package io.mycat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-56 回归锁：config home 的出生规则。
 *
 * <p>
 * 显式 -DMYCAT_HOME 直通（发行版 startup 脚本与 e2e 依赖此语义）；未显式设置时
 * fallback 必须落到隔离子目录 .mycat-home，使运行态回写（建库/建集群 hint、
 * resetConfig 等生成的 {clusters,datasources,users,schemas}/*.json 与 state.json）
 * 不污染启动目录本身——此前 fallback 停在 jar/classes 所在目录，直启场景把
 * 运行态 JSON 写进工作树，git 状态被盖章与重序列化刷屏。
 */
public class MycatHomeFallbackTest {

    @Test
    public void explicitPropertyIsUsedVerbatim() throws URISyntaxException {
        String saved = System.getProperty("MYCAT_HOME");
        try {
            System.setProperty("MYCAT_HOME", "explicit/home/should/passthrough");
            assertEquals("explicit/home/should/passthrough", MycatCore.findMycatHome());
        } finally {
            restore(saved);
        }
    }

    @Test
    public void fallbackUsesIsolatedSubdirectory() throws URISyntaxException {
        String saved = System.getProperty("MYCAT_HOME");
        try {
            System.clearProperty("MYCAT_HOME");
            String home = MycatCore.findMycatHome();
            assertTrue(home.replace('\\', '/').endsWith(".mycat-home"),
                    "fallback home 应落在隔离子目录 .mycat-home，实际=" + home);
        } finally {
            restore(saved);
        }
    }

    private void restore(String saved) {
        if (saved == null) {
            System.clearProperty("MYCAT_HOME");
        } else {
            System.setProperty("MYCAT_HOME", saved);
        }
    }
}
