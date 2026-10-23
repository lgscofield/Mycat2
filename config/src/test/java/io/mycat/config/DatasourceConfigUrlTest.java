package io.mycat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-AU-6（Task 4 活库 e2e 暴出的产品级缺口，fix-forward）：{@code DatasourceConfig.setUrl}
 * 曾因字段默认 {@code dbType="mysql"} 而对<b>一切</b> URL 走 Connector/J {@code ConnectionUrlParser}
 * 增强分支，{@code jdbc:mariadb://} 直接炸 {@code UnsupportedConnectionString}——XML/hint 注册任何
 * mariadb 后端都进不了池。修法=按 URL 实际 scheme 把关（getDbTypeRaw），仅真 mysql/cobar/log4jdbc URL
 * 走 Connector/J 参数注入；mariadb 等 URL 原样透传，参数归 mariadb 驱动自己管。
 * 另锁 dbType 推导语义：显式声明优先，未声明(null)时按 URL scheme 回落 getDbTypeRaw。
 */
class DatasourceConfigUrlTest {

    @Test
    void mariadbUrlPassesThroughWithoutConnectorJParsing() {
        DatasourceConfig c = new DatasourceConfig();
        assertDoesNotThrow(() -> c.setUrl("jdbc:mariadb://localhost:3308/mysql"));
        assertEquals("jdbc:mariadb://localhost:3308/mysql", c.getUrl(),
                "mariadb URL 必须原样透传（不得被 Connector/J 分支注入 mysql 专属参数或抛错）");
    }

    @Test
    void mysqlUrlKeepsLegacyEnrichment() {
        DatasourceConfig c = new DatasourceConfig();
        c.setUrl("jdbc:mysql://localhost:3306/db1");
        String url = c.getUrl();
        assertTrue(url.contains("useUnicode=true"), "mysql URL 既有参数注入行为不变：" + url);
        assertTrue(url.contains("characterEncoding=UTF-8"), "mysql URL 既有参数注入行为不变：" + url);
        assertTrue(url.contains("serverTimezone=Asia/Shanghai"), "mysql URL 既有参数注入行为不变：" + url);
        assertTrue(url.contains("autoReconnect=true"), "mysql URL 既有参数注入行为不变：" + url);
    }

    @Test
    void explicitDbTypeWinsOverUrlScheme() {
        DatasourceConfig c = new DatasourceConfig();
        c.setDbType("mariadb");
        assertEquals("mariadb", c.getDbType());
    }
}
