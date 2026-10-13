package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * R4/Task4 · 前端 TLS 轴兼容矩阵格（on/off × persona）——招牌 M2「TLS on/off」判据的 harness 侧建格。
 *
 * <p>口径以主 spec §3/§7 + MySQL/MariaDB 官网为准（落实「拿不准查官网」）。加密态判据用
 * <b>Connector/J {@code sslMode} 的官方强制语义</b>：{@code sslMode=REQUIRED} 等价 CLI {@code --ssl-mode=REQUIRED}
 * ——服务端未广告 CLIENT_SSL 时 Connector/J <b>直接失败、不回退明文</b>（MySQL Connector/J 官方 "Using TLS/SSL Connections"）。
 * 因此「REQUIRED 连接成功」本身即客户端可观测的权威加密证据，无需读服务端内部状态。
 *
 * <p><b>为何不用 {@code SHOW STATUS LIKE 'Ssl_cipher'}</b>：Mycat 是代理，其 SHOW STATUS 语义可能透传<b>后端</b>连接
 * 的 Ssl_cipher，而非「客户端↔Mycat」前端 TLS 态——据此判定会给假绿/假红，故弃用（详见 `2026-10-13-tls-compat-matrix.md` §2）。
 *
 * <p><b>激活条件（当前 NO_DATA，绝不假绿）</b>：需 {@code run-matrix} 起 Mycat 且服务端 {@code tlsMode!=DISABLED}，
 * 并置系统属性 {@code -Dmycat.tls.expected=true}（runbook 提供）。三层诚实门控：
 * <ol>
 *   <li>无 8066 基础设施 → 明文探测连接被拒 → {@link Assumptions#abort} 记 <b>NO_DATA</b>（须响，不得静默当绿）。</li>
 *   <li>{@code mycat.tls.expected} 未置 true（Mycat 以 tlsMode=DISABLED 起）→ TLS-on 格 {@code assumeTrue} 记 <b>N/A</b>（合法，非回归）。</li>
 *   <li>{@code mycat.tls.expected=true} → 跑真断言：REQUIRED 连上（证升级+加密）且 select 1 往返；此时若连不上=真回归 FAIL。</li>
 * </ol>
 * 传输层 upgradeToSsl 本身已由 server 模块单测 {@code FrontendStartTlsUpgradeTest} + 真 Connector/J 离线探针坐实
 * （cipher=TLS_AES_256_GCM_SHA384）；本格补「经完整 Mycat + 真实后端、认证+往返在 TLS 通道下不破」的 e2e 证据。
 *
 * <p>persona 维度由 {@code -Dmycat.persona} 驱动 run-matrix 起对应画像后端；{@code sslMode} 轴此处固定 REQUIRED/DISABLED 两态。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TlsAxisMatrixTest implements MycatTest {

    /** TLS 强制（REQUIRED 不回退明文；连上=已加密，官方语义）。verifyServerCertificate=false 容自签/测试证书。 */
    private static final String TLS_ON_URL = "jdbc:mysql://127.0.0.1:8066/mysql"
            + "?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true"
            + "&connectTimeout=3000&socketTimeout=10000&sslMode=REQUIRED&verifyServerCertificate=false";
    /** 明文（对应 CLI --ssl-mode=DISABLED）：既作明文格断言，也作基础设施探测（无 TLS 依赖，最稳）。 */
    private static final String TLS_OFF_URL = "jdbc:mysql://127.0.0.1:8066/mysql"
            + "?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true"
            + "&connectTimeout=3000&socketTimeout=10000&sslMode=DISABLED";

    private static final String USER = "root";
    private static final String PW = "123456";

    /** 明文探测 8066 是否在：连接被拒/超时 → abort 记 NO_DATA（不假绿、不产噪音红）。 */
    private void assumeInfraUp() {
        try (Connection c = DriverManager.getConnection(TLS_OFF_URL, USER, PW)) {
            // 连上即可；内容由各用例各自判定。
        } catch (Exception e) {
            abort("8066 无 Mycat 基础设施 → TLS 矩阵格 NO_DATA（待 run-matrix 起 mycat+后端）：" + e.getMessage());
        }
    }

    /** TLS-ON 格：REQUIRED 连上（官方语义=已 TLS）且 select 1 往返成功；连不上即真回归。 */
    @Test
    public void requiredTlsConnectsAndRoundTrips() throws Exception {
        assumeInfraUp();
        assumeTrue("true".equalsIgnoreCase(System.getProperty("mycat.tls.expected", "false")),
                "Mycat 未以 tlsMode!=DISABLED 起（或 run-matrix 未置 -Dmycat.tls.expected=true）→ TLS-on 格 N/A（非回归）");
        try (Connection c = DriverManager.getConnection(TLS_ON_URL, USER, PW)) {
            // sslMode=REQUIRED 能建连即证明服务端广告 CLIENT_SSL 且同连接 STARTTLS 升级成功（Connector/J 不回退明文）。
            List<Map<String, Object>> rows = executeQuery(c, "select 1");
            assertEquals(1, rows.size(), "TLS 通道上 select 1 往返应成功");
        }
    }

    /** TLS-OFF 格：DISABLED 连上且 select 1 往返成功（证未强制 TLS、明文路径 Task3 后仍不破）。 */
    @Test
    public void disabledTlsStaysPlaintextAndRoundTrips() throws Exception {
        assumeInfraUp();
        try (Connection c = DriverManager.getConnection(TLS_OFF_URL, USER, PW)) {
            List<Map<String, Object>> rows = executeQuery(c, "select 1");
            assertEquals(1, rows.size(), "明文通道 select 1 往返应成功");
        }
    }
}
